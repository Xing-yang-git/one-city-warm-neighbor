package com.platform.service;

import com.platform.ai.moderation.ModerationService;
import com.platform.common.AppTimeZone;
import com.platform.common.HelpApplicationStatus;
import com.platform.common.PostStatus;
import com.platform.model.dto.ApproveRequest;
import com.platform.model.dto.HelpRequestDTO;
import com.platform.model.dto.HelpResponseDTO;
import com.platform.model.dto.NotificationDTO;
import com.platform.model.dto.PageDTO;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.User;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HelpService 单元测试")
class HelpServiceTest {

    @Mock
    private HelpRequestRepository helpRequestRepository;
    @Mock
    private HelpApplicationRepository helpApplicationRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserActivityService userActivityService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private ModerationService moderationService;

    @InjectMocks
    private HelpService helpService;

    private Long userId;
    private Long helperId;
    private Long helpId;
    private Long appId;
    private HelpRequest helpRequest;
    private HelpApplication application;
    private User user;
    private User helper;

    @BeforeEach
    void setUp() {
        // 各实体使用不同的 Long 字面量，保证测试内 ID 互不冲突
        userId = 1L;
        helperId = 2L;
        helpId = 100L;
        appId = 200L;

        user = User.builder()
                .id(userId)
                .name("求助者")
                .userType("业主")
                .build();

        helper = User.builder()
                .id(helperId)
                .name("帮助者")
                .userType("业主")
                .build();

        helpRequest = HelpRequest.builder()
                .id(helpId)
                .userId(userId)
                .title("需要帮忙搬家具")
                .description("搬一个沙发")
                .category("搬家")
                .isUrgent(false)
                .status(PostStatus.ONLINE)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        application = HelpApplication.builder()
                .id(appId)
                .helpId(helpId)
                .helperId(helperId)
                .note("我可以帮忙")
                .status(HelpApplicationStatus.PENDING)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();
    }

    // ==================== publish ====================

    @Test
    @DisplayName("发布求助 - 正常发布求助成功")
    void should_publishHelp_when_validInput() {
        // 准备
        HelpRequestDTO req = new HelpRequestDTO();
        req.setTitle("需要帮忙");
        req.setDescription("帮忙搬东西");
        req.setCategory("搬家");
        req.setIsUrgent(true);
        req.setTimeStart("2026-07-10 09:00");
        req.setTimeEnd("2026-07-10 12:00");

        when(helpRequestRepository.save(any(HelpRequest.class))).thenAnswer(inv -> {
            HelpRequest hr = inv.getArgument(0);
            hr.setId(helpId);
            return hr;
        });
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.publish(userId, req);

        // 断言：发布后挂起等待 AI 异步审核（pending_review）
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("需要帮忙");
        assertThat(result.getStatus()).isEqualTo(PostStatus.PENDING_REVIEW);
        assertThat(result.getIsUrgent()).isTrue();
        verify(helpRequestRepository).save(any(HelpRequest.class));
    }

    @Test
    @DisplayName("发布求助 - isUrgent为null时默认false")
    void should_defaultIsUrgentToFalse_when_null() {
        // 准备
        HelpRequestDTO req = new HelpRequestDTO();
        req.setTitle("非紧急求助");
        req.setDescription("测试");
        req.setCategory("其他");

        when(helpRequestRepository.save(any(HelpRequest.class))).thenAnswer(inv -> {
            HelpRequest hr = inv.getArgument(0);
            hr.setId(helpId);
            return hr;
        });
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.publish(userId, req);

        // 断言
        assertThat(result.getIsUrgent()).isFalse();
    }

    // ==================== getHomeList ====================

