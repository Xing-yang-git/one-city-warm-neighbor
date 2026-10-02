package com.platform.ai.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 对话请求门 — 同一住户同时只允许一个 /chat 在飞，后到的请求直接拒绝。
 *
 * <p>对话是有状态、强顺序的交互：同一用户的上一条回复还在生成时，第二条请求语义不明
 * （用户究竟想要哪一个回复？），而且两条会并发读改写同一份热会话。前端已用 sending 标记禁用
 * 发送按钮，本门是绕过前端时的兜底（重试、弱网重发、脚本重放）。</p>
 *
 * <p><b>与 {@link AgentSessionGuard} 的分工</b>：本门是请求级的，覆盖整个 SSE 生命周期，
 * 只拦「同一用户的第二个 /chat」；那道锁是写入级的，覆盖 /chat、/exit、/resume 与归档调度器的
 * 全部热会话写入。/exit 等不是 /chat，归档调度器更没有前端，本门都拦不住，所以锁不可撤——
 * 本门的作用是让锁几乎不再超时，退化为兜底。</p>
 *
 * <p><b>为何是进程内标记而非 Redis</b>：单实例部署下进程内集合即够，且<b>不需要 TTL</b>——
 * 进程崩溃时内存随进程消失，标记自然清空，不存在「持有者已死、标记永挂」的问题。
 * 若改用 Redis 标记，就必须引入 TTL（乃至看门狗续期）来兜底崩溃，凭空多一份复杂度与失败模式；
 * 多实例时再换（与 {@link com.platform.ai.document.DocumentProcessGuard} 同口径）。</p>
 *
 * <p><b>释放需凭令牌</b>：{@link #tryAcquire} 返回本次请求的令牌，{@link #release} 校验令牌一致才移除。
 * 若只按 userId 无条件移除，多释放点（onCompletion、doOnError、外层 catch）之间会误清
 * 「已被下一个请求重新获得」的门——幂等不等于所有权。</p>
 *
 * <p>释放挂在 SSE 的终止路径上（正常完成、异常、超时、客户端断开）。极端情况下（emitter 未按预期结束）
 * 由 SseEmitter 的 300 秒超时兜底释放。</p>
 */
@Component
public class AgentTurnGuard {

    /**
     * 对话权令牌 — 每次成功获取产生新实例，释放时凭<b>引用相等</b>校验所有权。
     *
     * <p>刻意不用 record：零组件的 record 值相等，任意两个令牌会互相 equals，
     * 用 {@code Map.remove(key, value)} 时会把别人的持有误判为自己而误删。</p>
     */
    public static final class TurnToken {
        /** 仅由 {@link AgentTurnGuard#tryAcquire} 创建 */
        private TurnToken() {
        }
    }

    /** 在飞请求表：userId → 该用户当前在飞请求的令牌 */
    private final Map<Long, TurnToken> inFlight = new ConcurrentHashMap<>();

    /**
     * 尝试占用某住户的对话权。
     *
     * @param userId 住户用户 ID，不可为 null
     * @return 本次请求的令牌；返回 null 表示该住户已有请求在飞，本次未获得对话权
     */
    public TurnToken tryAcquire(Long userId) {
        TurnToken token = new TurnToken();
        // putIfAbsent 原子：并发同时抢同一 userId 时只有一个能拿到
        return inFlight.putIfAbsent(userId, token) == null ? token : null;
    }

    /**
     * 释放住户对话权（SSE 流结束、异常或客户端断开时调用）。
     *
     * <p>令牌一致才移除，因此可安全地在多个终止路径上重复调用；令牌不一致说明门已被
     * 下一个请求重新获得，本次释放静默跳过。</p>
     *
     * @param userId 住户用户 ID
     * @param token  该请求在 {@link #tryAcquire} 处取得的令牌
     */
    public void release(Long userId, TurnToken token) {
        inFlight.remove(userId, token);
    }
}
