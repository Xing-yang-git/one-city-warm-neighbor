package com.platform.service;

import com.platform.ai.moderation.ModerationService;
import com.platform.common.AppTimeZone;
import com.platform.common.BizException;
import com.platform.common.BizStatus;
import com.platform.common.ModerationStatus;
import com.platform.common.NotificationType;
import com.platform.common.UserFormatter;
import com.platform.model.dto.ApproveRequest;
import com.platform.model.dto.HelpRequestDTO;
import com.platform.model.dto.HelpResponseDTO;
import com.platform.model.dto.PageDTO;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.Notification;
import com.platform.model.entity.User;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Transactional
public class HelpService {

    private static final Logger log = LoggerFactory.getLogger(HelpService.class);

    private final HelpRequestRepository helpRequestRepository;
    private final HelpApplicationRepository helpApplicationRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RatingRepository ratingRepository;
    private final UserActivityService userActivityService;
    private final ModerationService moderationService;

    public HelpService(HelpRequestRepository helpRequestRepository,
                       HelpApplicationRepository helpApplicationRepository,
                       NotificationService notificationService,
                       UserRepository userRepository,
                       RoomRepository roomRepository,
                       RatingRepository ratingRepository,
                       UserActivityService userActivityService,
                       ModerationService moderationService) {
        this.helpRequestRepository = helpRequestRepository;
        this.helpApplicationRepository = helpApplicationRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.ratingRepository = ratingRepository;
        this.userActivityService = userActivityService;
        this.moderationService = moderationService;
    }

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public HelpResponseDTO publish(Long userId, HelpRequestDTO req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        HelpRequest helpRequest = new HelpRequest();
        helpRequest.setUserId(userId);
        helpRequest.setTenantId(user.getTenantId());
        helpRequest.setTitle(req.getTitle());
        helpRequest.setDescription(req.getDescription());
        helpRequest.setLocation(req.getLocation());
        helpRequest.setCategory(req.getCategory());
        helpRequest.setIsUrgent(req.getIsUrgent() != null && req.getIsUrgent());
        if (req.getTimeStart() != null && !req.getTimeStart().isEmpty()) {
            try {
                helpRequest.setTimeStart(LocalDateTime.parse(req.getTimeStart(), DT_FMT));
            } catch (Exception e) {
                log.debug("Failed to parse timeStart: {}", e.getMessage());
            }
        }
        if (req.getTimeEnd() != null && !req.getTimeEnd().isEmpty()) {
            try {
                helpRequest.setTimeEnd(LocalDateTime.parse(req.getTimeEnd(), DT_FMT));
            } catch (Exception e) {
                log.debug("Failed to parse timeEnd: {}", e.getMessage());
            }
        }
        helpRequest.setImages(req.getImages());
        // 发布后先挂起，等待 AI 异步审核
        helpRequest.setStatus(BizStatus.PENDING_REVIEW);
        helpRequest.setModerationStatus(ModerationStatus.PENDING);
        helpRequest.setIsProxy(req.getIsProxy() != null && req.getIsProxy());
        helpRequest.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        helpRequest = helpRequestRepository.save(helpRequest);

        // 异步 AI 内容审核，不阻塞发布响应
        final HelpRequest savedHelp = helpRequest;
        CompletableFuture.runAsync(() -> {
            try {
                moderationService.scheduleModeration(savedHelp);
            } catch (Exception e) {
                log.error("异步审核调度失败: helpId={}", savedHelp.getId(), e);
            }
        });

        return toDTO(helpRequest);
    }

