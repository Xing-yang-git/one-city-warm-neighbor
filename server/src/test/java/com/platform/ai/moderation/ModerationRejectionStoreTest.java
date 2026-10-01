package com.platform.ai.moderation;

import com.platform.common.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModerationRejectionStore 审核拒绝处理器单元测试 — 覆盖入队、退避、超限放弃、Redis 故障自吞与到期取出。
 *
 * <p>核心铁律：{@code rejectedExecution} 在任何情况下都不得向外抛异常——它由线程池在提交线程上同步调用，
 * 外抛会把 {@code RejectedExecutionException} 带回发布接口的调用点。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationRejectionStore 审核拒绝处理器单元测试")
class ModerationRejectionStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private ModerationRejectionStore store;

    /** 触发拒绝的线程池：仅用于读取队列剩余容量，用真实实例即可（不提交任务则不会创建线程） */
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10));

    @BeforeEach
    void setUp() {
        store = new ModerationRejectionStore(redisTemplate);
        // @Value 字段在单测中不会被 Spring 注入，需手动设置，否则 maxAttempts=0 会让所有任务直接超限
        ReflectionTestUtils.setField(store, "maxAttempts", 3);
        ReflectionTestUtils.setField(store, "baseBackoffMs", 30_000L);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    /** 构造一个审核任务 */
    private ModerationTask task(String type, Long id, int attempts) {
        return new ModerationTask(type, id, attempts, () -> {
        });
    }

    @Test
    @DisplayName("拒绝 - 首次被拒写入重试队列并续期 TTL")
    void should_enqueue_when_firstRejected() {
        store.rejectedExecution(task(ContentType.IDLE, 1L, 0), executor);

        verify(zSetOperations).add(eq(ModerationRejectionStore.RETRY_KEY), contains("\"a\":1"), anyDouble());
        verify(redisTemplate).expire(eq(ModerationRejectionStore.RETRY_KEY), any(Duration.class));
        assertThat(store.rejectedTotal()).isEqualTo(1);
        assertThat(store.exhaustedTotal()).isZero();
    }

    @Test
    @DisplayName("拒绝 - 超过重试上限时不再入队，计入超限数")
    void should_giveUp_when_attemptsExhausted() {
        // 已试 3 次 + 本次 = 4，超过上限 3
        store.rejectedExecution(task(ContentType.IDLE, 1L, 3), executor);

        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
        assertThat(store.exhaustedTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("拒绝 - Redis 不可用时异常自吞，绝不外抛")
    void should_swallowException_when_redisFails() {
        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("Redis 连接失败"));

        assertThatCode(() -> store.rejectedExecution(task(ContentType.IDLE, 1L, 0), executor))
                .doesNotThrowAnyException();

        assertThat(store.redisFailTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("拒绝 - 非审核任务不写重试队列（避免污染队列载荷）")
    void should_notEnqueue_when_notModerationTask() {
        store.rejectedExecution(() -> {
        }, executor);

        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
        assertThat(store.rejectedTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("重投队列 - 取出到期任务并移除，畸形成员跳过")
    void should_pollDueAndSkipMalformed() {
        when(zSetOperations.rangeByScore(eq(ModerationRejectionStore.RETRY_KEY),
                anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(new LinkedHashSet<>(List.of("{\"t\":\"idle\",\"id\":7,\"a\":2}", "不是JSON")));

        List<ModerationRejectionStore.TaskRef> due = store.pollDue(5, System.currentTimeMillis());

        assertThat(due).hasSize(1);
        assertThat(due.get(0).type()).isEqualTo(ContentType.IDLE);
        assertThat(due.get(0).id()).isEqualTo(7L);
        assertThat(due.get(0).attempts()).isEqualTo(2);
        // 取出即移除：重投若再被拒会以新 member 重新入队，不会与本条重复
        verify(zSetOperations).remove(ModerationRejectionStore.RETRY_KEY, "不是JSON");
    }

    @Test
    @DisplayName("重投队列 - Redis 异常时返回空列表而非抛异常")
    void should_returnEmpty_when_pollFails() {
        when(zSetOperations.rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Redis 连接失败"));

        assertThat(store.pollDue(5, System.currentTimeMillis())).isEmpty();
    }
}
