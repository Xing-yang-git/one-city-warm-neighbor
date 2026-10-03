package com.platform.ai.matching;

import com.platform.ai.embedding.EmbeddingService;
import com.platform.common.AppTimeZone;
import com.platform.common.PostStatus;
import com.platform.config.AiConfig;
import com.platform.common.NotificationType;
import com.platform.common.PostType;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.User;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.NotificationRepository;
import com.platform.repository.UserRepository;
import com.platform.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 供需匹配服务 — 当用户发布求借（WANTED）时，自动匹配历史出借（LEND）记录并向出借方推送通知。
 *
 * <p>核心流程：
 * <ol>
 *   <li>确保待匹配物品为 WANTED 类型</li>
 *   <li>若无语义向量，先调用 EmbeddingService 生成</li>
 *   <li>使用 pgvector 余弦距离查找最相似的历史 LEND 物品（completed/offline，2 个月内）</li>
 *   <li>对每个匹配到的出借方创建"需求匹配提醒"通知</li>
 *   <li>同一对 LEND-WANTED 只通知一次（持久去重）</li>
 * </ol>
 *
 * <p>匹配结果仅通知出借方（LEND 发布者），不通知求借方自身。</p>
 */
@Slf4j
@Service
public class MatchingService {

    private final IdleItemRepository idleItemRepository;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final EmbeddingService embeddingService;
    private final UserRepository userRepository;
    private final AiConfig aiConfig;

    public MatchingService(IdleItemRepository idleItemRepository,
                           NotificationService notificationService,
                           NotificationRepository notificationRepository,
                           EmbeddingService embeddingService,
                           UserRepository userRepository,
                           AiConfig aiConfig) {
        this.idleItemRepository = idleItemRepository;
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.embeddingService = embeddingService;
        this.userRepository = userRepository;
        this.aiConfig = aiConfig;
    }

    /**
     * 当发布 WANTED 时，匹配历史 LEND 并通知借出方。
     *
     * <p>匹配策略：
     * <ul>
     *   <li>搜索范围：同小区、LEND 类型、已完成/已下架状态、2 个月内更新的物品</li>
     *   <li>排除求借方自己发布的物品</li>
     *   <li>使用 pgvector 余弦距离排序，取前 6 条最相似结果</li>
     *   <li>对去重后的出借方逐一创建通知</li>
     * </ul>
     */
    public void matchWantedToLend(IdleItem wantedItem) {
        // 1. 确保是求借类型
        if (!PostType.WANTED.equals(wantedItem.getPostType())) {
            log.debug("物品 {} 非 WANTED 类型, 跳过匹配", wantedItem.getId());
            return;
        }

        // 2. 若无 embedding，先生成
        if (wantedItem.getEmbedding() == null) {
            log.debug("物品 {} 缺少语义向量，正在生成...", wantedItem.getId());
            try {
                embeddingService.updateItemEmbedding(wantedItem);
            } catch (Exception e) {
                log.error("为物品 {} 生成语义向量失败, 终止匹配", wantedItem.getId(), e);
                return;
            }
        }

        // 3. pgvector 向量相似度匹配
        List<Object[]> rows = idleItemRepository.findSimilarByEmbedding(
                wantedItem.getEmbedding(),
                wantedItem.getTenantId(),
                PostType.LEND,
                wantedItem.getUserId(),
                List.of(PostStatus.COMPLETED, PostStatus.OFFLINE),
                LocalDateTime.now(AppTimeZone.APP_ZONE).minusMonths(2),
                aiConfig.getSimilarityThreshold(),
                6);

        if (rows.isEmpty()) {
            log.debug("物品 {} 未找到匹配的历史 LEND", wantedItem.getId());
            return;
        }

        // 4. 获取求借方名字
        String wantedUserName = "未知用户";
        User wantedUser = userRepository.findById(wantedItem.getUserId()).orElse(null);
        if (wantedUser != null && wantedUser.getName() != null) {
            wantedUserName = wantedUser.getName();
        }

        // 5. 为每个匹配的借出方创建通知
        Set<Long> notifiedInBatch = new HashSet<>();
        int notifiedCount = 0;

        for (Object[] row : rows) {
            Long lendItemId = row[0] != null ? ((Number) row[0]).longValue() : null;
            Long lenderUserId = row[2] != null ? ((Number) row[2]).longValue() : null;

            if (lendItemId == null || lenderUserId == null) {
                log.warn("匹配结果数据异常，跳过: row={}", (Object) row);
                continue;
            }

            // 批次内去重
            if (!notifiedInBatch.add(lenderUserId)) continue;

            // 持久去重
            if (notificationRepository.existsByUserIdAndTypeAndRelatedId(
                    lenderUserId, NotificationType.MATCH_DEMAND, wantedItem.getId())) {
                continue;
            }

            String content = "邻居住户" + wantedUserName + "正在求借「"
                    + wantedItem.getTitle() + "」，你之前出借过类似物品，要看看吗？";

            notificationService.create(
                    lenderUserId,
                    NotificationType.MATCH_DEMAND,
                    "需求匹配提醒",
                    content,
                    wantedItem.getId());

            notifiedCount++;
            log.info("匹配通知: lenderUserId={} ← wantedItemId={}, lendItemId={}",
                    lenderUserId, wantedItem.getId(), lendItemId);
        }

        log.info("供需匹配完成: wantedItemId={}, 候选{}条, 通知{}位出借方",
                wantedItem.getId(), rows.size(), notifiedCount);
    }
}