    public PageDTO<HelpResponseDTO> getHomeList(Long userId, int page, int size) {
        User user = userRepository.findById(userId).orElse(null);
        Long tenantId = user != null ? user.getTenantId() : null;
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "isUrgent", "createdAt"));
        Page<HelpRequest> helpPage = tenantId != null
                ? helpRequestRepository.findByStatusAndTenantId(BizStatus.ONLINE, tenantId, pageRequest)
                : helpRequestRepository.findByStatus(BizStatus.ONLINE, pageRequest);

        List<HelpResponseDTO> dtos = helpPage.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        return PageDTO.<HelpResponseDTO>builder()
                .content(dtos)
                .totalElements(helpPage.getTotalElements())
                .totalPages(helpPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .build();
    }

    public HelpResponseDTO getDetail(Long helpId) {
        HelpRequest helpRequest = helpRequestRepository.findById(helpId)
                .orElseThrow(() -> new BizException("求助信息不存在"));
        return enrichWithUserStats(toDTO(helpRequest));
    }

    public PageDTO<HelpResponseDTO> search(Long userId, String keyword, int page, int size) {
        // 与 getHomeList 保持一致的租户隔离——不同小区的数据不得互相搜到
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        Long tenantId = user != null ? user.getTenantId() : null;
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "isUrgent", "createdAt"));
        Page<HelpRequest> helpPage = tenantId != null
                ? helpRequestRepository.searchByTenant(BizStatus.ONLINE, tenantId, keyword, keyword, pageRequest)
                : helpRequestRepository.findByStatusAndTitleContainingOrDescriptionContaining(
                        BizStatus.ONLINE, keyword, keyword, pageRequest);

        List<HelpResponseDTO> dtos = helpPage.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        return PageDTO.<HelpResponseDTO>builder()
                .content(dtos)
                .totalElements(helpPage.getTotalElements())
                .totalPages(helpPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .build();
    }

    public List<HelpResponseDTO> getMyPosts(Long userId) {
        List<HelpRequest> requests = helpRequestRepository.findByUserId(userId);
        return requests.stream().map(this::toDTO).collect(Collectors.toList());
    }

    public HelpResponseDTO delist(Long userId, Long helpId) {
        HelpRequest helpRequest = helpRequestRepository.findById(helpId)
                .orElseThrow(() -> new BizException("求助信息不存在"));

        if (!helpRequest.getUserId().equals(userId)) {
            throw new BizException("无权操作该求助");
        }

        helpRequest.setStatus(BizStatus.DRAFT);
        helpRequest.setDelistReason("用户自行下架");
        helpRequest = helpRequestRepository.save(helpRequest);

        // 将所有待处理的帮助申请设为已拒绝，并通知申请人
        List<HelpApplication> pendingApps = helpApplicationRepository
                .findByHelpIdAndStatus(helpId, BizStatus.PENDING);
        for (HelpApplication app : pendingApps) {
            app.setStatus(BizStatus.REJECTED);
            helpApplicationRepository.save(app);
            // 通知申请人该求助已被发布者下架
            createNotification(app.getHelperId(), NotificationType.HELP_REJECTED,
                    "帮助申请已失效",
                    "求助「" + helpRequest.getTitle() + "」已下架，您的帮助申请已自动失效",
                    app.getId());
        }

        return toDTO(helpRequest);
    }

    /**
     * 用户从草稿中删除求助，将其标记为已下架。
     */
    public HelpResponseDTO deleteItem(Long userId, Long helpId) {
        HelpRequest helpRequest = helpRequestRepository.findById(helpId)
                .orElseThrow(() -> new BizException("求助不存在"));
        if (!helpRequest.getUserId().equals(userId)) {
            throw new BizException("无权操作该求助");
        }
        helpRequest.setStatus(BizStatus.OFFLINE);
        helpRequest.setDelistReason("用户删除");
        helpRequest = helpRequestRepository.save(helpRequest);
        return toDTO(helpRequest);
    }

    public HelpResponseDTO apply(Long helperId, Long helpId, String note) {
        // 悲观写锁防并发：两个人同时申请同一求助时，后到达的事务需等待前者提交
        HelpRequest helpRequest = helpRequestRepository.findByIdWithLock(helpId)
                .orElseThrow(() -> new BizException("求助信息不存在"));

        if (!BizStatus.ONLINE.equals(helpRequest.getStatus())) {
            throw new BizException("该求助已被其他人抢先申请，请浏览其他求助");
        }

        if (helpRequest.getUserId().equals(helperId)) {
            throw new BizException("不能申请自己的求助");
        }

        // 防重复：同一用户对同一求助已有 pending/approved 申请时拒绝
        if (helpApplicationRepository.existsByHelpIdAndHelperIdAndStatusIn(
                helpId, helperId, List.of(BizStatus.PENDING, BizStatus.APPROVED))) {
            throw new BizException("您已申请过该求助，请勿重复提交");
        }

        HelpApplication application = new HelpApplication();
        application.setHelpId(helpId);
        application.setHelperId(helperId);
        application.setNote(note);
        application.setStatus(BizStatus.PENDING);
        application.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        application = helpApplicationRepository.save(application);

        // 标记求助为"已被申请"，首页列表不再展示（与闲置物品 reserved 模式一致）
        helpRequest.setStatus(BizStatus.PENDING);
        helpRequestRepository.save(helpRequest);

        createNotification(helpRequest.getUserId(), NotificationType.HELP_APPLICATION,
                "新的帮助申请", "有人想帮助您：" + helpRequest.getTitle(),
                application.getId());

        // 通知帮助者：申请已提交（服务通知展示"待回应"）
        // 先清理该用户对同一求助的旧"帮助申请已提交"通知，避免上一轮申请的通知仍显示为待回应
        notificationService.deleteByUserIdAndTypeAndRelatedId(helperId, NotificationType.HELP_APPLICATION_SUBMITTED, helpRequest.getId());
        createNotification(helperId, NotificationType.HELP_APPLICATION_SUBMITTED,
                "帮助申请已提交",
                "你已成功申请帮助「" + helpRequest.getTitle() + "」，等待对方确认",
                helpRequest.getId());

        return toDTO(helpRequest);
    }

    public HelpResponseDTO approveReject(Long ownerId, Long appId, ApproveRequest req) {
        HelpApplication application = helpApplicationRepository.findById(appId)
                .orElseThrow(() -> new BizException("帮助申请不存在"));

        HelpRequest helpRequest = helpRequestRepository.findById(application.getHelpId())
                .orElseThrow(() -> new BizException("求助信息不存在"));

        if (!helpRequest.getUserId().equals(ownerId)) {
            throw new BizException("无权操作该申请");
        }

        if (!BizStatus.PENDING.equals(application.getStatus())) {
            throw new BizException("该申请已被处理，无法重复操作");
        }

        application.setStatus(req.getApproved() ? BizStatus.APPROVED : BizStatus.REJECTED);
        helpApplicationRepository.save(application);

        // 同步 HelpRequest 状态
        if (req.getApproved()) {
            helpRequest.setStatus(BizStatus.ACTIVE);
            helpRequestRepository.save(helpRequest);
        } else {
            // 拒绝时：若该求助没有其他待审批的申请，恢复为 online（首页重新可见）
            List<HelpApplication> pendingForHelp = helpApplicationRepository
                    .findByHelpIdAndStatus(application.getHelpId(), BizStatus.PENDING);
            if (pendingForHelp.isEmpty()) {
                helpRequest.setStatus(BizStatus.ONLINE);
                helpRequestRepository.save(helpRequest);
            }
        }

        if (req.getApproved()) {
            createNotification(application.getHelperId(), NotificationType.HELP_APPROVED,
                    "帮助申请已通过",
                    "您对求助「" + helpRequest.getTitle() + "」的帮助申请已通过",
                    application.getId());
        } else {
            createNotification(application.getHelperId(), NotificationType.HELP_REJECTED,
                    "帮助申请被拒绝",
                    "您对求助「" + helpRequest.getTitle() + "」的帮助申请被拒绝"
                            + (req.getReason() != null ? "，原因：" + req.getReason() : ""),
                    application.getId());
        }

        return toDTO(helpRequest);
    }

    /**
     * 确认结束互助 —— 求助方(helpReq) / 帮助方(helpPro) 任意一方均可发起。
     * 二者是同一条 HelpApplication 的两个视角，状态共享，
     * 任意一方确认后双方都会从「进行中」进入「已完成」。
     */
    public HelpResponseDTO completeHelp(Long actorId, Long appId) {
        HelpApplication application = helpApplicationRepository.findById(appId)
                .orElseThrow(() -> new BizException("帮助申请不存在"));

        HelpRequest helpRequest = helpRequestRepository.findById(application.getHelpId())
                .orElseThrow(() -> new BizException("求助信息不存在"));

        boolean isRequester = helpRequest.getUserId().equals(actorId);
        boolean isHelper = application.getHelperId().equals(actorId);
        if (!isRequester && !isHelper) {
            throw new BizException("无权操作该申请");
        }

        if (!BizStatus.APPROVED.equals(application.getStatus())) {
            throw new BizException("只能完成进行中的帮助申请");
        }

        application.setStatus(BizStatus.COMPLETED);
        application.setCompletedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        helpApplicationRepository.save(application);

        helpRequest.setStatus(BizStatus.COMPLETED);
        helpRequestRepository.save(helpRequest);

        // 通知对方（发起人是求助方则通知帮助方，反之亦然）
        Long peerId = isRequester ? application.getHelperId() : helpRequest.getUserId();
        if (peerId != null) {
            createNotification(peerId, NotificationType.HELP_RESULT,
                    "帮助已完成", "「" + helpRequest.getTitle() + "」的互助已确认完成，请及时评价此次互助",
                    application.getId());
        }

        return toDTO(helpRequest);
    }

    /**
     * 更新求助信息（编辑保存或重新上架）。
     * 编辑后自动退回 pending_review 并重新排队 AI 审核，
     * 审核通过后自动上线。
     */
    public HelpResponseDTO update(Long userId, Long helpId, HelpRequestDTO req) {
        HelpRequest helpRequest = helpRequestRepository.findById(helpId)
                .orElseThrow(() -> new BizException("求助信息不存在"));

        if (!helpRequest.getUserId().equals(userId)) {
            throw new BizException("无权操作该求助");
        }

        // 保存原始状态，用于判断是否需要重新审核
        String originalStatus = helpRequest.getStatus();

        helpRequest.setTitle(req.getTitle() != null ? req.getTitle() : helpRequest.getTitle());
        helpRequest.setDescription(req.getDescription() != null ? req.getDescription() : helpRequest.getDescription());
        helpRequest.setCategory(req.getCategory() != null ? req.getCategory() : helpRequest.getCategory());
        helpRequest.setLocation(req.getLocation() != null ? req.getLocation() : helpRequest.getLocation());
        helpRequest.setIsUrgent(req.getIsUrgent() != null ? req.getIsUrgent() : helpRequest.getIsUrgent());

        if (req.getTimeStart() != null && !req.getTimeStart().isEmpty()) {
            try {
                helpRequest.setTimeStart(LocalDateTime.parse(req.getTimeStart(), DT_FMT));
            } catch (Exception e) { log.debug("Failed to parse timeStart: {}", e.getMessage()); }
        }
        if (req.getTimeEnd() != null && !req.getTimeEnd().isEmpty()) {
            try {
                helpRequest.setTimeEnd(LocalDateTime.parse(req.getTimeEnd(), DT_FMT));
            } catch (Exception e) { log.debug("Failed to parse timeEnd: {}", e.getMessage()); }
        }
        helpRequest.setImages(req.getImages());

        // 编辑后重新审核：原状态为 online/completed/offline 时，退回 pending_review
        boolean needsModeration = BizStatus.ONLINE.equals(originalStatus)
                || BizStatus.COMPLETED.equals(originalStatus)
                || BizStatus.OFFLINE.equals(originalStatus)
                || BizStatus.DRAFT.equals(originalStatus);
        if (needsModeration) {
            helpRequest.setStatus(BizStatus.PENDING_REVIEW);
            helpRequest.setModerationStatus(ModerationStatus.PENDING);
            // 从 completed/offline 重新发布时刷新时间
            if (BizStatus.COMPLETED.equals(originalStatus) || BizStatus.OFFLINE.equals(originalStatus)) {
                helpRequest.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
            }
        }

        helpRequest = helpRequestRepository.save(helpRequest);

        // 异步重新审核
        if (needsModeration) {
            final HelpRequest savedHelp = helpRequest;
            CompletableFuture.runAsync(() -> {
                try {
                    moderationService.scheduleModeration(savedHelp);
                } catch (Exception e) {
                    log.error("异步审核调度失败: helpId={}", savedHelp.getId(), e);
                }
            });
        }

        return toDTO(helpRequest);
    }

    public List<HelpResponseDTO> getMyApplications(Long userId) {
        List<HelpApplication> applications = helpApplicationRepository.findByHelperId(userId);
        return applications.stream().map(app -> {
            HelpRequest helpRequest = helpRequestRepository.findById(app.getHelpId())
                    .orElse(null);
            HelpResponseDTO dto = helpRequest != null ? toDTO(helpRequest) : HelpResponseDTO.builder().build();
            dto.setApplicationStatus(app.getStatus());
            dto.setApplicationId(app.getId());
            return dto;
        }).collect(Collectors.toList());
    }

    public List<HelpResponseDTO> getPendingApprovals(Long userId) {
        List<HelpRequest> myRequests = helpRequestRepository.findByUserId(userId);
        List<HelpResponseDTO> result = new ArrayList<>();
        for (HelpRequest hr : myRequests) {
            // 仅对在线中的帖子返回待审批申请（已下架/已完成的帖子不展示审批项）
            if (!BizStatus.ONLINE.equals(hr.getStatus())) {
                continue;
            }
            List<HelpApplication> pendingApps = helpApplicationRepository
                    .findByHelpIdAndStatus(hr.getId(), BizStatus.PENDING);
            for (HelpApplication app : pendingApps) {
                User helper = userRepository.findById(app.getHelperId()).orElse(null);
                HelpResponseDTO dto = toDTO(hr);
                dto.setApplicationId(app.getId());
                dto.setApplicationStatus(app.getStatus());
                dto.setHelperId(app.getHelperId());
                dto.setHelperName(helper != null ? helper.getName() : "未知用户");
                dto.setApplicationNote(app.getNote());
                result.add(dto);
            }
        }
        return result;
    }

    private HelpResponseDTO enrichWithUserStats(HelpResponseDTO dto) {
        Long userId = dto.getUserId();
        if (userId == null) return dto;

        Double avgScore = ratingRepository.getAverageScore(userId);
        dto.setRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);

        // 「以往记录」弹层五项统计 — 全站统一口径：已完成且被对方评价才计数
        UserActivityService.InteractionStats stats = userActivityService.interactionStats(userId);
        dto.setHelpCount((long) stats.helpReqCount());
        dto.setHelpedCount((long) stats.helpProCount());
        dto.setBorrowCount((long) stats.borrowCount());
        dto.setLendCount((long) stats.lendCount());
        dto.setReturnRate(stats.returnedCount() > 0
                ? Math.round(stats.onTimeCount() * 100.0 / stats.returnedCount()) + "%"
                : "100%");
        return dto;
    }

    private HelpResponseDTO toDTO(HelpRequest hr) {
        User user = userRepository.findById(hr.getUserId()).orElse(null);
        String userRoom = UserFormatter.formatRoomWithType(user);
        return HelpResponseDTO.builder()
                .id(hr.getId())
                .userId(hr.getUserId())
                .userName(user != null ? user.getName() : "未知用户")
                .userRoom(userRoom)
                .title(hr.getTitle())
                .description(hr.getDescription())
                .category(hr.getCategory())
                .isUrgent(hr.getIsUrgent())
                .timeStart(hr.getTimeStart())
                .timeEnd(hr.getTimeEnd())
                .images(hr.getImages())
                .status(hr.getStatus())
                .delistReason(hr.getDelistReason())
                .location(hr.getLocation())
                .isProxy(hr.getIsProxy())
                .createdAt(hr.getCreatedAt())
                .build();
    }

    private void createNotification(Long userId, String type, String title, String content, Long relatedId) {
        notificationService.create(userId, type, title, content, relatedId);
    }
}
