package com.platform.ai.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentTurnGuard 对话请求门单元测试 — 覆盖同用户互斥、令牌所有权、并发抢占与跨用户隔离。
 *
 * <p>AgentTurnGuard 是<b>被测对象本身</b>，使用真实实例（内部依赖仅为 ConcurrentHashMap，无需 Mock）。</p>
 */
@DisplayName("AgentTurnGuard 对话请求门单元测试")
class AgentTurnGuardTest {

    private static final long USER_ID = 1L;

    /** 真实被测对象，不 Mock */
    private AgentTurnGuard guard;

    @BeforeEach
    void setUp() {
        guard = new AgentTurnGuard();
    }

    @Test
    @DisplayName("同用户已在飞 - 第二次获取返回 null")
    void should_rejectSecondAcquire_when_sameUserInFlight() {
        // Act & Assert：第一次拿到令牌，未释放前第二次被拒
        assertThat(guard.tryAcquire(USER_ID)).isNotNull();
        assertThat(guard.tryAcquire(USER_ID)).isNull();
    }

    @Test
    @DisplayName("凭令牌释放后 - 同用户可再次获取")
    void should_allowReacquire_when_releasedWithToken() {
        // Arrange
        AgentTurnGuard.TurnToken token = guard.tryAcquire(USER_ID);

        // Act
        guard.release(USER_ID, token);

        // Assert
        assertThat(guard.tryAcquire(USER_ID)).isNotNull();
    }

    @Test
    @DisplayName("陈旧令牌释放 - 不误清新持有者的门")
    void should_notEvictNewHolder_when_releaseWithStaleToken() {
        // Arrange：第一次持有并释放，门被下一个请求重新获得
        AgentTurnGuard.TurnToken first = guard.tryAcquire(USER_ID);
        guard.release(USER_ID, first);
        guard.tryAcquire(USER_ID);

        // Act：迟到的重复释放（携带已被替换的旧令牌）
        guard.release(USER_ID, first);

        // Assert：新持有者仍在门内，第三个请求仍被拒（回归「幂等 ≠ 所有权」缺口）
        assertThat(guard.tryAcquire(USER_ID)).isNull();
    }

    @Test
    @DisplayName("同一令牌重复释放 - 幂等且不影响后续获取")
    void should_beIdempotent_when_sameTokenReleasedRepeatedly() {
        // Arrange
        AgentTurnGuard.TurnToken token = guard.tryAcquire(USER_ID);

        // Act：模拟多点释放（onCompletion、doOnError、外层 catch 各补一次）
        guard.release(USER_ID, token);
        guard.release(USER_ID, token);
        guard.release(USER_ID, token);

        // Assert
        assertThat(guard.tryAcquire(USER_ID)).isNotNull();
    }

    @Test
    @DisplayName("并发抢占同一用户 - 恰好一个成功")
    void should_allowExactlyOne_when_concurrentAcquire() throws Exception {
        // Arrange
        int threadCount = 8;
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (guard.tryAcquire(USER_ID) != null) {
                            acquired.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            // Act：同时放行全部线程
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Assert：putIfAbsent 原子性保证只有一条进入
        assertThat(acquired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同用户 - 互不阻塞")
    void should_notBlock_when_differentUsers() {
        // Act & Assert：A 在飞不影响 B
        assertThat(guard.tryAcquire(1L)).isNotNull();
        assertThat(guard.tryAcquire(2L)).isNotNull();
    }
}
