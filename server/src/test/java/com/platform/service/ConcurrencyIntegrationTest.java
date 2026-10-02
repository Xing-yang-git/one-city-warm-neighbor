package com.platform.service;

import com.platform.ai.embedding.EmbeddingService;
import com.platform.ai.matching.MatchingScheduler;
import com.platform.ai.moderation.ModerationService;
import com.platform.ai.search.SemanticSearchService;
import com.platform.common.BizException;
import com.platform.common.BizStatus;
import com.platform.common.DurationUnit;
import com.platform.model.dto.ApproveRequest;
import com.platform.model.dto.BorrowRequestDTO;
import com.platform.model.dto.IdleItemRequest;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.IdleItem;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.IdleItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发集成测试 — 验证「申请 / 审批 / 编辑」在同一把资源行锁下的互斥语义。
 *
 * <p><b>为什么必须是集成测试</b>：单元测试把仓储整个 mock 掉，`SELECT ... FOR UPDATE`
 * 这条加锁语句根本不存在，开多少线程都验证不出锁的效果。本类连接本地真实 PostgreSQL
 * （数据源沿用 application.yml），开多线程并发调用 Service，断言两条核心不变量：</p>
 * <ul>
 *   <li>同一物品被多个住户同时申请时，只能有一条申请成功（防重复申领）；</li>
 *   <li>申请与编辑并发时状态自洽，不出现「状态被旧数据覆盖」的丢更新。</li>
 * </ul>
 *
 * <p><b>数据隔离</b>：测试数据使用 {@code 9_100_000} 起的固定 ID 段（远离开发库真实数据的
 * 自增 ID），并在每个用例结束后按 ID 段清理，不在开发库留下残留。测试类关闭测试托管事务
 * （{@link Propagation#NOT_SUPPORTED}），否则多线程调用看不到测试事务里未提交的数据。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BorrowService.class, IdleService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.platform.IntegrationTestSupport#databaseReachable")
@DisplayName("并发集成测试 - 资源行锁与条件更新")
class ConcurrencyIntegrationTest {

    /** 测试数据 ID 起点，远离开发库真实数据的自增区间 */
    private static final long TEST_ID_BASE = 9_100_000L;
    private static final long TENANT_ID = TEST_ID_BASE + 1;
    private static final long OWNER_ID = TEST_ID_BASE + 2;
    private static final long BORROWER_ID_BASE = TEST_ID_BASE + 100;
    private static final long ITEM_ID_BASE = TEST_ID_BASE + 1_000;

    private static final int CONCURRENT_THREADS = 10;
    private static final long TIMEOUT_SECONDS = 30L;

    @Autowired
    private BorrowService borrowService;
    @Autowired
    private IdleService idleService;
    @Autowired
    private BorrowRequestRepository borrowRequestRepository;
    @Autowired
    private IdleItemRepository idleItemRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private NotificationService notificationService;
    @MockBean
    private UserActivityService userActivityService;
    @MockBean
    private EmbeddingService embeddingService;
    @MockBean
    private MatchingScheduler matchingScheduler;
    @MockBean
    private SemanticSearchService semanticSearchService;
    @MockBean
    private ModerationService moderationService;

    private final AtomicLong itemIdSequence = new AtomicLong(ITEM_ID_BASE);

    @BeforeEach
    void prepareTestData() {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "并发测试小区");
        insertUser(OWNER_ID, "并发测试物主");
    }

    @AfterEach
    void cleanUpTestData() {
        // 先删引用方再删被引用方，避免外键约束阻止删除
        jdbcTemplate.update("DELETE FROM borrow_requests WHERE idle_id >= ?", ITEM_ID_BASE);
        jdbcTemplate.update("DELETE FROM idle_items WHERE id >= ?", ITEM_ID_BASE);
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM users WHERE id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", TENANT_ID);
    }

    @Test
    @DisplayName("并发申请同一物品 - 10 个住户同时申请，只有 1 条成功且物品进入待审批")
    void should_allowOnlyOneApplication_when_concurrentApply() throws Exception {
        // 准备：一件在线物品 + 10 个不同住户
        Long itemId = createOnlineItem();
        List<Long> borrowerIds = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            Long borrowerId = BORROWER_ID_BASE + i;
            insertUser(borrowerId, "并发测试住户" + i);
            borrowerIds.add(borrowerId);
        }

        // 执行：所有线程在同一个起跑线上同时发起申请
        List<Boolean> results = runConcurrently(borrowerIds,
                borrowerId -> {
                    borrowService.apply(borrowerId, borrowRequestOf(itemId));
                    return true;
                });

        // 断言：悲观锁把「查状态 → 建申请 → 改状态」串行化，只有第一个线程能通过状态校验
        assertThat(countSuccess(results)).isEqualTo(1);
        assertThat(borrowRequestRepository.findByIdleId(itemId)).hasSize(1);
        assertThat(loadItem(itemId).getStatus()).isEqualTo(BizStatus.PENDING);
    }

    @Test
    @DisplayName("并发审批同一申请 - 只有 1 次生效，后到者收到「已被处理」")
    void should_allowOnlyOneDecision_when_concurrentApprove() throws Exception {
        // 准备：一件在线物品 + 一条待审批申请（物主将并发审批两次）
        Long itemId = createOnlineItem();
        Long borrowerId = BORROWER_ID_BASE;
        insertUser(borrowerId, "并发测试住户");
        borrowService.apply(borrowerId, borrowRequestOf(itemId));
        Long borrowId = borrowRequestRepository.findByIdleId(itemId).get(0).getId();

        ApproveRequest approve = new ApproveRequest();
        approve.setApproved(true);

        // 执行：两个线程同时审批同一条申请
        List<Boolean> results = runConcurrently(List.of(1, 2), ignored -> {
            borrowService.approveReject(OWNER_ID, borrowId, approve);
            return true;
        });

        // 断言：条件更新只放行一次，另一次命中 0 行并抛业务异常
        assertThat(countSuccess(results)).isEqualTo(1);
    }

    @Test
    @DisplayName("申请与编辑并发 - 二者不会同时成功，且不出现状态被旧数据覆盖的丢更新")
    void should_keepStateConsistent_when_applyAndEditRace() throws Exception {
        // 准备：一件在线物品 + 一个住户
        Long itemId = createOnlineItem();
        Long borrowerId = BORROWER_ID_BASE;
        insertUser(borrowerId, "并发测试住户");

        // 执行：申请与编辑几乎同时发起，二者竞争同一把物品行锁
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        Future<Boolean> applyFuture = pool.submit(guarded(startGate, () -> {
            borrowService.apply(borrowerId, borrowRequestOf(itemId));
            return true;
        }));
        Future<Boolean> editFuture = pool.submit(guarded(startGate, () -> {
            idleService.update(OWNER_ID, itemId, editRequestOf());
            return true;
        }));
        startGate.countDown();
        boolean applied = applyFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boolean edited = editFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        pool.shutdown();

        // 断言：谁先拿到锁谁办完，后者醒来看到最新状态后必须被拒
        assertThat(applied).isNotEqualTo(edited);

        List<BorrowRequest> pendingApplications = borrowRequestRepository
                .findByIdleIdInAndStatus(List.of(itemId), BizStatus.PENDING);
        IdleItem finalItem = loadItem(itemId);

        if (applied) {
            // 申请先成功 → 编辑必须被「有住户正在申请」的门禁挡下，物品状态保持待审批
            assertThat(pendingApplications).hasSize(1);
            assertThat(finalItem.getStatus()).isEqualTo(BizStatus.PENDING);
        } else {
            // 编辑先成功 → 申请被拒，物品进入待审核，不留任何待审批申请
            assertThat(pendingApplications).isEmpty();
            assertThat(finalItem.getStatus()).isEqualTo(BizStatus.PENDING_REVIEW);
        }
    }

    // ==================== 并发执行辅助 ====================

    /**
     * 让若干线程在同一时刻并发执行同一段逻辑，返回每个线程是否成功。
     *
     * @param inputs 每个线程的入参
     * @param action 线程执行的业务动作（抛出 {@link BizException} 视为被业务规则拒绝）
     * @return 与入参一一对应的成功标记
     */
    private <T> List<Boolean> runConcurrently(List<T> inputs, ConcurrentAction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(inputs.size());
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (T input : inputs) {
                futures.add(pool.submit(guarded(startGate, () -> action.run(input))));
            }
            startGate.countDown();

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 包装成「等待起跑令 → 执行业务 → 业务拒绝返回 false」的可调用任务。
     *
     * @param startGate 起跑闸门
     * @param action    业务动作
     * @return 可提交给线程池的任务
     */
    private Callable<Boolean> guarded(CountDownLatch startGate, Callable<Boolean> action) {
        return () -> {
            startGate.await();
            try {
                return action.call();
            } catch (BizException e) {
                return false;
            }
        };
    }

    private int countSuccess(List<Boolean> results) {
        return (int) results.stream().filter(Boolean::booleanValue).count();
    }

    /** 并发任务体：入参 → 是否成功 */
    @FunctionalInterface
    private interface ConcurrentAction<T> {
        boolean run(T input);
    }

    // ==================== 测试数据辅助 ====================

    private void insertUser(Long userId, String name) {
        // user_type 受 CHECK 约束限制，取值须为 '业主'/'租客'/'物业'/... （见 db/alter.sql）
        jdbcTemplate.update("INSERT INTO users (id, tenant_id, username, user_type, name, auth_status) "
                + "VALUES (?, ?, ?, '业主', ?, 'approved') ON CONFLICT (id) DO NOTHING",
                userId, TENANT_ID, "concurrency_test_" + userId, name);
    }

    private Long createOnlineItem() {
        Long itemId = itemIdSequence.incrementAndGet();
        jdbcTemplate.update("INSERT INTO idle_items (id, user_id, tenant_id, post_type, title, category, "
                + "\"condition\", price, duration_unit, pickup_method, status, is_proxy) "
                + "VALUES (?, ?, ?, 'LEND', '并发测试物品', '其他', 'normal', 0, 'day', 'self_pickup', 'online', false)",
                itemId, OWNER_ID, TENANT_ID);
        return itemId;
    }

    private BorrowRequestDTO borrowRequestOf(Long itemId) {
        BorrowRequestDTO req = new BorrowRequestDTO();
        req.setIdleId(itemId);
        req.setDurationType(DurationUnit.DAY);
        req.setDurationDays(1);
        req.setNote("并发测试申请");
        return req;
    }

    private IdleItemRequest editRequestOf() {
        IdleItemRequest req = new IdleItemRequest();
        req.setTitle("并发测试物品-已修改");
        return req;
    }

    private IdleItem loadItem(Long itemId) {
        return idleItemRepository.findById(itemId)
                .orElseThrow(() -> new BizException("测试物品不存在：" + itemId));
    }
}
