package com.platform.ai.moderation;

import com.platform.common.PostStatus;
import com.platform.common.ModerationStatus;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.IdleItem;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.IdleItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内容审核定时扫描器 — 每天凌晨 4 点扫描审核状态仍为 pending 的内容，重新提交审核。
 *
 * <p>用于处理首次审核失败的边界情况（如 API 临时不可用导致审核未完成），
 * 确保所有发布内容最终都能完成审核。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentModerationScheduler {

    private final IdleItemRepository idleItemRepository;
    private final HelpRequestRepository helpRequestRepository;
    private final ModerationService moderationService;

    /**
     * 每天凌晨 4 点执行：扫描审核状态为 pending 的所有内容并重新提交审核。
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void rescanPendingModerations() {
        log.info("定时扫描未完成审核的内容...");

        // 扫描闲置物品
        List<IdleItem> pendingIdleItems = idleItemRepository.findByStatus(PostStatus.PENDING_REVIEW);
        if (!pendingIdleItems.isEmpty()) {
            log.info("发现 {} 条闲置物品审核状态为 pending，重新提交审核", pendingIdleItems.size());
            for (IdleItem item : pendingIdleItems) {
                if (ModerationStatus.PENDING.equals(item.getModerationStatus())) {
                    moderationService.scheduleModeration(item);
                }
            }
        }

        // 扫描求助信息
        List<HelpRequest> pendingHelpRequests = helpRequestRepository.findByStatus(PostStatus.PENDING_REVIEW);
        if (!pendingHelpRequests.isEmpty()) {
            log.info("发现 {} 条求助信息审核状态为 pending，重新提交审核", pendingHelpRequests.size());
            for (HelpRequest hr : pendingHelpRequests) {
                if (ModerationStatus.PENDING.equals(hr.getModerationStatus())) {
                    moderationService.scheduleModeration(hr);
                }
            }
        }

        log.info("定时扫描完成");
    }
}
