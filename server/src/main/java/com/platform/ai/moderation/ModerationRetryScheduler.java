package com.platform.ai.moderation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审核重试调度器 — 定期把 Redis 重试队列里已到期的任务重新提交到审核池。
 *
 * <p>与 {@link ContentModerationScheduler}（每日 4 点全量扫待审核内容）职责分离：那个是兜底巡检，
 * 周期以天计；本类只负责「被拒任务的分钟级快速重投」，两者混在一起会让排查时分不清是哪种补偿生效。</p>
 *
 * <p><b>两道闸门避免热循环</b>：
 * <ol>
 *   <li><b>容量预检</b>：池子队列还有剩余容量才去取。否则「取出 → 又被拒 → 放回」每轮空转一次，
 *       白白消耗 Redis 往返，还会让任务的重试次数无谓增长、更快耗尽额度；</li>
 *   <li><b>到期过滤</b>：只取 score（下次可重投时间）已到的，未到期的留在队列里等退避结束。</li>
 * </ol>
 *
 * <p>超过重试上限的任务由 {@link ModerationRejectionStore} 拒绝入队，最终由内容状态本身
 * （始终为 {@code PENDING}）交由每日巡检兜底。</p>
 */
@Slf4j
@Component
public class ModerationRetryScheduler {

    private final ModerationRejectionStore rejectionStore;
    private final ModerationService moderationService;
    private final ThreadPoolTaskExecutor moderationExecutor;

    /** 单轮最多重投条数：池子腾出的位置有限，取多了只会「再被拒 + 白涨重试次数」 */
    @Value("${ai.moderation.retry-batch:5}")
    private int retryBatch;

    public ModerationRetryScheduler(ModerationRejectionStore rejectionStore,
                                    ModerationService moderationService,
                                    @Qualifier("moderationExecutor") ThreadPoolTaskExecutor moderationExecutor) {
        this.rejectionStore = rejectionStore;
        this.moderationService = moderationService;
        this.moderationExecutor = moderationExecutor;
    }

    /**
     * 扫描 Redis 重试队列并重投到期任务（每分钟一轮）。
     *
     * <p>队列为空或无到期任务时直接返回、不打日志，避免空转刷屏；仅在确有到期任务时输出一条汇总。</p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void retryRejected() {
        try {
            int remaining = moderationExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();
            if (remaining == 0) {
                // 池子仍满，本轮不取：避免「取出→再拒→放回」空转
                return;
            }
            // 只取池子当前腾得出的位置数：多取的任务会被再次拒绝、白涨 attempts 并加倍退避，无谓消耗重试额度
            List<ModerationRejectionStore.TaskRef> due =
                    rejectionStore.pollDue(Math.min(retryBatch, remaining), System.currentTimeMillis());
            if (due.isEmpty()) {
                return;
            }
            int dispatched = 0;
            for (ModerationRejectionStore.TaskRef task : due) {
                try {
                    if (moderationService.tryDispatchFromRetry(task.type(), task.id(), task.attempts())) {
                        dispatched++;
                    }
                } catch (Exception e) {
                    // 单条失败不中断本轮：其余任务照常重投
                    log.warn("审核重投失败，跳过本条: type={}, id={}, {}", task.type(), task.id(), e.getMessage());
                }
            }
            log.info("审核重投扫描: 到期 {} 条, 重投 {} 条, 队列存量 {} 条；累计被拒 {} / 超限 {} / Redis 失败 {}",
                    due.size(), dispatched, rejectionStore.size(),
                    rejectionStore.rejectedTotal(), rejectionStore.exhaustedTotal(), rejectionStore.redisFailTotal());
        } catch (Exception e) {
            log.warn("审核重投扫描失败（跳过本轮）: {}", e.getMessage());
        }
    }
}
