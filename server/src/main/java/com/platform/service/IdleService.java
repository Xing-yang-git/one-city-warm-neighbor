package com.platform.service;

import com.platform.ai.embedding.EmbeddingService;
import com.platform.ai.matching.MatchingScheduler;
import com.platform.ai.moderation.ModerationService;
import com.platform.ai.search.SemanticSearchService;
import com.platform.common.AppTimeZone;
import com.platform.common.BizException;
import com.platform.common.BizStatus;
import com.platform.common.ModerationStatus;
import com.platform.common.NotificationType;
import com.platform.common.PostType;
import com.platform.common.UserFormatter;
import com.platform.model.dto.IdleItemDTO;
import com.platform.model.dto.IdleItemRequest;
import com.platform.model.dto.PageDTO;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.User;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 闲置物品业务逻辑 — 发布、浏览、搜索、详情、编辑、下架、删除。
 *
 * <p>涉及物品状态变更的写入口（申请、审批、编辑、下架）统一使用物品行悲观锁
 * （{@code findByIdWithLock}）串行化，避免并发下互相覆盖状态。</p>
 */
@Slf4j
@Service
@Transactional
public class IdleService {

    private final IdleItemRepository idleItemRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final BorrowRequestRepository borrowRequestRepository;
    private final RatingRepository ratingRepository;
    private final UserActivityService userActivityService;
    private final SemanticSearchService semanticSearchService;
    private final EmbeddingService embeddingService;
    private final MatchingScheduler matchingScheduler;
    private final ModerationService moderationService;
    private final NotificationService notificationService;

    public IdleService(IdleItemRepository idleItemRepository,
                       UserRepository userRepository,
                       RoomRepository roomRepository,
                       BorrowRequestRepository borrowRequestRepository,
                       RatingRepository ratingRepository,
                       UserActivityService userActivityService,
                       EmbeddingService embeddingService,
                       MatchingScheduler matchingScheduler,
                       SemanticSearchService semanticSearchService,
                       ModerationService moderationService,
                       NotificationService notificationService) {
        this.idleItemRepository = idleItemRepository;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.borrowRequestRepository = borrowRequestRepository;
        this.ratingRepository = ratingRepository;
        this.userActivityService = userActivityService;
        this.embeddingService = embeddingService;
        this.matchingScheduler = matchingScheduler;
        this.semanticSearchService = semanticSearchService;
        this.moderationService = moderationService;
        this.notificationService = notificationService;
    }

    public IdleItemDTO publish(Long userId, IdleItemRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        IdleItem item = new IdleItem();
        item.setUserId(userId);
        item.setTenantId(user.getTenantId());
        item.setTitle(req.getTitle());
        item.setDescription(req.getDescription());
        item.setPostType(req.getPostType());
        item.setCategory(req.getCategory());
        item.setCondition(req.getCondition() != null ? req.getCondition() : BizStatus.NORMAL);
        item.setImages(req.getImages());
        item.setPrice(req.getPrice() != null ? req.getPrice() : BigDecimal.ZERO);
        item.setMaxDuration(req.getMaxDuration() != null ? req.getMaxDuration() : 7);
        item.setDurationUnit(req.getDurationUnit() != null ? req.getDurationUnit() : "day");
        item.setPickupMethod(req.getPickupMethod() != null ? req.getPickupMethod() : "self_pickup");
        item.setIsProxy(req.getIsProxy() != null && req.getIsProxy());
        // 发布后先挂起，等待 AI 异步审核
        item.setStatus(BizStatus.PENDING_REVIEW);
        item.setModerationStatus(ModerationStatus.PENDING);
        item.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        item = idleItemRepository.save(item);

        // 异步生成语义向量 + AI 内容审核，不阻塞发布响应
        final IdleItem savedItem = item;
        CompletableFuture.runAsync(() -> {
            try {
                embeddingService.updateItemEmbedding(savedItem);
                log.debug("物品 {} 语义向量已生成", savedItem.getId());
            } catch (Exception e) {
                log.error("异步生成语义向量失败: itemId={}", savedItem.getId(), e);
            }
            try {
                moderationService.scheduleModeration(savedItem);
            } catch (Exception e) {
                log.error("异步审核调度失败: itemId={}", savedItem.getId(), e);
            }
        });

        // WANTED 发布时，异步触发供需匹配
        if (PostType.WANTED.equals(item.getPostType())) {
            matchingScheduler.scheduleMatch(item);
        }

        return toDTO(item);
    }

