package com.platform.ai.moderation;

import com.platform.common.PostStatus;
import com.platform.common.ModerationStatus;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.IdleItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核结果写库集成测试 —— 守护「审核回调能真正落库」这条不变量。
 *
 * <p><b>存在的理由</b>：审核结果由 {@code ModerationService.applyResult} 写在
 * {@code moderationExecutor} 线程池线程上，该链路**没有任何外层事务**。它对数据库的写入用的是
 * 条件更新（{@code @Modifying} 查询），而 {@code @Modifying} 必须在事务内执行——一旦遗漏事务，
 * 会抛 {@code TransactionRequiredException}，结果是**所有审核结论都写不进去**，
 * 新发布内容永远停在 pending_review、首页不可见。</p>
 *
 * <p>单元测试把仓储整个 mock 掉，触达不到这条约束；故此处连真实 PostgreSQL，
 * 并在关闭测试托管事务（{@link Propagation#NOT_SUPPORTED}）的前提下直接调用仓储方法，
 * 复现「无线程池、无外层事务」的真实调用条件。</p>
 *
 * <p>测试数据使用 {@code 9_200_000} 起的固定 ID 段，用例结束后清理，不在开发库留下残留。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.platform.IntegrationTestSupport#databaseReachable")
@DisplayName("审核结果写库集成测试 - 条件更新须在事务内可执行")
class ModerationResultWriteIntegrationTest {

    /** 测试数据 ID 起点，远离开发库真实数据的自增区间（与并发集成测试的 9_100_000 段错开） */
    private static final long TEST_ID_BASE = 9_200_000L;
    private static final long TENANT_ID = TEST_ID_BASE + 1;
    private static final long USER_ID = TEST_ID_BASE + 2;
    private static final long IDLE_ID = TEST_ID_BASE + 10;
    private static final long HELP_ID = TEST_ID_BASE + 11;

    @Autowired
    private IdleItemRepository idleItemRepository;
    @Autowired
    private HelpRequestRepository helpRequestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareTestData() {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "审核写库测试小区");
        // user_type 受 CHECK 约束限制，取值须为 '业主'/'租客'/'物业'/...（见 db/alter.sql）
        jdbcTemplate.update("INSERT INTO users (id, tenant_id, username, user_type, name, auth_status) "
                        + "VALUES (?, ?, ?, '业主', ?, 'approved') ON CONFLICT (id) DO NOTHING",
                USER_ID, TENANT_ID, "moderation_write_test_" + USER_ID, "审核写库测试用户");
        insertIdleItem();
        insertHelpRequest();
    }

    @AfterEach
    void cleanUpTestData() {
        jdbcTemplate.update("DELETE FROM idle_items WHERE id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM help_requests WHERE id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM users WHERE id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM tenants WHERE id = ?", TENANT_ID);
    }

    @Test
    @DisplayName("闲置物品 - 仍待审核时条件更新落库：上线并记录审核等级")
    void should_writeIdleResult_when_stillPendingReview() {
        // 执行：模拟审核线程池线程上的调用（无外层事务）
        int updated = idleItemRepository.applyModerationResult(
                IDLE_ID, PostStatus.ONLINE, ModerationStatus.GREEN, null);

        // 断言
        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = loadIdleRow(IDLE_ID);
        assertThat(row.get("status")).isEqualTo(PostStatus.ONLINE);
        assertThat(row.get("moderation_status")).isEqualTo(ModerationStatus.GREEN);
        assertThat(row.get("delist_reason")).isNull();
    }

    @Test
    @DisplayName("闲置物品 - 内容状态已变更（被申请占用）时丢弃结果，不覆盖当前状态")
    void should_discardIdleResult_when_statusChanged() {
        // 准备：审核期间有人申请，物品已变为待审批
        jdbcTemplate.update("UPDATE idle_items SET status = ? WHERE id = ?", PostStatus.PENDING, IDLE_ID);

        // 执行
        int updated = idleItemRepository.applyModerationResult(
                IDLE_ID, PostStatus.ONLINE, ModerationStatus.GREEN, null);

        // 断言：命中 0 行，状态保持待审批不被错误上线
        assertThat(updated).isZero();
        assertThat(loadIdleRow(IDLE_ID).get("status")).isEqualTo(PostStatus.PENDING);
    }

    @Test
    @DisplayName("求助信息 - 仍待审核时条件更新落库：驳回并记录原因")
    void should_writeHelpResult_when_stillPendingReview() {
        // 执行
        int updated = helpRequestRepository.applyModerationResult(
                HELP_ID, PostStatus.OFFLINE, ModerationStatus.RED, "违规内容");

        // 断言
        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = loadHelpRow(HELP_ID);
        assertThat(row.get("status")).isEqualTo(PostStatus.OFFLINE);
        assertThat(row.get("moderation_status")).isEqualTo(ModerationStatus.RED);
        assertThat(row.get("delist_reason")).isEqualTo("违规内容");
    }

    // ==================== 测试数据辅助 ====================

    /** 插入处于「待审核 + 待 AI 审核」状态的测试数据，即条件更新应当命中的初始态 */
    private void insertIdleItem() {
        jdbcTemplate.update("INSERT INTO idle_items (id, user_id, tenant_id, post_type, title, category, "
                        + "\"condition\", price, duration_unit, pickup_method, status, moderation_status, is_proxy) "
                        + "VALUES (?, ?, ?, 'LEND', '审核写库测试物品', '其他', 'normal', 0, 'day', 'self_pickup', ?, ?, false)",
                IDLE_ID, USER_ID, TENANT_ID, PostStatus.PENDING_REVIEW, ModerationStatus.PENDING);
    }

    private void insertHelpRequest() {
        jdbcTemplate.update("INSERT INTO help_requests (id, user_id, tenant_id, title, category, status, moderation_status) "
                        + "VALUES (?, ?, ?, '审核写库测试求助', '其他', ?, ?)",
                HELP_ID, USER_ID, TENANT_ID, PostStatus.PENDING_REVIEW, ModerationStatus.PENDING);
    }

    /**
     * 绕过持久化上下文直接读闲置物品行，确保断言的是真正落库的值。
     *
     * @param id 物品 ID
     * @return 该行数据
     */
    private Map<String, Object> loadIdleRow(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, moderation_status, delist_reason FROM idle_items WHERE id = ?", id);
    }

    /**
     * 绕过持久化上下文直接读求助行，确保断言的是真正落库的值。
     *
     * @param id 求助 ID
     * @return 该行数据
     */
    private Map<String, Object> loadHelpRow(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, moderation_status, delist_reason FROM help_requests WHERE id = ?", id);
    }
}
