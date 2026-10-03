package com.platform.service;

import com.platform.common.ActivityRole;
import com.platform.common.AppTimeZone;
import com.platform.common.BizException;
import com.platform.common.AuthStatus;
import com.platform.common.BorrowStatus;
import com.platform.common.HelpApplicationStatus;
import com.platform.common.PostStatus;
import com.platform.common.ContentType;
import com.platform.common.PostType;
import com.platform.common.UserFormatter;
import com.platform.model.dto.ApprovalCountDTO;
import com.platform.model.dto.MyPostItemDTO;
import com.platform.model.dto.UserProfileDTO;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.Rating;
import com.platform.model.entity.User;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class UserActivityService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);
    private static final java.time.format.DateTimeFormatter DT_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final IdleItemRepository idleItemRepository;
    private final HelpRequestRepository helpRequestRepository;
    private final HelpApplicationRepository helpApplicationRepository;
    private final BorrowRequestRepository borrowRequestRepository;
    private final RatingRepository ratingRepository;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;

    public UserActivityService(IdleItemRepository idleItemRepository,
                               HelpRequestRepository helpRequestRepository,
                               HelpApplicationRepository helpApplicationRepository,
                               BorrowRequestRepository borrowRequestRepository,
                               RatingRepository ratingRepository,
                               UserRepository userRepository,
                               RoomRepository roomRepository) {
        this.idleItemRepository = idleItemRepository;
        this.helpRequestRepository = helpRequestRepository;
        this.helpApplicationRepository = helpApplicationRepository;
        this.borrowRequestRepository = borrowRequestRepository;
        this.ratingRepository = ratingRepository;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
    }

    // ============================================================
    // 个人资料 — 我的 tab
    // ============================================================

    /**
     * 获取当前用户的个人资料及统计数据，用于"我的" tab。
     */
    @Transactional(readOnly = true)
    public UserProfileDTO getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        // 评分
        Double avgScore = ratingRepository.getAverageScore(userId);
        long ratingCount = ratingRepository.countByToUserId(userId);

        // 统计数据 — 口径：交易已完成且被对方评价才计数（详见 interactionStats）。
        // WANTED 帖里发布者才是借入方，直接用 borrowerId 会把方向算反，统计内部已按真实角色分流。
        InteractionStats stats = interactionStats(userId);

        return UserProfileDTO.builder()
                .id(user.getId())
                .name(user.getName())
                .userType(user.getUserType())
                .userTypeText(UserFormatter.getUserTypeLabel(user.getUserType()))
                .roomInfo(UserFormatter.formatRoomWithType(user))
                .isAuth(AuthStatus.APPROVED.equals(user.getAuthStatus()))
                .score(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : 5.0)
                .ratingCount((int) ratingCount)
                .lendCount(stats.lendCount())
                .borrowCount(stats.borrowCount())
                // 尚无已归还的互借记录时默认 100%（新用户无扣分依据）
                .borrowReturnRate(stats.returnedCount() > 0
                        ? Math.round((double) stats.onTimeCount() / stats.returnedCount() * 1000.0) / 10.0 : 100.0)
                .helpReqCount(stats.helpReqCount())
                .helpProCount(stats.helpProCount())
                .build();
    }

    // ============================================================
    // 发布 tab — 我发布的帖子（闲置 + 求助）
    // ============================================================

    /**
     * 获取我发布的帖子（闲置 + 求助合并），用于"发布" tab。
     * @param statusFilter "online" | "offline" | "completed" — 按状态过滤
     */
    @Transactional(readOnly = true)
    public List<MyPostItemDTO> getMyPosts(Long userId, String statusFilter) {
        List<MyPostItemDTO> result = new ArrayList<>();

        // 1. 闲置物品（所有 postType：LEND + WANTED）
        List<IdleItem> idleItems = idleItemRepository.findByUserId(userId);
        for (IdleItem item : idleItems) {
            if (statusFilter != null && !statusFilter.isEmpty() && !statusFilter.equals(item.getStatus())) {
                continue;
            }
            result.add(toMyPostItemDTO(item));
        }

        // 2. 求助信息
        List<HelpRequest> helpRequests = helpRequestRepository.findByUserId(userId);
        for (HelpRequest hr : helpRequests) {
            if (statusFilter != null && !statusFilter.isEmpty() && !statusFilter.equals(hr.getStatus())) {
                continue;
            }
            result.add(toMyPostItemDTO(hr));
        }

        // 3. 按 createdAt 降序排序
        result.sort(Comparator.comparing(MyPostItemDTO::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return result;
    }

    // ============================================================
    // 审批 tab — 待审批事项
    // ============================================================

    /**
     * 获取待审批列表（等待我作为物品发布者/求助人审批的申请）。
     *
     * @param type 审批类别，取值见 {@link ActivityRole} 的 type 族：
     *             {@code BORROW} — 别人申请借入我发布的 LEND 物品（我是出借方，审批是否借出）；
     *             {@code LEND} — 别人愿意借出给我发布的 WANTED 需求（我是借入方，确认是否借入）；
     *             {@code HELP} — 别人申请帮助我发布的求助
     * @param userId 当前住户用户 ID
     * @return 待审批事项列表，按创建时间倒序
     */
    @Transactional(readOnly = true)
    public List<MyPostItemDTO> getApprovals(Long userId, String type) {
        List<MyPostItemDTO> result;
        if (ActivityRole.BORROW.equals(type) || ActivityRole.LEND.equals(type)) {
            result = collectBorrowLendApprovals(userId, type);
        } else if (ActivityRole.HELP.equals(type)) {
            result = collectHelpApprovals(userId);
        } else {
            result = new ArrayList<>();
        }

        result.sort(Comparator.comparing(MyPostItemDTO::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /**
     * 收集借用审批列表（借入确认 / 借出审批），按 WANTED/LEND 帖类型分流。
     */
    private List<MyPostItemDTO> collectBorrowLendApprovals(Long userId, String type) {
        List<MyPostItemDTO> result = new ArrayList<>();
        boolean wantWanted = ActivityRole.BORROW.equals(type);
        List<BorrowRequest> pendingBorrows = borrowRequestRepository
                .findByOwnerIdAndStatus(userId, BorrowStatus.PENDING);
        for (BorrowRequest br : pendingBorrows) {
            IdleItem item = resolveIdleItem(br);
            boolean wanted = item != null && PostType.WANTED.equals(item.getPostType());
            if (wanted != wantWanted) continue;
            MyPostItemDTO dto = borrowRequestToDTO(br);
            dto.setPersonId(br.getBorrowerId());
            User applicant = userRepository.findById(br.getBorrowerId()).orElse(null);
            if (applicant != null) {
                dto.setPersonName(UserFormatter.formatPersonName(applicant));
                dto.setPersonRoom(UserFormatter.formatRoomWithType(applicant));
                dto.setPersonType(UserFormatter.getUserTypeLabel(applicant.getUserType()));
            }
            enrichUserStats(dto, br.getBorrowerId());
            dto.setType(ContentType.IDLE);
            dto.setSubType(type);
            result.add(dto);
        }
        return result;
    }

    /**
     * 收集帮助审批列表（我发布的求助下待处理的帮助申请）。
     */
    private List<MyPostItemDTO> collectHelpApprovals(Long userId) {
        List<MyPostItemDTO> result = new ArrayList<>();
        List<HelpRequest> myHelpRequests = helpRequestRepository.findByUserId(userId);
        for (HelpRequest hr : myHelpRequests) {
            List<HelpApplication> pendingApps = helpApplicationRepository
                    .findByHelpIdAndStatus(hr.getId(), HelpApplicationStatus.PENDING);
            for (HelpApplication app : pendingApps) {
                MyPostItemDTO dto = helpRequestToDTO(hr);
                dto.setId(app.getId());
                dto.setType(ContentType.HELP);
                dto.setSubType(ActivityRole.HELP_REQ);
                dto.setPostType(PostType.HELP);
                dto.setNote(app.getNote());
                dto.setPersonId(app.getHelperId());
                User helper = userRepository.findById(app.getHelperId()).orElse(null);
                if (helper != null) {
                    dto.setPersonName(UserFormatter.formatPersonName(helper));
                    dto.setPersonRoom(UserFormatter.formatRoomWithType(helper));
                    dto.setPersonType(UserFormatter.getUserTypeLabel(helper.getUserType()));
                }
                enrichUserStats(dto, app.getHelperId());
                result.add(dto);
            }
        }
        return result;
    }

    /**
     * 待审批数量统计 — 供 tabBar「管理」红点与审批 tab 角标使用。
     * 只做计数不做 DTO 组装，避免 getApprovals 的用户统计等重查询开销。
     * 类型语义与 getApprovals 一致：borrow=确认借入（WANTED 帖）、lend=审批借出（LEND 帖）。
     */
    @Transactional(readOnly = true)
    public ApprovalCountDTO getApprovalCounts(Long userId) {
        int borrow = 0;
        int lend = 0;
        for (BorrowRequest br : borrowRequestRepository.findByOwnerIdAndStatus(userId, BorrowStatus.PENDING)) {
            IdleItem item = resolveIdleItem(br);
            boolean wanted = item != null && PostType.WANTED.equals(item.getPostType());
            if (wanted) {
                borrow++;
            } else {
                lend++;
            }
        }

        int help = 0;
        for (HelpRequest hr : helpRequestRepository.findByUserId(userId)) {
            help += helpApplicationRepository.findByHelpIdAndStatus(hr.getId(), HelpApplicationStatus.PENDING).size();
        }

        return ApprovalCountDTO.builder()
                .borrow(borrow)
                .lend(lend)
                .help(help)
                .total(borrow + lend + help)
                .build();
    }

    // ============================================================
    // 进行中 tab — 进行中的交易
    // ============================================================

    /**
     * 获取进行中的交易/互助。
     * @param role 角色视角，取值见 {@link ActivityRole} 的 role 族：
     *             {@code BORROW}（借入方）/ {@code LEND}（借出方）/ {@code HELP_REQ}（求助方）/ {@code HELP_PRO}（帮忙方）
     */
    @Transactional(readOnly = true)
    public List<MyPostItemDTO> getInProgress(Long userId, String role) {
        List<MyPostItemDTO> result = switch (role) {
            case ActivityRole.BORROW, ActivityRole.LEND -> collectBorrowInProgress(userId, role);
            case ActivityRole.HELP_REQ -> collectHelpReqInProgress(userId);
            case ActivityRole.HELP_PRO -> collectHelpProInProgress(userId);
            default -> throw new BizException("无效的角色类型: " + role);
        };

        result.sort(Comparator.comparing(MyPostItemDTO::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /**
     * 收集借用进行中的记录（含 lend/borrow 双视角）。
     */
    private List<MyPostItemDTO> collectBorrowInProgress(Long userId, String role) {
        List<MyPostItemDTO> result = new ArrayList<>();
        List<BorrowRequest> pool = new ArrayList<>();
        pool.addAll(borrowRequestRepository.findByBorrowerIdAndStatus(userId, BorrowStatus.APPROVED));
        pool.addAll(borrowRequestRepository.findByOwnerIdAndStatus(userId, BorrowStatus.APPROVED));
        Set<Long> seen = new HashSet<>();
        for (BorrowRequest br : pool) {
            if (!seen.add(br.getId())) continue;
            if (!role.equals(resolveBorrowRole(br, userId))) continue;
            MyPostItemDTO dto = borrowRequestToDTO(br);
            dto.setType(ContentType.IDLE);
            dto.setSubType(role);
            dto.setRoleLabel(role.equals(ActivityRole.BORROW) ? "借出住户" : "借走住户");
            populateBorrowPeer(dto, br, userId);
            calculateRemaining(dto, br);
            result.add(dto);
        }
        return result;
    }

    /**
     * 收集求助进行中的记录（我发起的求助有人接单）。
     */
    private List<MyPostItemDTO> collectHelpReqInProgress(Long userId) {
        List<MyPostItemDTO> result = new ArrayList<>();
        List<HelpRequest> myHelpRequests = helpRequestRepository.findByUserId(userId);
        for (HelpRequest hr : myHelpRequests) {
            List<HelpApplication> approvedApps = helpApplicationRepository
                    .findByHelpIdAndStatus(hr.getId(), HelpApplicationStatus.APPROVED);
            for (HelpApplication app : approvedApps) {
                MyPostItemDTO dto = helpRequestToDTO(hr);
                dto.setId(app.getId());
                dto.setType(ContentType.HELP);
                dto.setSubType(ActivityRole.HELP_REQ);
                dto.setPostType(PostType.HELP);
                dto.setRoleLabel("帮助住户");
                dto.setDisplayStatus("进行中");
                dto.setNote(app.getNote());
                dto.setPersonId(app.getHelperId());
                User helper = userRepository.findById(app.getHelperId()).orElse(null);
                if (helper != null) {
                    dto.setPersonName(UserFormatter.formatPersonName(helper));
                    dto.setPersonRoom(UserFormatter.formatRoomWithType(helper));
                    dto.setPersonType(UserFormatter.getUserTypeLabel(helper.getUserType()));
                }
                Double avgScore = ratingRepository.getAverageScore(app.getHelperId());
                dto.setPersonRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);
                calculateHelpRemaining(dto, hr);
                result.add(dto);
            }
        }
        return result;
    }

    /**
     * 收集提供帮助进行中的记录（我帮助别人）。
     */
    private List<MyPostItemDTO> collectHelpProInProgress(Long userId) {
        List<MyPostItemDTO> result = new ArrayList<>();
        List<HelpApplication> myApps = helpApplicationRepository.findByHelperId(userId);
        for (HelpApplication app : myApps) {
            if (!HelpApplicationStatus.APPROVED.equals(app.getStatus())) continue;
            HelpRequest hr = app.getHelpRequest();
            if (hr == null) {
                hr = helpRequestRepository.findById(app.getHelpId()).orElse(null);
            }
            if (hr == null) continue;
            MyPostItemDTO dto = helpRequestToDTO(hr);
            dto.setId(app.getId());
            dto.setType(ContentType.HELP);
            dto.setSubType(ActivityRole.HELP_PRO);
            dto.setPostType(PostType.HELP);
            dto.setRoleLabel("求助住户");
            dto.setDisplayStatus("进行中");
            dto.setNote(app.getNote());
            dto.setPersonId(hr.getUserId());
            User requester = userRepository.findById(hr.getUserId()).orElse(null);
            if (requester != null) {
                dto.setPersonName(UserFormatter.formatPersonName(requester));
                dto.setPersonRoom(UserFormatter.formatRoomWithType(requester));
                dto.setPersonType(UserFormatter.getUserTypeLabel(requester.getUserType()));
            }
            Double avgScore = ratingRepository.getAverageScore(hr.getUserId());
            dto.setPersonRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);
            calculateHelpRemaining(dto, hr);
            result.add(dto);
        }
        return result;
    }

    // ============================================================
    // 已完成 tab — 已完成的交易
    // ============================================================

    /**
     * 获取已完成的交易。
     * @param role 角色视角，取值见 {@link ActivityRole} 的 role 族：
     *             {@code BORROW}（借入方）/ {@code LEND}（借出方）/ {@code HELP_REQ}（求助方）/ {@code HELP_PRO}（帮忙方）
     */
    @Transactional(readOnly = true)
    public List<MyPostItemDTO> getCompleted(Long userId, String role) {
        List<MyPostItemDTO> result = switch (role) {
            case ActivityRole.BORROW, ActivityRole.LEND -> collectBorrowCompleted(userId, role);
            case ActivityRole.HELP_REQ -> collectHelpReqCompleted(userId);
            case ActivityRole.HELP_PRO -> collectHelpProCompleted(userId);
            default -> throw new BizException("无效的角色类型: " + role);
        };

        result.sort(Comparator.comparing(MyPostItemDTO::getCompletedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /**
     * 收集已归还的借用记录（含 lend/borrow 双视角）。
     */
    private List<MyPostItemDTO> collectBorrowCompleted(Long userId, String role) {
        List<MyPostItemDTO> result = new ArrayList<>();
        List<BorrowRequest> pool = new ArrayList<>();
        pool.addAll(borrowRequestRepository.findByBorrowerIdAndStatus(userId, BorrowStatus.RETURNED));
        pool.addAll(borrowRequestRepository.findByOwnerIdAndStatus(userId, BorrowStatus.RETURNED));
        Set<Long> seen = new HashSet<>();
        for (BorrowRequest br : pool) {
            if (!seen.add(br.getId())) continue;
            if (!role.equals(resolveBorrowRole(br, userId))) continue;
            // 跳过已下架的物品
            IdleItem idleItem = resolveIdleItem(br);
            if (idleItem != null && PostStatus.OFFLINE.equals(idleItem.getStatus())) continue;
            MyPostItemDTO dto = borrowRequestToDTO(br);
            dto.setType(ContentType.IDLE);
            dto.setSubType(role);
            dto.setRoleLabel(role.equals(ActivityRole.BORROW) ? "借出住户" : "借走住户");
            dto.setCompletedAt(br.getReturnedAt());
            populateBorrowPeer(dto, br, userId);
            loadBorrowRatings(dto, br, userId);
            // 借用归还明细（"我的"页记录弹框）
            dto.setDamageType(br.getDamageType());
            dto.setIsOnTime(br.getIsOnTime());
            result.add(dto);
        }
        return result;
    }

    /**
     * 收集我发布的已完成求助记录。
     */
    private List<MyPostItemDTO> collectHelpReqCompleted(Long userId) {
        List<MyPostItemDTO> result = new ArrayList<>();
        List<HelpRequest> myHelpRequests = helpRequestRepository.findByUserId(userId);
        for (HelpRequest hr : myHelpRequests) {
            // 跳过已被管理员下架的求助
            if (PostStatus.OFFLINE.equals(hr.getStatus())) continue;
            List<HelpApplication> completedApps = helpApplicationRepository
                    .findByHelpIdAndStatus(hr.getId(), HelpApplicationStatus.COMPLETED);
            for (HelpApplication app : completedApps) {
                MyPostItemDTO dto = helpRequestToDTO(hr);
                dto.setId(app.getId());
                dto.setType(ContentType.HELP);
                dto.setSubType(ActivityRole.HELP_REQ);
                dto.setPostType(PostType.HELP);
                dto.setRoleLabel("帮助住户");
                dto.setCompletedAt(app.getCompletedAt());
                dto.setNote(app.getNote());
                dto.setPersonId(app.getHelperId());
                User helper = userRepository.findById(app.getHelperId()).orElse(null);
                if (helper != null) {
                    dto.setPersonName(UserFormatter.formatPersonName(helper));
                    dto.setPersonRoom(UserFormatter.formatRoomWithType(helper));
                    dto.setPersonType(UserFormatter.getUserTypeLabel(helper.getUserType()));
                }
                Double avgScore = ratingRepository.getAverageScore(app.getHelperId());
                dto.setPersonRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);
                loadHelpRatings(dto, app, userId);
                result.add(dto);
            }
        }
        return result;
    }

    /**
     * 收集我提供的已完成帮助记录。
     */
    private List<MyPostItemDTO> collectHelpProCompleted(Long userId) {
        List<MyPostItemDTO> result = new ArrayList<>();
        List<HelpApplication> myApps = helpApplicationRepository.findByHelperId(userId);
        for (HelpApplication app : myApps) {
            if (!HelpApplicationStatus.COMPLETED.equals(app.getStatus())) continue;
            HelpRequest hr = app.getHelpRequest();
            if (hr == null) {
                hr = helpRequestRepository.findById(app.getHelpId()).orElse(null);
            }
            if (hr == null) continue;
            // 跳过已被管理员下架的求助
            if (PostStatus.OFFLINE.equals(hr.getStatus())) continue;
            MyPostItemDTO dto = helpRequestToDTO(hr);
            dto.setId(app.getId());
            dto.setType(ContentType.HELP);
            dto.setSubType(ActivityRole.HELP_PRO);
            dto.setPostType(PostType.HELP);
            dto.setRoleLabel("求助住户");
            dto.setCompletedAt(app.getCompletedAt());
            dto.setNote(app.getNote());
            dto.setPersonId(hr.getUserId());
            User requester = userRepository.findById(hr.getUserId()).orElse(null);
            if (requester != null) {
                dto.setPersonName(UserFormatter.formatPersonName(requester));
                dto.setPersonRoom(UserFormatter.formatRoomWithType(requester));
                dto.setPersonType(UserFormatter.getUserTypeLabel(requester.getUserType()));
            }
            Double avgScore = ratingRepository.getAverageScore(hr.getUserId());
            dto.setPersonRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);
            loadHelpRatings(dto, app, userId);
            result.add(dto);
        }
        return result;
    }

    // ============================================================
    // 私有转换辅助方法
    // ============================================================

    /**
     * 将 IdleItem 转换为 MyPostItemDTO（用于"发布" tab）。
     */
    private MyPostItemDTO toMyPostItemDTO(IdleItem item) {
        String displayStatus = mapIdleDisplayStatus(item.getStatus());

        User user = item.getUser();
        String personName = UserFormatter.formatPersonName(user);
        String personRoom = UserFormatter.formatRoomWithType(user);
        String personType = user != null ? UserFormatter.getUserTypeLabel(user.getUserType()) : null;

        return MyPostItemDTO.builder()
                .id(item.getId())
                .type(ContentType.IDLE)
                .postType(item.getPostType())
                .title(item.getTitle())
                .category(item.getCategory())
                .description(item.getDescription())
                .price(item.getPrice())
                .condition(item.getCondition())
                .maxDuration(item.getMaxDuration())
                .durationUnit(item.getDurationUnit())
                .pickupMethod(item.getPickupMethod())
                .isProxy(item.getIsProxy())
                .status(item.getStatus())
                .displayStatus(displayStatus)
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .personName(personName)
                .personRoom(personRoom)
                .personType(personType)
                .images(item.getImages())
                .build();
    }

    /**
     * 将 HelpRequest 转换为 MyPostItemDTO（用于"发布" tab）。
     */
    private MyPostItemDTO toMyPostItemDTO(HelpRequest hr) {
        String displayStatus = mapHelpDisplayStatus(hr.getStatus());

        User user = hr.getUser();
        String personName = UserFormatter.formatPersonName(user);
        String personRoom = UserFormatter.formatRoomWithType(user);
        String personType = user != null ? UserFormatter.getUserTypeLabel(user.getUserType()) : null;

        // 格式化时间范围供前端展示（与 helpRequestToDTO 保持一致）
        java.time.format.DateTimeFormatter dtf = DT_FMT;
        String timeStartStr = hr.getTimeStart() != null ? hr.getTimeStart().format(dtf) : null;
        String timeEndStr = hr.getTimeEnd() != null ? hr.getTimeEnd().format(dtf) : null;

        return MyPostItemDTO.builder()
                .id(hr.getId())
                .type(ContentType.HELP)
                .postType(PostType.HELP)
                .title(hr.getTitle())
                .category(hr.getCategory())
                .description(hr.getDescription())
                .isUrgent(hr.getIsUrgent())
                .isProxy(hr.getIsProxy())
                .status(hr.getStatus())
                .displayStatus(displayStatus)
                .createdAt(hr.getCreatedAt())
                .updatedAt(hr.getUpdatedAt())
                .timeStart(timeStartStr)
                .timeEnd(timeEndStr)
                .personName(personName)
                .personRoom(personRoom)
                .personType(personType)
                .images(hr.getImages())
                .build();
    }

    /**
     * 由 HelpRequest 构建基础 DTO（不含 type/subType，由调用方设置）。
     */
    private MyPostItemDTO helpRequestToDTO(HelpRequest hr) {
        // 格式化时间范围供前端展示
        java.time.format.DateTimeFormatter dtf = DT_FMT;
        String timeStartStr = hr.getTimeStart() != null ? hr.getTimeStart().format(dtf) : null;
        String timeEndStr = hr.getTimeEnd() != null ? hr.getTimeEnd().format(dtf) : null;

        return MyPostItemDTO.builder()
                .title(hr.getTitle())
                .category(hr.getCategory())
                .description(hr.getDescription())
                .isUrgent(hr.getIsUrgent())
                .isProxy(hr.getIsProxy())
                .status(hr.getStatus())
                .displayStatus(mapHelpDisplayStatus(hr.getStatus()))
                .createdAt(hr.getCreatedAt())
                .timeStart(timeStartStr)
                .timeEnd(timeEndStr)
                .build();
    }

    /**
     * 由 BorrowRequest 构建基础 DTO（不含 type/subType，由调用方设置）。
     */
    private MyPostItemDTO borrowRequestToDTO(BorrowRequest br) {
        IdleItem idleItem = br.getIdleItem();
        if (idleItem == null) {
            idleItem = idleItemRepository.findById(br.getIdleId()).orElse(null);
        }

        String title = idleItem != null ? idleItem.getTitle() : "未知物品";
        String category = idleItem != null ? idleItem.getCategory() : null;
        String description = idleItem != null ? idleItem.getDescription() : null;
        // postType 取自原始闲置帖：LEND → 借入说明, WANTED → 借出说明
        String postType = idleItem != null ? idleItem.getPostType() : PostType.LEND;

        return MyPostItemDTO.builder()
                .id(br.getId())
                .title(title)
                .category(category)
                .description(description)
                .postType(postType)
                .price(idleItem != null ? idleItem.getPrice() : null)
                .condition(idleItem != null ? idleItem.getCondition() : null)
                .maxDuration(br.getDurationDays())
                .durationUnit(br.getDurationType())
                .note(br.getNote())
                .createdAt(br.getCreatedAt())
                .build();
    }

    // ============================================================
    // 显示状态映射
    // ============================================================

    private String mapIdleDisplayStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case PostStatus.ONLINE    -> "在线";
            case PostStatus.OFFLINE   -> "已下架";
            case PostStatus.DRAFT     -> "已下架";
            case PostStatus.PENDING   -> "待审批";
            case PostStatus.ACTIVE    -> "进行中";
            case PostStatus.COMPLETED -> "已完成";
            default                  -> status;
        };
    }

    private String mapHelpDisplayStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case PostStatus.ONLINE    -> "在线";
            case PostStatus.OFFLINE   -> "已下架";
            case PostStatus.DRAFT     -> "已下架";
            case PostStatus.PENDING   -> "待审批";
            case PostStatus.ACTIVE    -> "进行中";
            case PostStatus.COMPLETED -> "已完成";
            default                  -> status;
        };
    }

    // ============================================================
    // 剩余天数计算
    // ============================================================

    private void calculateRemaining(MyPostItemDTO dto, BorrowRequest br) {
        if (br.getDurationDays() == null) return;

        LocalDateTime expectedReturn;
        if ("hour".equals(br.getDurationType())) {
            // 按小时计算：从审批通过时间（updatedAt）开始，兜底用 createdAt
            LocalDateTime startDateTime = br.getUpdatedAt() != null ? br.getUpdatedAt() : br.getCreatedAt();
            expectedReturn = startDateTime.plusHours(br.getDurationDays());
        } else {
            // 按天计算：从 startDate（审批当天）开始，未设置时回退到 createdAt
            LocalDate start = br.getStartDate() != null ? br.getStartDate() : br.getCreatedAt().toLocalDate();
            expectedReturn = start.plusDays(br.getDurationDays()).atStartOfDay();
        }
        long remainingDays = ChronoUnit.DAYS.between(LocalDate.now(AppTimeZone.APP_ZONE), expectedReturn.toLocalDate());
        long remainingHours = ChronoUnit.HOURS.between(LocalDateTime.now(AppTimeZone.APP_ZONE), expectedReturn);

        dto.setExpectedReturnDays(br.getDurationDays());
        dto.setRemainingDays((int) remainingDays);
        dto.setRemainingHours((int) remainingHours);
        dto.setIsOverdue(remainingHours < 0);

        if (remainingHours < 0) {
            long absDays = Math.abs(remainingDays);
            long absHours = Math.abs(remainingHours);
            if (absDays >= 1) {
                dto.setMetaText("已逾期 " + absDays + " 天");
            } else if (absHours > 0) {
                dto.setMetaText("已逾期 " + absHours + " 小时");
            } else {
                dto.setMetaText("已逾期");
            }
        } else if (remainingHours == 0) {
            dto.setMetaText("今日应归还");
        } else if (remainingHours < 24) {
            dto.setMetaText("剩余 " + remainingHours + " 小时归还");
        } else {
            dto.setMetaText("剩余 " + remainingDays + " 天归还");
        }
    }

    private void calculateHelpRemaining(MyPostItemDTO dto, HelpRequest hr) {
        if (hr.getTimeEnd() == null) return;

        long remaining = ChronoUnit.DAYS.between(LocalDate.now(AppTimeZone.APP_ZONE), hr.getTimeEnd().toLocalDate());
        dto.setRemainingDays((int) remaining);
        dto.setIsOverdue(remaining < 0);

        if (remaining > 0) {
            dto.setMetaText("剩余 " + remaining + " 天");
        } else if (remaining == 0) {
            dto.setMetaText("今日截止");
        } else {
            long absDays = Math.abs(remaining);
            if (absDays >= 1) {
                dto.setMetaText("已逾期 " + absDays + " 天");
            } else {
                dto.setMetaText("已逾期");
            }
        }
    }

    // ============================================================
    // 借用角色判定 — 处理 WANTED(需求借入) 的角色反转
    //
    // 一条 BorrowRequest 涉及两方：物品 owner(idleItem.userId) 与 borrowerId。
    //  - LEND 帖：owner 是出借方，borrowerId 是借入方（与模型一致）。
    //  - WANTED 帖：发布者(owner) 其实是"借入方"，响应者(borrowerId) 才是"出借方"，
    //    真实角色相对模型反转。
    // 因此判断"当前用户在这笔交易里到底是借入还是借出"必须结合 postType。
    // ============================================================

    private IdleItem resolveIdleItem(BorrowRequest br) {
        IdleItem item = br.getIdleItem();
        if (item == null) {
            item = idleItemRepository.findById(br.getIdleId()).orElse(null);
        }
        return item;
    }

    /**
     * 计算当前用户在这笔 BorrowRequest 中的真实角色。
     * @return {@link ActivityRole#BORROW}（我是借入方）或 {@link ActivityRole#LEND}（我是出借方）
     */
    private String resolveBorrowRole(BorrowRequest br, Long me) {
        IdleItem item = resolveIdleItem(br);
        boolean wanted = item != null && PostType.WANTED.equals(item.getPostType());
        boolean iAmOwner = item != null && me.equals(item.getUserId());
        // LEND+owner→lend, LEND+borrower→borrow, WANTED+owner→borrow, WANTED+borrower→lend
        if (iAmOwner) {
            return wanted ? ActivityRole.BORROW : ActivityRole.LEND;
        }
        return wanted ? ActivityRole.LEND : ActivityRole.BORROW;
    }

    /**
     * 把 DTO 的 person* 字段填成"对方"（这笔交易里非当前用户的另一方）。
     */
    private void populateBorrowPeer(MyPostItemDTO dto, BorrowRequest br, Long me) {
        IdleItem item = resolveIdleItem(br);
        Long ownerId = item != null ? item.getUserId() : null;
        Long peerId = me.equals(br.getBorrowerId()) ? ownerId : br.getBorrowerId();
        if (peerId == null) return;
        dto.setPersonId(peerId);
        User peer = userRepository.findById(peerId).orElse(null);
        if (peer != null) {
            dto.setPersonName(UserFormatter.formatPersonName(peer));
            dto.setPersonRoom(UserFormatter.formatRoomWithType(peer));
            dto.setPersonType(UserFormatter.getUserTypeLabel(peer.getUserType()));
        }
        Double avgScore = ratingRepository.getAverageScore(peerId);
        dto.setPersonRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);
    }

    /** 互助统计结果：四项次数 + 按时归还率的分子分母 */
    public record InteractionStats(int borrowCount, int lendCount, int helpReqCount,
                                   int helpProCount, int returnedCount, int onTimeCount) {}

    /**
     * 互助次数统计（借入 / 借出 / 求助 / 帮助他人）— 全站统一口径，
     * 供「我的」页、审批弹层、闲置/求助详情页「以往记录」弹层共用。
     *
     * <p>计数口径（2026-07-18 约定）：交易达到终态（借用=returned，帮助=completed）
     * <b>且已被对方评价</b>（ratings.to_user_id = 本人）才计入次数——发布、待审批、
     * 进行中的记录一律不计，防止"发布即涨数据"。
     * 按时归还率与借入/借出一致，仅统计已评价的归还记录。
     */
    @Transactional(readOnly = true)
    public InteractionStats interactionStats(Long userId) {
        // 构建"已被对方评价"的交易 ID 集合
        Set<Long> ratedBorrowIds = new HashSet<>();
        Set<Long> ratedHelpAppIds = new HashSet<>();
        for (Rating r : ratingRepository.findByToUserId(userId)) {
            if (r.getBorrowId() != null) ratedBorrowIds.add(r.getBorrowId());
            if (r.getHelpApplicationId() != null) ratedHelpAppIds.add(r.getHelpApplicationId());
        }

        // 借用统计
        BorrowLendStats blStats = countBorrowLendStats(userId, ratedBorrowIds);

        // 求助统计
        int helpReq = 0;
        for (HelpRequest hr : helpRequestRepository.findByUserId(userId)) {
            for (HelpApplication app : helpApplicationRepository.findByHelpIdAndStatus(hr.getId(), HelpApplicationStatus.COMPLETED)) {
                if (ratedHelpAppIds.contains(app.getId())) helpReq++;
            }
        }

        // 帮助他人统计
        int helpPro = 0;
        for (HelpApplication app : helpApplicationRepository.findByHelperId(userId)) {
            if (HelpApplicationStatus.COMPLETED.equals(app.getStatus()) && ratedHelpAppIds.contains(app.getId())) {
                helpPro++;
            }
        }

        return new InteractionStats(blStats.borrow, blStats.lend, helpReq, helpPro,
                blStats.returned, blStats.onTime);
    }

    /**
     * 借用统计中间结果。
     */
    private record BorrowLendStats(int borrow, int lend, int returned, int onTime) {}

    /**
     * 统计用户的借入/借出次数及归还率数据。
     * 借入/借出/归还/按时归还均以已被对方评价为口径，保证四项数据一致可比。
     */
    private BorrowLendStats countBorrowLendStats(Long userId, Set<Long> ratedBorrowIds) {
        List<BorrowRequest> pool = new ArrayList<>();
        pool.addAll(borrowRequestRepository.findByBorrowerId(userId));
        pool.addAll(borrowRequestRepository.findByOwnerIdAndStatus(userId, BorrowStatus.APPROVED));
        pool.addAll(borrowRequestRepository.findByOwnerIdAndStatus(userId, BorrowStatus.RETURNED));

        Set<Long> seen = new HashSet<>();
        int borrow = 0, lend = 0, returned = 0, onTime = 0;
        for (BorrowRequest br : pool) {
            if (!seen.add(br.getId())) continue;
            if (!BorrowStatus.RETURNED.equals(br.getStatus())) continue;
            if (!ratedBorrowIds.contains(br.getId())) continue;  // 仅统计已评价的记录，与借入/借出口径一致
            boolean isBorrowRole = ActivityRole.BORROW.equals(resolveBorrowRole(br, userId));
            if (isBorrowRole) {
                returned++;
                if (Boolean.TRUE.equals(br.getIsOnTime())) onTime++;
                borrow++;
            } else {
                lend++;
            }
        }
        return new BorrowLendStats(borrow, lend, returned, onTime);
    }

    // ============================================================
    // 评价加载
    // ============================================================

    private void loadBorrowRatings(MyPostItemDTO dto, BorrowRequest br, Long currentUserId) {
        // 我对对方的评价
        Optional<Rating> myRating = ratingRepository.findFirstByBorrowIdAndFromUserId(br.getId(), currentUserId);
        myRating.ifPresent(r -> {
            dto.setMyRating(r.getScore().doubleValue());
            dto.setMyFeedback(r.getFeedback());
        });

        // 对方对我的评价
        Long idleOwnerId = br.getIdleItem() != null ? br.getIdleItem().getUserId() : null;
        if (idleOwnerId == null) {
            // 懒加载失败时的兜底查询
            idleOwnerId = idleItemRepository.findById(br.getIdleId())
                    .map(IdleItem::getUserId).orElse(null);
        }
        Long otherUserId = currentUserId.equals(br.getBorrowerId())
                ? idleOwnerId
                : br.getBorrowerId();
        if (otherUserId != null) {
            Optional<Rating> theirRating = ratingRepository.findFirstByBorrowIdAndFromUserId(br.getId(), otherUserId);
            theirRating.ifPresent(r -> {
                dto.setTheirRating(r.getScore().doubleValue());
                dto.setTheirFeedback(r.getFeedback());
            });
        }
    }
    private void loadHelpRatings(MyPostItemDTO dto, HelpApplication app, Long currentUserId) {
        // 我对对方的评价
        Optional<Rating> myRating = ratingRepository.findFirstByHelpApplicationIdAndFromUserId(app.getId(), currentUserId);
        myRating.ifPresent(r -> {
            dto.setMyRating(r.getScore().doubleValue());
            dto.setMyFeedback(r.getFeedback());
        });

        // 对方对我的评价
        HelpRequest hr = app.getHelpRequest();
        if (hr == null) {
            hr = helpRequestRepository.findById(app.getHelpId()).orElse(null);
        }
        Long otherUserId = currentUserId.equals(app.getHelperId())
                ? (hr != null ? hr.getUserId() : null)
                : app.getHelperId();
        if (otherUserId != null) {
            Optional<Rating> theirRating = ratingRepository.findFirstByHelpApplicationIdAndFromUserId(app.getId(), otherUserId);
            theirRating.ifPresent(r -> {
                dto.setTheirRating(r.getScore().doubleValue());
                dto.setTheirFeedback(r.getFeedback());
            });
        }
    }

    // ============================================================
    // 用户统计数据补充
    // ============================================================

    /**
     * 为 DTO 补充用户统计数据（评分、借入/借出/求助/帮助他人次数）。
     * 次数口径：已完成且被对方评价才计数（详见 interactionStats）。
     */
    private void enrichUserStats(MyPostItemDTO dto, Long userId) {
        if (userId == null) return;

        // 评分
        Double avgScore = ratingRepository.getAverageScore(userId);
        dto.setPersonRating(avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : null);

        InteractionStats stats = interactionStats(userId);
        dto.setBorrowCount(stats.borrowCount());
        // 尚无已归还的互借记录时默认 100%
        dto.setBorrowReturnRate(stats.returnedCount() > 0
                ? Math.round((double) stats.onTimeCount() / stats.returnedCount() * 1000.0) / 10.0
                : 100.0);
        dto.setLendCount(stats.lendCount());
        dto.setHelpReqCount(stats.helpReqCount());
        dto.setHelpProCount(stats.helpProCount());
    }

}