    public PageDTO<IdleItemDTO> getHomeList(String postType, Long userId, int page, int size) {
        User user = userRepository.findById(userId).orElse(null);
        Long tenantId = user != null ? user.getTenantId() : null;
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<IdleItem> itemPage = tenantId != null
                ? idleItemRepository.findByStatusAndPostTypeAndTenantId(BizStatus.ONLINE, postType, tenantId, pageRequest)
                : idleItemRepository.findByStatusAndPostType(BizStatus.ONLINE, postType, pageRequest);

        List<IdleItemDTO> dtos = itemPage.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        return PageDTO.<IdleItemDTO>builder()
                .content(dtos)
                .totalElements(itemPage.getTotalElements())
                .totalPages(itemPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .build();
    }

    public IdleItemDTO getDetail(Long itemId) {
        return getDetail(itemId, null);
    }

    /**
     * 获取物品详情，可选带上当前用户的借用申请状态。
     */
    public IdleItemDTO getDetail(Long itemId, Long currentUserId) {
        IdleItem item = idleItemRepository.findById(itemId)
                .orElseThrow(() -> new BizException("物品不存在"));
        IdleItemDTO dto = enrichWithUserStats(toDTO(item));

        // 检查当前用户是否对该物品提交过借用申请
        if (currentUserId != null) {
            List<com.platform.model.entity.BorrowRequest> userRequests =
                    borrowRequestRepository.findByIdleId(itemId);
            for (com.platform.model.entity.BorrowRequest br : userRequests) {
                if (currentUserId.equals(br.getBorrowerId())) {
                    dto.setUserBorrowStatus(br.getStatus()); // "pending" | "approved" | "rejected" | "returned"
                    break;
                }
            }
        }

        return dto;
    }

    public PageDTO<IdleItemDTO> search(Long userId, String keyword, String postType, int page, int size) {
        return search(userId, keyword, postType, page, size, "keyword");
    }

    /**
     * 搜索闲置物品，支持三种搜索模式。
     *
     * @param userId   当前用户 ID（用于租户隔离）
     * @param keyword  搜索关键词
     * @param postType 发布类型筛选
     * @param page     页码（从 0 开始）
     * @param size     每页条数
     * @param mode     搜索模式：keyword(LIKE 关键词, 默认) / semantic(语义向量) / 其他或空(混合搜索)
     * @return 搜索结果分页
     */
    public PageDTO<IdleItemDTO> search(Long userId, String keyword, String postType, int page, int size, String mode) {
        // 与 getHomeList 保持一致的租户隔离——不同小区的数据不得互相搜到
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        Long tenantId = user != null ? user.getTenantId() : null;

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<IdleItem> itemPage;

        if (tenantId == null) {
            // 无租户上下文的兜底（理论上不应发生）
            itemPage = Page.empty();
        } else if ("semantic".equals(mode)) {
            // 纯语义搜索
            List<IdleItem> results = semanticSearchService.semanticSearchIdle(
                    keyword, tenantId, postType, size);
            int totalSize = results.size();
            int start = page * size;
            int end = Math.min(start + size, totalSize);
            List<IdleItem> pageResults = start < totalSize
                    ? results.subList(start, end)
                    : List.of();
            itemPage = new PageImpl<>(pageResults, pageRequest, totalSize);
        } else if (mode == null || mode.isEmpty() || "keyword".equals(mode)) {
            // 混合搜索 或 默认关键词搜索
            if ("keyword".equals(mode)) {
                // 纯关键词 LIKE 搜索（保持原有行为）
                itemPage = idleItemRepository.searchByTenant(
                        BizStatus.ONLINE, postType, tenantId, keyword, keyword, pageRequest);
            } else {
                // 混合搜索：语义 + 关键词去重合并
                itemPage = semanticSearchService.hybridSearch(
                        keyword, tenantId, postType, pageRequest);
            }
        } else {
            // 未知 mode，回退到混合搜索
            itemPage = semanticSearchService.hybridSearch(
                    keyword, tenantId, postType, pageRequest);
        }

        List<IdleItemDTO> dtos = itemPage.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        return PageDTO.<IdleItemDTO>builder()
                .content(dtos)
                .totalElements(itemPage.getTotalElements())
                .totalPages(itemPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .build();
    }

    public List<IdleItemDTO> getMyPosts(Long userId, String postType) {
        List<IdleItem> items = idleItemRepository
                .findByUserIdAndPostType(userId, postType, Pageable.unpaged())
                .getContent();
        return items.stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * 下架闲置物品（用户自行下架，状态转为草稿）。
     *
     * <p>与申请/编辑/审批共用同一把物品行锁，保证状态流转串行。下架后该物品的待审批申请
     * 已失去意义，一并置为已拒绝并通知申请人——否则申请会永远悬在「待回应」，
     * 申请人也收不到任何失效告知（与求助侧下架、管理端下架的既有做法一致）。</p>
     *
     * @param userId 操作用户 ID（须为物品所有者）
     * @param itemId 物品 ID
     * @return 下架后的物品
     * @throws BizException 物品不存在或非本人操作时抛出
     */
    public IdleItemDTO delist(Long userId, Long itemId) {
        IdleItem item = idleItemRepository.findByIdWithLock(itemId)
                .orElseThrow(() -> new BizException("物品不存在"));

        if (!item.getUserId().equals(userId)) {
            throw new BizException("无权操作该物品");
        }

        item.setStatus(BizStatus.DRAFT);
        item.setDelistReason("用户自行下架");
        item = idleItemRepository.save(item);

        List<BorrowRequest> pendingBorrows = borrowRequestRepository
                .findByIdleIdInAndStatus(List.of(itemId), BizStatus.PENDING);
        for (BorrowRequest pending : pendingBorrows) {
            pending.setStatus(BizStatus.REJECTED);
            borrowRequestRepository.save(pending);
            notificationService.create(pending.getBorrowerId(), NotificationType.AUDIT_RESULT,
                    "借入申请已拒绝",
                    "您的借入申请「" + item.getTitle() + "」因物品已下架而自动拒绝",
                    pending.getId());
        }
        if (!pendingBorrows.isEmpty()) {
            log.info("物品下架，作废待审批借入申请 {} 条: itemId={}", pendingBorrows.size(), itemId);
        }

        return toDTO(item);
    }

    public IdleItemDTO deleteItem(Long userId, Long itemId) {
        IdleItem item = idleItemRepository.findById(itemId)
                .orElseThrow(() -> new BizException("物品不存在"));

        if (!item.getUserId().equals(userId)) {
            throw new BizException("无权操作该物品");
        }

        item.setStatus(BizStatus.OFFLINE);
        item.setDelistReason("用户删除");
        item = idleItemRepository.save(item);
        return toDTO(item);
    }

    /**
     * 更新闲置物品（编辑保存或重新上架）。
     * 编辑后自动退回 pending_review 并重新排队 AI 审核，
     * 审核通过后自动上线。
     *
     * <p>加锁与门禁：与申请/审批/下架共用同一把物品行锁，锁内先拒绝两种不允许编辑的状态——
     * 有住户正在申请时编辑，会让申请人看到与申请时不一致的内容，且编辑与申请互相覆盖；
     * 审核中编辑，会让后台审核任务把旧内容的结论用到新内容上。二者一并挡在锁内。</p>
     *
     * @param userId 操作用户 ID（须为物品所有者）
     * @param itemId 物品 ID
     * @param req    编辑请求体
     * @return 编辑后的物品
     * @throws BizException 物品不存在、非本人操作、有待处理申请或正在审核时抛出
     */
    public IdleItemDTO update(Long userId, Long itemId, IdleItemRequest req) {
        IdleItem item = idleItemRepository.findByIdWithLock(itemId)
                .orElseThrow(() -> new BizException("物品不存在"));

        if (!item.getUserId().equals(userId)) {
            throw new BizException("无权操作该物品");
        }

        assertEditable(itemId, item);

        // 保存原始状态，用于判断是否需要重新审核
        String originalStatus = item.getStatus();

        item.setTitle(req.getTitle() != null ? req.getTitle() : item.getTitle());
        item.setDescription(req.getDescription() != null ? req.getDescription() : item.getDescription());
        item.setCategory(req.getCategory() != null ? req.getCategory() : item.getCategory());
        item.setCondition(req.getCondition() != null ? req.getCondition() : item.getCondition());
        item.setImages(req.getImages() != null ? req.getImages() : item.getImages());
        item.setPrice(req.getPrice() != null ? req.getPrice() : item.getPrice());
        item.setMaxDuration(req.getMaxDuration() != null ? req.getMaxDuration() : item.getMaxDuration());
        item.setDurationUnit(req.getDurationUnit() != null ? req.getDurationUnit() : item.getDurationUnit());
        item.setPickupMethod(req.getPickupMethod() != null ? req.getPickupMethod() : item.getPickupMethod());

        // 编辑后重新审核：原状态为 online/completed/offline 时，退回 pending_review
        boolean needsModeration = BizStatus.ONLINE.equals(originalStatus)
                || BizStatus.COMPLETED.equals(originalStatus)
                || BizStatus.OFFLINE.equals(originalStatus)
                || BizStatus.DRAFT.equals(originalStatus);
        if (needsModeration) {
            item.setStatus(BizStatus.PENDING_REVIEW);
            item.setModerationStatus(ModerationStatus.PENDING);
            // 从 completed/offline 重新发布时刷新时间
            if (BizStatus.COMPLETED.equals(originalStatus) || BizStatus.OFFLINE.equals(originalStatus)) {
                item.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
            }
        }

        item = idleItemRepository.save(item);

        // 异步重新生成语义向量 + 重新审核
        if (needsModeration) {
            final IdleItem savedItem = item;
            CompletableFuture.runAsync(() -> {
                try {
                    embeddingService.updateItemEmbedding(savedItem);
                    log.debug("物品 {} 语义向量已更新", savedItem.getId());
                } catch (Exception e) {
                    log.error("异步更新语义向量失败: itemId={}", savedItem.getId(), e);
                }
                try {
                    moderationService.scheduleModeration(savedItem);
                } catch (Exception e) {
                    log.error("异步审核调度失败: itemId={}", savedItem.getId(), e);
                }
            });
        }

        return toDTO(item);
    }

    /**
     * 编辑前置门禁 — 判定该物品当前是否允许被所有者编辑（须在物品行锁内调用）。
     *
     * <p>两类状态禁止编辑：有住户正在申请、内容仍在审核中。前者避免申请人看到的帖子
     * 与申请时不一致以及编辑与申请互相覆盖；后者保证审核期间内容不变，
     * 后台审核任务的结论必然对应它实际审过的那份内容。</p>
     *
     * @param itemId 物品 ID
     * @param item   已加锁的物品实体
     * @throws BizException 存在待处理申请或正在审核时抛出
     */
    private void assertEditable(Long itemId, IdleItem item) {
        List<BorrowRequest> pendingApplications = borrowRequestRepository
                .findByIdleIdInAndStatus(List.of(itemId), BizStatus.PENDING);
        if (!pendingApplications.isEmpty()) {
            throw new BizException("该物品有住户正在申请，请先处理申请后再编辑");
        }
        if (BizStatus.PENDING_REVIEW.equals(item.getStatus())) {
            throw new BizException("该帖子正在审核中，请等审核完成后再编辑");
        }
    }

    private IdleItemDTO enrichWithUserStats(IdleItemDTO dto) {
        Long userId = dto.getUserId();
        if (userId == null) return dto;

        Double avgScore = ratingRepository.getAverageScore(userId);
        // 暂无评分时默认 5.0（无互助记录的新用户）
        dto.setRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : 5.0);

        // 「以往记录」弹层五项统计 — 全站统一口径：已完成且被对方评价才计数。
        // 借入/借出按真实角色分流（WANTED 帖 owner/borrower 反转），归还率不设评价门槛。
        UserActivityService.InteractionStats stats = userActivityService.interactionStats(userId);
        dto.setBorrowCount((long) stats.borrowCount());
        dto.setLendCount((long) stats.lendCount());
        dto.setHelpCount((long) stats.helpReqCount());
        dto.setHelpedCount((long) stats.helpProCount());
        dto.setReturnRate(stats.returnedCount() > 0
                ? Math.round(stats.onTimeCount() * 100.0 / stats.returnedCount()) + "%"
                : "100%");
        return dto;
    }

    private IdleItemDTO toDTO(IdleItem item) {
        User user = userRepository.findById(item.getUserId()).orElse(null);
        String userName = user != null ? user.getName() : "未知用户";
        String roomInfo = UserFormatter.formatRoomWithType(user);

        return IdleItemDTO.builder()
                .id(item.getId())
                .userId(item.getUserId())
                .userName(userName)
                .userRoom(roomInfo)
                .title(item.getTitle())
                .description(item.getDescription())
                .postType(item.getPostType())
                .category(item.getCategory())
                .images(item.getImages())
                .price(item.getPrice())
                .condition(item.getCondition())
                .maxDuration(item.getMaxDuration() != null ? item.getMaxDuration() : 7)
                .durationUnit(item.getDurationUnit() != null ? item.getDurationUnit() : "day")
                .pickupMethod(item.getPickupMethod())
                .status(item.getStatus())
                .delistReason(item.getDelistReason())
                .isProxy(item.getIsProxy())
                .createdAt(item.getCreatedAt())
                .build();
    }

}