    @Test
    @DisplayName("获取求助首页 - 正常返回分页列表")
    void should_returnHomeList_when_helpsExist() {
        // 准备
        Page<HelpRequest> helpPage = new PageImpl<>(List.of(helpRequest),
                PageRequest.of(0, 10), 1);
        // 用户未关联小区（tenantId 为 null）时走不带 tenant 过滤的查询分支
        when(helpRequestRepository.findByStatus(eq("online"), any(PageRequest.class)))
                .thenReturn(helpPage);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        PageDTO<HelpResponseDTO> result = helpService.getHomeList(userId, 0, 10);

        // 断言
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ==================== getDetail ====================

    @Test
    @DisplayName("获取求助详情 - 正常返回详情")
    void should_returnDetail_when_helpExists() {
        // 准备
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ratingRepository.getAverageScore(userId)).thenReturn(4.0);
        when(userActivityService.interactionStats(anyLong()))
                .thenReturn(new UserActivityService.InteractionStats(0, 0, 1, 5, 0, 0));

        // 执行
        HelpResponseDTO result = helpService.getDetail(helpId, null);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getRating()).isEqualTo(4.0);
        assertThat(result.getHelpCount()).isEqualTo(1);
        assertThat(result.getHelpedCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("获取求助详情 - 传入当前用户时附带其对该求助的申请状态")
    void should_returnUserApplyStatus_when_currentUserApplied() {
        // 准备
        HelpApplication mine = HelpApplication.builder()
                .id(appId).helpId(helpId).helperId(helperId).status(HelpApplicationStatus.PENDING).build();
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ratingRepository.getAverageScore(userId)).thenReturn(null);
        when(userActivityService.interactionStats(anyLong()))
                .thenReturn(new UserActivityService.InteractionStats(0, 0, 0, 0, 0, 0));
        when(helpApplicationRepository.findByHelpIdAndHelperIdOrderByCreatedAtDesc(helpId, helperId))
                .thenReturn(List.of(mine));

        // 执行
        HelpResponseDTO result = helpService.getDetail(helpId, helperId);

        // 断言
        assertThat(result.getUserApplyStatus()).isEqualTo(HelpApplicationStatus.PENDING);
    }

    @Test
    @DisplayName("获取求助详情 - 同一用户多条申请时优先返回仍有效的那条（被拒后可重申）")
    void should_preferActiveApplication_when_multipleApplications() {
        // 准备：先被拒、后重新申请且仍在待审批
        HelpApplication rejected = HelpApplication.builder()
                .id(301L).helpId(helpId).helperId(helperId).status(HelpApplicationStatus.REJECTED).build();
        HelpApplication pending = HelpApplication.builder()
                .id(302L).helpId(helpId).helperId(helperId).status(HelpApplicationStatus.PENDING).build();
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ratingRepository.getAverageScore(userId)).thenReturn(null);
        when(userActivityService.interactionStats(anyLong()))
                .thenReturn(new UserActivityService.InteractionStats(0, 0, 0, 0, 0, 0));
        when(helpApplicationRepository.findByHelpIdAndHelperIdOrderByCreatedAtDesc(helpId, helperId))
                .thenReturn(List.of(pending, rejected));

        // 执行
        HelpResponseDTO result = helpService.getDetail(helpId, helperId);

