package com.platform.ai.agent;

import com.platform.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
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
 * <p><b>等待超时即明确失败</b>：拿不到锁时抛 {@link BizException}，<b>不再降级为不加锁执行</b>。
 * 降级会静默放弃互斥——等于放任这套锁本要防止的丢更新发生，且事后无人知晓；明确失败把问题暴露出来。
 * 失败的可见程度随调用点而异：请求早期阶段（问候写入、清空指令归档）位于 Controller 的 try 块内，
 * 会走 SSE error 事件告知用户；流式回复落库（{@code completeStream}）发生在回复已完整送达之后，
 * 由 Controller 捕获后只记 ERROR 日志、照常结束，表现为本轮历史未保存。</p>
 *
 * <p><b>与 {@link AgentTurnGuard} 的分工</b>：那道门是请求级的，管「同用户同时只允许一个 /chat 在飞」，
 * 覆盖整个 SSE 生命周期；本锁是写入级的，管「同一份 Redis 状态的读-改-写串行化」，覆盖 /chat、
 * /exit、/resume 与归档调度器全部写路径。门挡不住后三者（都不是 /chat，也没有前端可拦），故本锁不可撤。</p>
 *
 * <p>单实例部署用进程内锁即可；多实例时需换 Redis 分布式锁（v2，与 DocumentProcessGuard 同口径）。</p>
 *
 * <p>锁对象按 userId 缓存且<b>不清理</b>：key 空间受用户表规模约束（非时间/会话数维度增长），
 * 单条 ReentrantLock 约百字节、1 万用户约 1MB；而清理存在「刚移除又被别的线程 computeIfAbsent
 * 拿到新锁 → 同一用户两把锁并存」的竞态，即使使用两参 remove(key, value) 也无法消除
 * （等待队列为空与 acquire 之间存在窗口），收益远小于风险。</p>
 */
@Slf4j
@Component
public class AgentSessionGuard {

    /** 锁等待超过该毫秒数即记 WARN（仅用于观测锁竞争，不改变行为） */
    private static final long LOCK_SLOW_WARN_MS = 50L;

    /** 拿不到锁时抛出的业务文案（BizException 的 message 经 Controller 白名单透出给前端） */
    private static final String BUSY_MESSAGE = "会话正忙，请稍后重试";

    /** 每用户一把可重入锁（不清理，理由见类注释） */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 获取锁的最长等待毫秒数（超时抛业务异常）。字段直接初始化，使未经过 Spring 注入的单元测试也能得到合理超时 */
    @Value("${ai.agent.session-lock-timeout-ms:5000}")
    private long lockTimeoutMs = 5000L;

    /**
     * 在用户级互斥下执行有返回值的动作（同线程嵌套调用不阻塞）。
     *
     * @param userId 住户用户 ID（null 时不加锁直接执行）
     * @param action 临界区动作（Redis 读-改-写整段）
     * @param <T>    返回值类型
     * @return 动作返回值
     * @throws BizException 等待锁超时或被中断，此时 action 未执行
     */
    public <T> T runExclusive(Long userId, Supplier<T> action) {
        if (userId == null) {
            return action.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(userId, k -> new ReentrantLock());
        // 嵌套调用由 ReentrantLock 自身处理：本线程已持锁时 tryLock 立即返回 true 并递增持有计数，
        // 不进入等待与超时逻辑（不能用 isHeldByCurrentThread() 做快速路径——它只查询不计数，
        // 会让配对的 unlock 提前释放一层锁，嵌套场景抛 IllegalMonitorStateException）
        long startNs = System.nanoTime();
        boolean locked;
        try {
            locked = lock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标志后再失败，不吞掉「被中断」这一事实
            Thread.currentThread().interrupt();
            log.warn("Agent 会话锁等待被中断（本次操作未执行）: userId={}", userId);
            throw new BizException(BUSY_MESSAGE, e);
        }
        long waitedMs = (System.nanoTime() - startNs) / 1_000_000L;
        if (!locked) {
            log.warn("Agent 会话锁等待超时（本次操作未执行）: userId={}, 超时={}ms, 实际等待={}ms",
                    userId, lockTimeoutMs, waitedMs);
            throw new BizException(BUSY_MESSAGE);
        }
        if (waitedMs >= LOCK_SLOW_WARN_MS) {
            log.warn("Agent 会话锁等待耗时偏长: userId={}, 等待={}ms", userId, waitedMs);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
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
     * @throws BizException 等待锁超时或被中断，此时 action 未执行
     */
    public void executeExclusive(Long userId, Runnable action) {
        runExclusive(userId, () -> {
            action.run();
            return null;
        });
    }
}
