package com.platform.ai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Agent 热会话进程内互斥锁 — 按用户串行化「读 Redis → 改内存 → 写回 Redis」整段，消除并发丢更新。
 *
 * <p>热会话 value 是单个 JSON 字符串，所有写操作都是 getSession → 改对象 → saveSession 整体覆盖，
 * 读写之间隔着反序列化与内存变更（含 truncate 截断计算）。并发交错时后写覆盖先写，表现为
 * 「连续两条用户消息」脏数据，进而污染归档行、压缩段与 resume 回填。</p>
 *
 * <p><b>可重入</b>：appendTurn → SessionService.append、resume → archiveRemaining、
 * archiveIfNeeded → archiveWindow 均为同线程嵌套（外层已持锁，内层再次获取同一把锁），
 * ReentrantLock 重入不阻塞，嵌套层各自配对一次 unlock。</p>
 *
 * <p><b>死锁不可能发生</b>的前提是「全程只持有一把用户锁」：加锁方向恒为「无锁 → 用户锁」，
 * 不存在「用户锁 A → 用户锁 B」。<b>严禁嵌套获取两把不同用户的锁</b>——遍历多用户时必须逐个进出，
 * 例如归档调度器扫描多个会话时，每处理完一个用户再进入下一个。</p>
 *
 * <p><b>硬性约束</b>：被本锁保护的临界区内，最后一次 saveSession 之后不得再有任何 Redis 热会话写入。
 * 锁在 {@code @Transactional} 提交前释放，该不变量是「解锁后不回写覆盖后到者」的唯一依据。</p>
 *
 * <p>单实例部署用进程内锁即可；多实例时需换 Redis 分布式锁（v2，与 DocumentProcessGuard 同口径）。</p>
 *
 * <p>锁对象按 userId 缓存且<b>不清理</b>：key 空间受用户表规模约束（非时间/会话数维度增长），
 * 单条 ReentrantLock 约百字节、1 万用户约 1MB；而清理存在「刚移除又被别的线程 computeIfAbsent
 * 拿到新锁 → 同一用户两把锁并存」的竞态，即使使用两参 remove(key, value) 也无法消除
 * （等待队列为空与 acquire 之间存在窗口），收益远小于风险。</p>
 *
 * <p>等待超过 session-lock-timeout-ms 后<b>降级为不加锁执行</b>：与 Redis 不可用降级同口径，
 * 最坏情况等价于改造前行为，绝不因锁超时抛异常阻断对话主链路或影响 SSE 流。</p>
 */
@Slf4j
@Component
public class AgentSessionGuard {

    /** 锁等待超过该毫秒数即记 WARN（仅用于观测锁竞争，不改变行为） */
    private static final long LOCK_SLOW_WARN_MS = 50L;

    /** 每用户一把可重入锁（不清理，理由见类注释） */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 本线程已降级（未持锁）执行的用户 ID：嵌套调用据此跳过等待，避免外层 5s + 内层 5s 叠加超时。
     * 最外层降级退出时移除本条，集合为空时整体 remove，防止线程池复用线程时残留。
     */
    private final ThreadLocal<Set<Long>> degradedUsers = ThreadLocal.withInitial(HashSet::new);

    /** 获取锁的最长等待毫秒数（超时降级不加锁执行）。字段直接初始化，使未经过 Spring 注入的单元测试也能得到合理超时 */
    @Value("${ai.agent.session-lock-timeout-ms:5000}")
    private long lockTimeoutMs = 5000L;

    /**
     * 在用户级互斥下执行有返回值的动作（同线程嵌套调用不阻塞）。
     *
     * @param userId 住户用户 ID（null 时不加锁直接执行）
     * @param action 临界区动作（Redis 读-改-写整段）
     * @param <T>    返回值类型
     * @return 动作返回值
     */
    public <T> T runExclusive(Long userId, Supplier<T> action) {
        if (userId == null) {
            return action.get();
        }
        Set<Long> degraded = degradedUsers.get();
        if (degraded.contains(userId)) {
            // 本线程外层已降级：嵌套调用直接执行，不再重复等待一个完整超时
            return action.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(userId, k -> new ReentrantLock());
        // 嵌套调用由 ReentrantLock 自身处理：本线程已持锁时 tryLock 立即返回 true 并递增持有计数，
        // 不进入等待与超时逻辑（不能用 isHeldByCurrentThread() 做快速路径——它只查询不计数，
        // 会让配对的 unlock 提前释放一层锁，嵌套场景抛 IllegalMonitorStateException）
        boolean locked;
        boolean interrupted = false;
        long startNs = System.nanoTime();
        try {
            locked = lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
            interrupted = true;
            log.warn("Agent 会话锁等待被中断（降级不加锁执行）: userId={}", userId);
        }
        long waitedMs = (System.nanoTime() - startNs) / 1_000_000L;
        if (locked) {
            if (waitedMs >= LOCK_SLOW_WARN_MS) {
                log.warn("Agent 会话锁等待耗时偏长: userId={}, 等待={}ms", userId, waitedMs);
            }
        } else {
            degraded.add(userId);
            // 中断分支已单独记过分因日志，此处不再补一条「超时=5000ms」——那条文案与中断的事实不符
            if (!interrupted) {
                log.warn("Agent 会话锁等待超时（降级不加锁执行，存在并发丢更新风险）: userId={}, 超时={}ms",
                        userId, lockTimeoutMs);
            }
        }
        try {
            return action.get();
        } finally {
            if (locked) {
                lock.unlock();
            } else {
                degraded.remove(userId);
                if (degraded.isEmpty()) {
                    // 池化线程防残留：空集合整体移除，避免 ThreadLocal 持有失效集合
                    degradedUsers.remove();
                }
            }
        }
    }

    /**
     * 在用户级互斥下执行无返回值的动作。
     *
     * <p>刻意不提供 {@code runExclusive(Long, Runnable)} 重载：{@code () -> foo()} 这类语句表达式
     * 同时兼容 void 与有值两种函数式接口，Java 会报 ambiguous（与 ExecutorService#submit 同源问题），
     * 用不同方法名规避。</p>
     *
     * @param userId 住户用户 ID（null 时不加锁直接执行）
     * @param action 临界区动作
     */
    public void executeExclusive(Long userId, Runnable action) {
        runExclusive(userId, () -> {
            action.run();
            return null;
        });
    }
}