        // 断言
        assertThat(result.getUserApplyStatus()).isEqualTo(HelpApplicationStatus.PENDING);
    }

    @Test
    @DisplayName("获取求助详情 - 求助不存在时抛出异常")
    void should_throwException_when_helpNotFound() {
        // 准备
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.getDetail(helpId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("求助信息不存在");
    }

    // ==================== search ====================

    @Test
    @DisplayName("搜索求助 - 按用户所属小区隔离搜索")
    void should_searchHelp_when_keywordMatches() {
        // 准备：用户归属小区 10L，搜索必须走带 tenantId 的隔离查询（与首页列表一致）
        User tenantUser = User.builder().id(userId).name("张三").tenantId(10L).build();
        Page<HelpRequest> helpPage = new PageImpl<>(List.of(helpRequest),
                PageRequest.of(0, 10), 1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(tenantUser));
        when(helpRequestRepository.searchByTenant(
                eq("online"), eq(10L), eq("搬"), eq("搬"), any(PageRequest.class)))
                .thenReturn(helpPage);

        // 执行
        PageDTO<HelpResponseDTO> result = helpService.search(userId, "搬", 0, 10);

        // 断言
        assertThat(result.getContent()).hasSize(1);
    }

    // ==================== getMyPosts ====================

    @Test
    @DisplayName("获取我的求助 - 返回用户的求助列表")
    void should_returnMyPosts_when_userHasPosts() {
        // 准备
        when(helpRequestRepository.findByUserId(userId)).thenReturn(List.of(helpRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        List<HelpResponseDTO> result = helpService.getMyPosts(userId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("需要帮忙搬家具");
    }

    @Test
    @DisplayName("获取我的求助 - 无求助时返回空列表")
    void should_returnEmptyPosts_when_noPosts() {
        // 准备
        when(helpRequestRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 执行
        List<HelpResponseDTO> result = helpService.getMyPosts(userId);

        // 断言
        assertThat(result).isEmpty();
    }

    // ==================== delist ====================

    @Test
    @DisplayName("下架求助 - 正常下架")
    void should_delistHelp_when_ownerOperates() {
        // 准备
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpRequestRepository.save(any(HelpRequest.class))).thenReturn(helpRequest);
        when(helpApplicationRepository.findByHelpIdAndStatus(helpId, HelpApplicationStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.delist(userId, helpId);

        // 断言
        assertThat(result.getStatus()).isEqualTo("draft");
        assertThat(helpRequest.getStatus()).isEqualTo("draft");
    }

    @Test
    @DisplayName("下架求助 - 非所有者操作时抛出异常")
    void should_throwException_when_delistNotOwner() {
        // 准备
        Long otherId = 99L;
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.delist(otherId, helpId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权操作该求助");
    }

    // ==================== apply ====================

    @Test
    @DisplayName("申请帮助 - 正常申请成功")
    void should_applyHelp_when_validInput() {
        // 准备
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpApplicationRepository.existsByHelpIdAndHelperIdAndStatusIn(
                eq(helpId), eq(helperId), anyList())).thenReturn(false);
        when(helpApplicationRepository.save(any(HelpApplication.class))).thenAnswer(inv -> {
            HelpApplication app = inv.getArgument(0);
            app.setId(appId);
            return app;
        });
        when(notificationService.create(anyLong(), anyString(), anyString(), anyString(), anyLong())).thenReturn(new NotificationDTO());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.apply(helperId, helpId, "我可以帮忙");

        // 断言
        assertThat(result).isNotNull();
        verify(helpApplicationRepository).save(any(HelpApplication.class));
        verify(notificationService, atLeastOnce()).create(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("申请帮助 - 求助已下架时提示已下架而非被抢先申请")
    void should_throwException_when_helpClosed() {
        // 准备
        helpRequest.setStatus(PostStatus.OFFLINE);
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.apply(helperId, helpId, "note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该求助已下架，无法申请");
    }

    @Test
    @DisplayName("申请帮助 - 求助正在审核中时提示审核中而非被抢先申请")
    void should_throwException_when_helpPendingReview() {
        // 准备
        helpRequest.setStatus(PostStatus.PENDING_REVIEW);
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.apply(helperId, helpId, "note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该求助正在审核中，暂时无法申请，请稍后再试");
    }

    @Test
    @DisplayName("申请帮助 - 申请自己的求助时抛出异常")
    void should_throwException_when_applyingOwnHelp() {
        // 准备
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.apply(userId, helpId, "note"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("不能申请自己的求助");
    }

    // ==================== approveReject ====================

    /**
     * 模拟条件更新命中：按传入的目标状态改写实体并返回 1 行受影响。
     * 真实实现由数据库在一条语句内完成「判断仍是待审批 + 写入新状态」，
     * 此处以 thenAnswer 复刻其对外可见效果。
     */
    private void stubDecideIfPendingHit() {
        when(helpApplicationRepository.decideIfPending(eq(appId), anyString())).thenAnswer(inv -> {
            application.setStatus(inv.getArgument(1));
            return 1;
        });
    }

    @Test
    @DisplayName("审批帮助 - 通过申请并更新状态")
    void should_approveHelp_when_validApproval() {
        // 准备
        ApproveRequest req = new ApproveRequest();
        req.setApproved(true);

        when(helpApplicationRepository.findHelpIdById(appId)).thenReturn(Optional.of(helpId));
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        stubDecideIfPendingHit();
        when(helpApplicationRepository.findById(appId)).thenReturn(Optional.of(application));
        when(helpRequestRepository.save(any(HelpRequest.class))).thenReturn(helpRequest);
        when(notificationService.create(anyLong(), anyString(), anyString(), anyString(), anyLong())).thenReturn(new NotificationDTO());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.approveReject(userId, appId, req);

        // 断言
        assertThat(application.getStatus()).isEqualTo(HelpApplicationStatus.APPROVED);
        assertThat(helpRequest.getStatus()).isEqualTo(PostStatus.ACTIVE);
        verify(helpApplicationRepository).decideIfPending(appId, HelpApplicationStatus.APPROVED);
    }

    @Test
    @DisplayName("审批帮助 - 拒绝且无其他待审批申请时恢复为 online")
    void should_restoreOnline_when_rejectedWithoutOtherPending() {
        // 准备
        ApproveRequest req = new ApproveRequest();
        req.setApproved(false);

        when(helpApplicationRepository.findHelpIdById(appId)).thenReturn(Optional.of(helpId));
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        stubDecideIfPendingHit();
        when(helpApplicationRepository.findByHelpIdAndStatus(helpId, HelpApplicationStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(helpApplicationRepository.findById(appId)).thenReturn(Optional.of(application));
        when(helpRequestRepository.save(any(HelpRequest.class))).thenReturn(helpRequest);
        when(notificationService.create(anyLong(), anyString(), anyString(), anyString(), anyLong())).thenReturn(new NotificationDTO());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        helpService.approveReject(userId, appId, req);

        // 断言
        assertThat(helpRequest.getStatus()).isEqualTo(PostStatus.ONLINE);
        verify(helpApplicationRepository).decideIfPending(appId, HelpApplicationStatus.REJECTED);
    }

    @Test
    @DisplayName("审批帮助 - 非所有者操作时抛出异常")
    void should_throwException_when_approveNotOwner() {
        // 准备
        ApproveRequest req = new ApproveRequest();
        req.setApproved(true);
        Long otherId = 99L;

        when(helpApplicationRepository.findHelpIdById(appId)).thenReturn(Optional.of(helpId));
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.approveReject(otherId, appId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("无权操作该申请");
    }

    @Test
    @DisplayName("审批帮助 - 申请已被处理（并发重复审批后到者）时抛出异常")
    void should_throwException_when_applicationAlreadyProcessed() {
        // 准备
        ApproveRequest req = new ApproveRequest();
        req.setApproved(true);

        when(helpApplicationRepository.findHelpIdById(appId)).thenReturn(Optional.of(helpId));
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        // 条件更新命中 0 行 → 申请已被他人处理
        when(helpApplicationRepository.decideIfPending(eq(appId), anyString())).thenReturn(0);

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.approveReject(userId, appId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该申请已被处理，无法重复操作");
    }

    // ==================== completeHelp ====================

    @Test
    @DisplayName("完成帮助 - 正常完成帮助")
    void should_completeHelp_when_validCompletion() {
        // 准备
        application.setStatus(HelpApplicationStatus.APPROVED);

        when(helpApplicationRepository.findById(appId)).thenReturn(Optional.of(application));
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpApplicationRepository.save(any(HelpApplication.class))).thenReturn(application);
        when(helpRequestRepository.save(any(HelpRequest.class))).thenReturn(helpRequest);
        when(notificationService.create(anyLong(), anyString(), anyString(), anyString(), anyLong())).thenReturn(new NotificationDTO());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.completeHelp(userId, appId);

        // 断言
        assertThat(application.getStatus()).isEqualTo(HelpApplicationStatus.COMPLETED);
        assertThat(helpRequest.getStatus()).isEqualTo(PostStatus.COMPLETED);
        verify(notificationService, atLeastOnce()).create(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("完成帮助 - 只能完成进行中的申请")
    void should_throwException_when_notInProgressApplication() {
        // 准备
        application.setStatus("pending");

        when(helpApplicationRepository.findById(appId)).thenReturn(Optional.of(application));
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.completeHelp(userId, appId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("只能完成进行中的帮助申请");
    }

    // ==================== update ====================

    @Test
    @DisplayName("更新求助 - 正常更新求助信息")
    void should_updateHelp_when_validUpdate() {
        // 准备
        HelpRequestDTO req = new HelpRequestDTO();
        req.setTitle("更新后的求助标题");
        req.setDescription("更新描述");

        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpApplicationRepository.findByHelpIdAndStatus(helpId, HelpApplicationStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(helpRequestRepository.save(any(HelpRequest.class))).thenReturn(helpRequest);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.update(userId, helpId, req);

        // 断言
        assertThat(result.getTitle()).isEqualTo("更新后的求助标题");
    }

    @Test
    @DisplayName("更新求助 - completed状态编辑后退回 pending_review 重新审核")
    void should_autoRelist_when_statusCompleted() {
        // 准备
        helpRequest.setStatus(PostStatus.COMPLETED);
        HelpRequestDTO req = new HelpRequestDTO();
        req.setTitle("重新发布");

        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpApplicationRepository.findByHelpIdAndStatus(helpId, HelpApplicationStatus.PENDING))
                .thenReturn(Collections.emptyList());
        when(helpRequestRepository.save(any(HelpRequest.class))).thenReturn(helpRequest);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        HelpResponseDTO result = helpService.update(userId, helpId, req);

        // 断言：重新发布走 AI 审核流程，先挂起而非直接上线
        assertThat(result.getStatus()).isEqualTo(PostStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("编辑门禁 - 有住户正在申请时拒绝编辑")
    void should_throwException_when_editingWithPendingApplication() {
        // 准备
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpApplicationRepository.findByHelpIdAndStatus(helpId, HelpApplicationStatus.PENDING))
                .thenReturn(List.of(application));
        HelpRequestDTO req = new HelpRequestDTO();

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.update(userId, helpId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该求助有住户正在申请，请先处理申请后再编辑");
    }

    @Test
    @DisplayName("编辑门禁 - 内容仍在审核中时拒绝编辑（避免旧结论用到新内容）")
    void should_throwException_when_editingWhilePendingReview() {
        // 准备
        helpRequest.setStatus(PostStatus.PENDING_REVIEW);
        when(helpRequestRepository.findByIdWithLock(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpApplicationRepository.findByHelpIdAndStatus(helpId, HelpApplicationStatus.PENDING))
                .thenReturn(Collections.emptyList());
        HelpRequestDTO req = new HelpRequestDTO();

        // 执行 & 断言
        assertThatThrownBy(() -> helpService.update(userId, helpId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该帖子正在审核中，请等审核完成后再编辑");
    }

    // ==================== getMyApplications ====================

    @Test
    @DisplayName("获取我的帮助申请 - 返回申请列表")
    void should_returnMyApplications_when_hasApplications() {
        // 准备
        when(helpApplicationRepository.findByHelperId(helperId)).thenReturn(List.of(application));
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        List<HelpResponseDTO> result = helpService.getMyApplications(helperId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getApplicationStatus()).isEqualTo(HelpApplicationStatus.PENDING);
        assertThat(result.get(0).getApplicationId()).isEqualTo(appId);
    }

    // ==================== getPendingApprovals ====================

    @Test
    @DisplayName("获取待审批 - 返回待审批帮助申请")
    void should_returnPendingApprovals_when_hasPending() {
        // 准备
        when(helpRequestRepository.findByUserId(userId)).thenReturn(List.of(helpRequest));
        when(helpApplicationRepository.findByHelpIdAndStatus(helpId, "pending"))
                .thenReturn(List.of(application));
        when(userRepository.findById(helperId)).thenReturn(Optional.of(helper));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        List<HelpResponseDTO> result = helpService.getPendingApprovals(userId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHelperName()).isEqualTo("帮助者");
    }
}
