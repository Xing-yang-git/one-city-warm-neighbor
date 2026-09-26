package com.platform.ai.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentSessionGuard 用户级互斥锁单元测试 — 覆盖可重入、串行化、降级与 ThreadLocal 清理。
 *
 * <p>AgentSessionGuard 是<b>被测对象本身</b>，全程使用真实实例而非 Mock（Mock 的 runExclusive
 * 默认返回 null 会让临界区逻辑根本不执行）。</p>
 */
@DisplayName("AgentSessionGuard 用户级互斥锁单元测试")
class AgentSessionGuardTest {

    private static final long USER_ID = 1L;

    /** 真实被测对象，不 Mock */
    private AgentSessionGuard guard;

    /** 捕获 AgentSessionGuard 日志的 logback appender，用于验证降级 WARN */
    private Logger guardLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        guard = new AgentSessionGuard();
        guardLogger = (Logger) LoggerFactory.getLogger(AgentSessionGuard.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        guardLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        guardLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ==================== 反射辅助 ====================

    @SuppressWarnings("unchecked")
    private Map<Long, ReentrantLock> locks() {
        return (Map<Long, ReentrantLock>) ReflectionTestUtils.getField(guard, "locks");
    }

    @SuppressWarnings("unchecked")
    private ThreadLocal<Set<Long>> degradedUsers() {
        return (ThreadLocal<Set<Long>>) ReflectionTestUtils.getField(guard, "degradedUsers");
    }

    /** 断言日志中出现过含指定关键字（如「降级」）的 WARN */
    private boolean hasWarnContaining(String keyword) {
        return logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(keyword));
    }

    // ==================== 可重入 ====================

    @Test
    @DisplayName("同线程嵌套 - 内层立即进入且外层退出后锁真正释放")
    void should_reenterWithoutBlocking_andReleaseLock_when_nestedSameUser() throws Exception {
        // Arrange
        AtomicInteger enteredDepth = new AtomicInteger();

        // Act：嵌套两层 runExclusive
        String result = guard.runExclusive(USER_ID, () -> {
            enteredDepth.incrementAndGet();
            String inner = guard.runExclusive(USER_ID, () -> {
                enteredDepth.incrementAndGet();
                return "inner";
            });
            return "outer+" + inner;
        });

        // Assert：两层都进入，且未抛 IllegalMonitorStateException
        // （回归 isHeldByCurrentThread 陷阱——该做法不递增持有计数，会让配对的 unlock 提前释放一层锁）
        assertThat(result).isEqualTo("outer+inner");
        assertThat(enteredDepth.get()).isEqualTo(2);

        // 外层退出后锁真正释放：持有计数归零、未被锁定
        ReentrantLock lock = locks().get(USER_ID);
        assertThat(lock.getHoldCount()).isZero();
        assertThat(lock.isLocked()).isFalse();

        // 另起线程能重新获取该锁，进一步证明确实已释放
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> reacquired = pool.submit(() -> {
                AtomicBoolean heldByThisThread = new AtomicBoolean(false);
                guard.runExclusive(USER_ID, () -> {
                    heldByThisThread.set(lock.isHeldByCurrentThread());
                    return null;
                });
                return heldByThisThread.get();
            });
            assertThat(reacquired.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 互斥隔离 ====================

    @Test
    @DisplayName("同 userId 并发 - 临界区内同时只允许一个线程")
    void should_serializeEntries_when_sameUserConcurrent() throws Exception {
        // Arrange
        int threadCount = 8;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        guard.runExclusive(USER_ID, () -> {
                            int now = concurrent.incrementAndGet();
                            maxConcurrent.accumulateAndGet(now, Math::max);
                            try {
                                // 临界区内驻留，制造重叠窗口
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            concurrent.decrementAndGet();
                            return null;
                        });
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

        // Assert：同时进入临界区的线程数峰值为 1
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 userId - 互不阻塞可同时进入临界区")
    void should_notBlock_when_differentUsers() throws Exception {
        // Arrange
        long userA = 1L;
        long userB = 2L;
        CountDownLatch bothIn = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (long uid : new long[]{userA, userB}) {
                pool.submit(() -> guard.runExclusive(uid, () -> {
                    bothIn.countDown();
                    try {
                        // 持锁等待对方进入：若两把锁互相排斥，此处会阻塞到超时
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }

            // Act & Assert：两把独立锁，双方应同时进入（短超时内计数归零）
            assertThat(bothIn.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ==================== 超时降级 ====================

    @Test
    @DisplayName("锁超时 - 等待超时后降级不加锁执行并记 WARN")
    void should_degradeAndWarn_when_lockTimeout() throws Exception {
        // Arrange：小超时，持锁线程驻留 3s
        ReflectionTestUtils.setField(guard, "lockTimeoutMs", 100L);
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> guard.runExclusive(USER_ID, () -> {
            holderIn.countDown();
            try {
                releaseHolder.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertThat(holderIn.await(2, TimeUnit.SECONDS)).isTrue();

        // Act
        AtomicBoolean actionRan = new AtomicBoolean(false);
        long start = System.nanoTime();
        String result = guard.runExclusive(USER_ID, () -> {
            actionRan.set(true);
            return "degraded";
        });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        releaseHolder.countDown();
        holder.join(2000);

        // Assert：超时后仍执行 action，且在约一个超时内返回（远早于持锁方 3s 释放）
        assertThat(result).isEqualTo("degraded");
        assertThat(actionRan).isTrue();
        assertThat(elapsedMs).isLessThan(400L);
        assertThat(hasWarnContaining("降级")).isTrue();
    }

    @Test
    @DisplayName("降级后嵌套 - 内层不再重复等待一个完整超时")
    void should_notRepeatTimeout_when_nestedAfterDegrade() throws Exception {
        // Arrange：小超时 300ms，持锁线程驻留 3s
        ReflectionTestUtils.setField(guard, "lockTimeoutMs", 300L);
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> guard.runExclusive(USER_ID, () -> {
            holderIn.countDown();
            try {
                releaseHolder.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertThat(holderIn.await(2, TimeUnit.SECONDS)).isTrue();

        // Act：外层超时降级后，内层嵌套调用应直接执行
        AtomicInteger innerRuns = new AtomicInteger();
        long start = System.nanoTime();
        guard.runExclusive(USER_ID, () -> {
            guard.runExclusive(USER_ID, () -> {
                innerRuns.incrementAndGet();
                return null;
            });
            return null;
        });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        releaseHolder.countDown();
        holder.join(2000);

        // Assert：内层确实执行，且总耗时只含一次超时（约 300ms），远小于两倍超时的 600ms
        assertThat(innerRuns.get()).isEqualTo(1);
        assertThat(elapsedMs).isLessThan(500L);
    }

    @Test
    @DisplayName("降级执行结束 - ThreadLocal degradedUsers 不残留")
    void should_removeDegradedUser_when_degradeCompletes() throws Exception {
        // Arrange：小超时，持锁线程驻留
        ReflectionTestUtils.setField(guard, "lockTimeoutMs", 100L);
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> guard.runExclusive(USER_ID, () -> {
            holderIn.countDown();
            try {
                releaseHolder.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertThat(holderIn.await(2, TimeUnit.SECONDS)).isTrue();

        // Act：本线程超时降级执行
        guard.runExclusive(USER_ID, () -> "degraded");

        releaseHolder.countDown();
        holder.join(2000);

        // Assert：withInitial 使 get() 恒非 null，故断言「集合为空」而非「为 null」
        assertThat(degradedUsers().get()).isEmpty();
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("userId 为 null - 直通执行且不产生锁记录")
    void should_executeDirectly_when_userIdNull() {
        // Arrange
        AtomicBoolean ran = new AtomicBoolean(false);

        // Act
        String result = guard.runExclusive(null, () -> {
            ran.set(true);
            return "ok";
        });

        // Assert
        assertThat(result).isEqualTo("ok");
        assertThat(ran).isTrue();
        assertThat(locks()).isEmpty();
    }

    @Test
    @DisplayName("executeExclusive - 与 runExclusive 等价：执行动作并加锁")
    void should_runAndLock_when_executeExclusive() {
        // Arrange
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean heldDuringAction = new AtomicBoolean(false);
        AtomicBoolean lockCaptured = new AtomicBoolean(false);
        ReentrantLock[] captured = new ReentrantLock[1];

        // Act
        guard.executeExclusive(USER_ID, () -> {
            ran.set(true);
            ReentrantLock l = locks().get(USER_ID);
            if (l != null) {
                captured[0] = l;
                lockCaptured.set(true);
                heldDuringAction.set(l.isHeldByCurrentThread());
            }
        });

        // Assert：动作执行、动作期间持有锁、退出后释放
        assertThat(ran).isTrue();
        assertThat(lockCaptured).isTrue();
        assertThat(heldDuringAction).isTrue();
        assertThat(captured[0].isLocked()).isFalse();
        assertThat(captured[0].getHoldCount()).isZero();
    }

    @Test
    @DisplayName("等待锁被中断 - 恢复中断标志并降级执行 action")
    void should_keepInterruptAndDegrade_when_waitInterrupted() throws Exception {
        // Arrange：持锁线程驻留 3s，等待线程随之阻塞在 tryLock
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> guard.runExclusive(USER_ID, () -> {
            holderIn.countDown();
            try {
                releaseHolder.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertThat(holderIn.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean actionRan = new AtomicBoolean(false);
        AtomicBoolean interruptedInsideAction = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> guard.runExclusive(USER_ID, () -> {
            interruptedInsideAction.set(Thread.currentThread().isInterrupted());
            actionRan.set(true);
            return null;
        }));
        waiter.start();

        // Act：待其进入等待后中断
        Thread.sleep(150);
        waiter.interrupt();
        waiter.join(2000);

        releaseHolder.countDown();
        holder.join(2000);

        // Assert：中断被转换为降级——action 仍执行，且中断标志在 action 内保持为 true
        assertThat(actionRan).isTrue();
        assertThat(interruptedInsideAction.get()).isTrue();
    }
}
