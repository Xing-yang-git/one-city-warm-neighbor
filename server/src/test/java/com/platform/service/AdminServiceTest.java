package com.platform.service;

import com.platform.common.AppTimeZone;
import com.platform.common.ContentType;
import com.platform.common.DamageType;
import com.platform.common.PostType;
import com.platform.model.dto.*;
import com.platform.model.entity.*;
import com.platform.repository.*;
import com.platform.websocket.ChatWebSocketHandler;
import com.platform.common.ActivityRole;
import com.platform.common.BizStatus;
import com.platform.common.UserType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * AdminService 单元测试。
 *
 * <p>越权口径（requireSuperAdmin 用例）：项目尚无 Controller/MockMvc 测试先例，
 * 敏感词管理等平台级配置模块（{@code /api/admin/sensitive-words}）每个端点首行调用
 * {@link AdminService#requireSuperAdmin(Long)}，非 super_admin 一律拒绝（业务异常）。
 * 因此越权校验按服务层用例覆盖：super_admin 放行、普通/高级管理员被拒、账号不存在被拒。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService 单元测试")
class AdminServiceTest {

    @Mock private IdleItemRepository idleItemRepository;
    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private BorrowRequestRepository borrowRequestRepository;
    @Mock private HelpApplicationRepository helpApplicationRepository;
    @Mock private UserRepository userRepository;
    @Mock private OperationLogRepository operationLogRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private BuildingRepository buildingRepository;
    @Mock private UnitRepository unitRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private RatingRepository ratingRepository;
    @Mock private ExportLogRepository exportLogRepository;
    @Mock private UserActivityService userActivityService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private NotificationService notificationService;
    @Mock private ChatWebSocketHandler chatWebSocketHandler;

    @InjectMocks
    private AdminService adminService;

    private Long adminId;
    private Long userId;
    private Long itemId;
    private Long helpId;
    private Long tenantId;
    private User admin;
    private User user;
    private IdleItem idleItem;
    private HelpRequest helpRequest;

    @BeforeEach
    void setUp() {
        // 各实体使用不同的 Long 字面量，保证测试内 ID 互不冲突
        adminId = 1L;
        userId = 2L;
        itemId = 100L;
        helpId = 200L;
        tenantId = 10L;

        // 管理员账号：高级管理员可访问本小区业务数据 + 操作日志/导出（super_admin 仅管理系统设置，无业务数据权限）
        admin = User.builder()
                .id(adminId)
                .name("管理员")
                .userType(UserType.SENIOR_ADMIN)
                .tenantId(tenantId)
                .authStatus(BizStatus.APPROVED)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        user = User.builder()
                .id(userId)
                .name("测试用户")
                .userType("业主")
                .tenantId(tenantId)
                .authStatus(BizStatus.APPROVED)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        idleItem = IdleItem.builder()
                .id(itemId)
                .userId(userId)
                .tenantId(tenantId)
                .title("闲置物品")
                .description("物品描述")
                .postType(PostType.LEND)
                .category("数码")
                .status(BizStatus.ONLINE)
                .isProxy(false)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        helpRequest = HelpRequest.builder()
                .id(helpId)
                .userId(userId)
                .tenantId(tenantId)
                .title("求助信息")
                .description("求助描述")
                .category("搬家")
                .isUrgent(false)
                .status(BizStatus.ONLINE)
                .isProxy(false)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();
    }

    // ==================== getDashboard ====================

    /** 构造一条租户内借用记录（供看板测试，idleId 需落在 idleItems 映射内） */
    private BorrowRequest buildBorrow(Long idleId, Long borrowerId) {
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        return BorrowRequest.builder().idleId(idleId).borrowerId(borrowerId)
                .status(BizStatus.RETURNED).damageType(DamageType.NORMAL)
                .createdAt(now).returnedAt(now)
                .build();
    }

    @Test
    @DisplayName("获取仪表盘 - 正常返回统计数据")
    void should_returnDashboard_when_dataExists() {
        // 准备：本月归还完成且有正常耗损的借用 + 本月完成的帮助接单
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        BorrowRequest borrow = buildBorrow(itemId, userId);
        HelpApplication helpApp = HelpApplication.builder()
                .id(400L).helpId(helpId).helperId(userId)
                .status(BizStatus.COMPLETED)
                .createdAt(now).completedAt(now)
                .build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(List.of(idleItem));
        when(helpRequestRepository.findAll()).thenReturn(List.of(helpRequest));
        when(borrowRequestRepository.findAll()).thenReturn(List.of(borrow));
        when(helpApplicationRepository.findAll()).thenReturn(List.of(helpApp));
        when(ratingRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findAll()).thenReturn(List.of(user));

        // 执行
        DashboardDTO result = adminService.getDashboard(adminId);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getKpis()).hasSize(4);
        assertThat(result.getKpis().get(0).getKey()).isEqualTo(ContentType.IDLE);
        assertThat(result.getKpis().get(0).getValue()).isEqualTo(1);   // LEND online
        assertThat(result.getKpis().get(1).getKey()).isEqualTo(ContentType.HELP);
        assertThat(result.getKpis().get(1).getValue()).isEqualTo(1);   // 求助 online
        assertThat(result.getCompletion().getCompleted()).isEqualTo(2); // 归还借用 + 完成帮助
        assertThat(result.getDamage().getNormal()).isEqualTo(1);
        assertThat(result.getRanking()).isNotEmpty();
        assertThat(result.getTrends().getWeek().getLabels()).hasSize(7);
    }

    @Test
    @DisplayName("KPI 环比 - 本月 vs 上月发布增速")
    void should_computeKpiMomChange_correctly() {
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        LocalDateTime lastMonth = now.minusMonths(1);
        List<IdleItem> idleItems = new ArrayList<>();
        // 本月 3 条 LEND 发布
        for (int i = 0; i < 3; i++) {
            idleItems.add(IdleItem.builder().id(100L + i).userId(userId).tenantId(tenantId)
                    .postType(PostType.LEND).status(BizStatus.ONLINE).category("数码").createdAt(now).build());
        }
        // 上月 6 条 LEND 发布
        for (int i = 0; i < 6; i++) {
            idleItems.add(IdleItem.builder().id(200L + i).userId(userId).tenantId(tenantId)
                    .postType(PostType.LEND).status(BizStatus.ONLINE).category("数码").createdAt(lastMonth).build());
        }
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(idleItems);
        when(helpRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(helpApplicationRepository.findAll()).thenReturn(Collections.emptyList());
        when(ratingRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findAll()).thenReturn(List.of(user));

        DashboardDTO result = adminService.getDashboard(adminId);

        // 在线闲置(LEND online) = 9；idle 环比 = 本月 3 vs 上月 6 = -50%
        assertThat(result.getKpis().get(0).getValue()).isEqualTo(9);
        assertThat(result.getKpis().get(0).getMomChange()).isEqualTo(-50.0);
        // 本月发布 = 3、pub 环比 -50%
        assertThat(result.getKpis().get(2).getValue()).isEqualTo(3);
        assertThat(result.getKpis().get(2).getMomChange()).isEqualTo(-50.0);
    }

    @Test
    @DisplayName("损坏统计 - 三态分布")
    void should_computeDamageThreeStates() {
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        IdleItem i1 = IdleItem.builder().id(itemId).userId(userId).tenantId(tenantId)
                .postType(PostType.LEND).status(BizStatus.ONLINE).createdAt(now).build();
        IdleItem i2 = IdleItem.builder().id(itemId + 1).userId(userId).tenantId(tenantId)
                .postType(PostType.LEND).status(BizStatus.ONLINE).createdAt(now).build();
        List<BorrowRequest> borrows = List.of(
                BorrowRequest.builder().idleId(i1.getId()).borrowerId(userId).damageType(DamageType.NORMAL).build(),
                BorrowRequest.builder().idleId(i1.getId()).borrowerId(userId).damageType(DamageType.NORMAL).build(),
                BorrowRequest.builder().idleId(i2.getId()).borrowerId(userId).damageType(DamageType.ABNORMAL).build(),
                BorrowRequest.builder().idleId(i2.getId()).borrowerId(userId).damageType(DamageType.BROKEN).build());
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(List.of(i1, i2));
        when(helpRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findAll()).thenReturn(borrows);
        when(helpApplicationRepository.findAll()).thenReturn(Collections.emptyList());
        when(ratingRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findAll()).thenReturn(List.of(user));

        DashboardDTO result = adminService.getDashboard(adminId);

        assertThat(result.getDamage().getNormal()).isEqualTo(2);
        assertThat(result.getDamage().getSevere()).isEqualTo(1);
        assertThat(result.getDamage().getBroken()).isEqualTo(1);
    }

    @Test
    @DisplayName("趋势 - 周/月/季分桶结构与当日计数")
    void should_computeTrendBuckets() {
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        // 今日发布 1 条闲置、今日归还 1 条借用（应落在 week 最后一个桶「今日」）
        IdleItem todayIdle = IdleItem.builder().id(itemId).userId(userId).tenantId(tenantId)
                .postType(PostType.LEND).status(BizStatus.ONLINE).createdAt(now).build();
        BorrowRequest todayReturned = BorrowRequest.builder().id(300L).idleId(itemId).borrowerId(userId)
                .status(BizStatus.RETURNED).createdAt(now).returnedAt(now).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(List.of(todayIdle));
        when(helpRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findAll()).thenReturn(List.of(todayReturned));
        when(helpApplicationRepository.findAll()).thenReturn(Collections.emptyList());
        when(ratingRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findAll()).thenReturn(List.of(user));

        DashboardDTO result = adminService.getDashboard(adminId);

        DashboardDTO.TrendData week = result.getTrends().getWeek();
        assertThat(week.getLabels()).hasSize(7);
        assertThat(week.getLabels().get(6)).isEqualTo("今日");
        assertThat(week.getPublish().get(6)).isEqualTo(1);
        assertThat(week.getCompleted().get(6)).isEqualTo(1);

        DashboardDTO.TrendData month = result.getTrends().getMonth();
        assertThat(month.getLabels().get(0)).isEqualTo("第1周");
        assertThat(month.getPublish()).contains(1L);   // 今日发布落入本月某一周桶

        assertThat(result.getTrends().getQuarter().getLabels()).hasSize(3);
    }

    @Test
    @DisplayName("排行 - 按住户合并闲置借入与技能接单次数")
    void should_computeRanking_groupedByUserAndType() {
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        Building building = Building.builder().id(1L).buildingNo(3).tenantId(tenantId).build();
        Unit unit = Unit.builder().id(1L).unitNo(2).building(building).build();
        Room room = Room.builder().id(1L).roomNumber("1502").unit(unit).build();
        User borrower = User.builder().id(3L).name("借入住户").userType(UserType.OWNER)
                .tenantId(tenantId).room(room).authStatus(BizStatus.APPROVED).createdAt(now).build();
        User helper = User.builder().id(4L).name("接单住户").userType(UserType.TENANT)
                .tenantId(tenantId).authStatus(BizStatus.APPROVED).createdAt(now).build();
        IdleItem i1 = IdleItem.builder().id(itemId).userId(3L).tenantId(tenantId)
                .postType(PostType.LEND).status(BizStatus.ONLINE).createdAt(now).build();
        HelpRequest hr = HelpRequest.builder().id(helpId).userId(4L).tenantId(tenantId)
                .title("求助").status(BizStatus.COMPLETED).createdAt(now).build();
        // 3 号住户：2 次借入 + 1 次完成接单 → 合并 3 次；4 号住户：1 次完成接单 → 1 次
        HelpApplication appForBorrower = HelpApplication.builder().id(401L).helpId(helpId).helperId(3L)
                .status(BizStatus.COMPLETED).createdAt(now).completedAt(now).build();
        HelpApplication appForHelper = HelpApplication.builder().id(402L).helpId(helpId).helperId(4L)
                .status(BizStatus.COMPLETED).createdAt(now).completedAt(now).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(List.of(i1));
        when(helpRequestRepository.findAll()).thenReturn(List.of(hr));
        when(borrowRequestRepository.findAll()).thenReturn(List.of(buildBorrow(itemId, 3L), buildBorrow(itemId, 3L)));
        when(helpApplicationRepository.findAll()).thenReturn(List.of(appForBorrower, appForHelper));
        when(ratingRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findAll()).thenReturn(List.of(borrower, helper));

        DashboardDTO result = adminService.getDashboard(adminId);

        List<DashboardDTO.RankingItem> ranking = result.getRanking();
        // 同一住户只出现一条：3 号合并 3 次排第一，展示名含房号
        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).getName()).isEqualTo("3栋2单元1502号(业主)");
        assertThat(ranking.get(0).getCount()).isEqualTo(3);
        assertThat(ranking.get(1).getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("仪表盘 - 无数据时全 0 不抛异常")
    void should_returnZeroDashboard_when_empty() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(Collections.emptyList());
        when(helpRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(helpApplicationRepository.findAll()).thenReturn(Collections.emptyList());
        when(ratingRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        DashboardDTO result = adminService.getDashboard(adminId);

        assertThat(result.getKpis()).hasSize(4);
        for (DashboardDTO.KpiStat k : result.getKpis()) {
            assertThat(k.getValue()).isZero();
            assertThat(k.getMomChange()).isZero();
        }
        assertThat(result.getCompletion().getRate()).isZero();
        assertThat(result.getDamage().getNormal()).isZero();
        assertThat(result.getRanking()).isEmpty();
    }

    @Test
    @DisplayName("仪表盘 - 跨租户数据不计入")
    void should_exclude_otherTenant_when_dashboard() {
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        IdleItem otherTenant = IdleItem.builder().id(900L).userId(9L).tenantId(99L)
                .postType(PostType.LEND).status(BizStatus.ONLINE).createdAt(now).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(List.of(otherTenant));
        when(helpRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findAll()).thenReturn(Collections.emptyList());
        when(helpApplicationRepository.findAll()).thenReturn(Collections.emptyList());
        when(ratingRepository.findAll()).thenReturn(Collections.emptyList());
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        DashboardDTO result = adminService.getDashboard(adminId);

        assertThat(result.getKpis().get(0).getValue()).isZero();   // 他租户闲置不计入
        assertThat(result.getTrends().getWeek().getPublish()).allMatch(v -> v == 0L);
    }

    // ==================== getAudits ====================

    @Test
    @DisplayName("获取审核列表 - 按状态过滤返回审核用户")
    void should_returnAudits_when_filteredByStatus() {
        // 准备
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        // 服务内会按创建时间对列表排序，需返回可变列表
        when(userRepository.findByTenantIdAndAuthStatus(tenantId, "pending")).thenReturn(new ArrayList<>(List.of(user)));

        // 执行
        List<UserDTO> result = adminService.getAudits(adminId, "pending");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("获取审核列表 - 空状态时排除 registering 用户")
    void should_returnAllNonRegistering_when_statusEmpty() {
        // 准备
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        // 服务内会按创建时间对列表排序，需返回可变列表
        when(userRepository.findByTenantIdAndAuthStatusNot(tenantId, BizStatus.REGISTERING)).thenReturn(new ArrayList<>(List.of(user)));

        // 执行
        List<UserDTO> result = adminService.getAudits(adminId, null);

        // 断言
        assertThat(result).hasSize(1);
    }

    // ==================== getAuditCounts ====================

    @Test
    @DisplayName("获取审核计数 - 返回各状态的数量")
    void should_returnAuditCounts_when_called() {
        // 准备（"全部住户"页签排除管理员账号，因此 approved 使用带 userType 排除的计数）
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.countByTenantIdAndAuthStatus(tenantId, "pending")).thenReturn(5L);
        when(userRepository.countByTenantIdAndAuthStatusAndUserTypeNotIn(eq(tenantId), eq("approved"), anyList())).thenReturn(10L);
        when(userRepository.countByTenantIdAndAuthStatus(tenantId, "rejected")).thenReturn(2L);
        when(userRepository.countByTenantIdAndAuthStatusNot(tenantId, BizStatus.REGISTERING)).thenReturn(17L);

        // 执行
        AuditCountDTO result = adminService.getAuditCounts(adminId);

        // 断言
        assertThat(result.getPending()).isEqualTo(5L);
        assertThat(result.getApproved()).isEqualTo(10L);
        assertThat(result.getRejected()).isEqualTo(2L);
        assertThat(result.getAll()).isEqualTo(17L);
    }

    // ==================== auditUser ====================

    @Test
    @DisplayName("审核用户 - 通过审核")
    void should_approveUser_when_approved() {
        // 准备
        AuditRequest req = new AuditRequest();
        req.setApproved(true);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        Map<String, Object> result = adminService.auditUser(adminId, userId, req);

        // 断言
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("message")).isEqualTo("审核通过");
        assertThat(user.getAuthStatus()).isEqualTo(BizStatus.APPROVED);
        assertThat(user.getRejectReason()).isNull();
        verify(operationLogRepository).save(any(OperationLog.class));
    }

    @Test
    @DisplayName("审核用户 - 拒绝审核并记录原因")
    void should_rejectUser_when_rejected() {
        // 准备
        AuditRequest req = new AuditRequest();
        req.setApproved(false);
        req.setReason("资料不完整");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        Map<String, Object> result = adminService.auditUser(adminId, userId, req);

        // 断言
        assertThat(result.get("message")).isEqualTo("已拒绝");
        assertThat(user.getAuthStatus()).isEqualTo("rejected");
        assertThat(user.getRejectReason()).isEqualTo("资料不完整");
        // 拒绝即踢：tokenVersion +1 并断开其在线 WS 连接
        assertThat(user.getTokenVersion()).isEqualTo(1);
        verify(chatWebSocketHandler).closeUserSessions(userId.toString());
    }

    @Test
    @DisplayName("审核用户 - 用户不存在时抛出异常")
    void should_throwException_when_userNotFound() {
        // 准备：先校验管理员身份（requireNotSuperAdmin），再查目标用户
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> adminService.auditUser(adminId, userId, new AuditRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("用户不存在");
    }

    // ==================== getContentCounts ====================

    @Test
    @DisplayName("获取内容计数 - 返回各状态的数量")
    void should_returnContentCounts() {
        // 准备
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.countByTenantIdAndStatus(tenantId, "online")).thenReturn(10L);
        when(helpRequestRepository.countByTenantIdAndStatus(tenantId, "online")).thenReturn(5L);
        when(idleItemRepository.countByTenantIdAndStatus(tenantId, "active")).thenReturn(3L);
        when(helpRequestRepository.countByTenantIdAndStatus(tenantId, "active")).thenReturn(2L);
        when(idleItemRepository.countByTenantIdAndStatus(tenantId, "completed")).thenReturn(8L);
        when(helpRequestRepository.countByTenantIdAndStatus(tenantId, "completed")).thenReturn(4L);
        when(idleItemRepository.countByTenantIdAndStatus(tenantId, BizStatus.OFFLINE)).thenReturn(1L);
        when(helpRequestRepository.countByTenantIdAndStatus(tenantId, BizStatus.OFFLINE)).thenReturn(1L);

        // 执行
        ContentCountDTO result = adminService.getContentCounts(adminId);

        // 断言
        assertThat(result.getShowing()).isEqualTo(15L);
        assertThat(result.getProgressing()).isEqualTo(5L);
        assertThat(result.getCompleted()).isEqualTo(12L);
        assertThat(result.getViolation()).isEqualTo(2L);
        assertThat(result.getAll()).isEqualTo(34L);
    }

    // ==================== getContentDetail ====================

    @Test
    @DisplayName("获取内容详情 - idle类型返回详情")
    void should_returnIdleDetail_when_typeIdle() {
        // 准备
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        ContentItemDTO result = adminService.getContentDetail(adminId, itemId, "idle");

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ContentType.IDLE);
        assertThat(result.getTitle()).isEqualTo("闲置物品");
    }

    @Test
    @DisplayName("获取内容详情 - help类型返回详情")
    void should_returnHelpDetail_when_typeHelp() {
        // 准备
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        ContentItemDTO result = adminService.getContentDetail(adminId, helpId, "help");

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ContentType.HELP);
        assertThat(result.getTitle()).isEqualTo("求助信息");
    }

    @Test
    @DisplayName("获取内容详情 - idle借用中状态包含对方信息和时间范围")
    void should_returnBorrowingPeerInfo_when_idleStatusBorrowing() {
        // 准备：借用中的闲置物品
        IdleItem borrowingItem = IdleItem.builder()
                .id(itemId)
                .userId(userId)
                .tenantId(tenantId)
                .title("借用中的物品")
                .postType(PostType.LEND)
                .status(BizStatus.ACTIVE)
                .isProxy(false)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        User borrower = User.builder()
                .id(3L)
                .name("借入人")
                .userType("业主")
                .tenantId(tenantId)
                .build();

        BorrowRequest borrowRequest = BorrowRequest.builder()
                .id(300L)
                .idleId(itemId)
                .borrowerId(3L)
                .status(BizStatus.ACTIVE)
                .startDate(LocalDate.of(2026, 7, 20))
                .durationDays(7)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(borrowingItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(borrowRequestRepository.findByIdleId(itemId)).thenReturn(List.of(borrowRequest));
        when(userRepository.findById(3L)).thenReturn(Optional.of(borrower));

        // 执行
        ContentItemDTO result = adminService.getContentDetail(adminId, itemId, "idle");

        // 断言：对方信息和时间范围已填充
        assertThat(result).isNotNull();
        assertThat(result.getPeerName()).isEqualTo("借入人");
        assertThat(result.getTimeStart()).isEqualTo(LocalDate.of(2026, 7, 20).atStartOfDay());
        assertThat(result.getTimeEnd()).isEqualTo(LocalDate.of(2026, 7, 27).atStartOfDay());
        // 已完成特有字段应为空
        assertThat(result.getPeerRating()).isNull();
        assertThat(result.getPublisherRatingStars()).isNull();
        assertThat(result.getApplyAt()).isNull();
    }

    @Test
    @DisplayName("获取内容详情 - idle已完成状态包含评价和时间线")
    void should_returnCompletedPeerInfo_when_idleStatusCompleted() {
        // 准备：已完成的闲置物品
        IdleItem completedItem = IdleItem.builder()
                .id(itemId)
                .userId(userId)
                .tenantId(tenantId)
                .title("已完成的物品")
                .postType(PostType.LEND)
                .status(BizStatus.COMPLETED)
                .isProxy(false)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        User borrower = User.builder()
                .id(3L)
                .name("借入人")
                .userType("业主")
                .tenantId(tenantId)
                .build();

        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        BorrowRequest completedBorrow = BorrowRequest.builder()
                .id(300L)
                .idleId(itemId)
                .borrowerId(3L)
                .status(BizStatus.RETURNED)
                .startDate(LocalDate.of(2026, 7, 15))
                .durationDays(5)
                .createdAt(now.minusDays(10))
                .build();
        completedBorrow.setUpdatedAt(now);
        completedBorrow.setReturnedAt(now);

        Rating pubRating = Rating.builder()
                .id(1L)
                .borrowId(300L)
                .fromUserId(3L)
                .score(5)
                .build();

        Rating peerRating = Rating.builder()
                .id(2L)
                .borrowId(300L)
                .fromUserId(userId)
                .score(4)
                .build();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(completedItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(borrowRequestRepository.findByIdleId(itemId)).thenReturn(List.of(completedBorrow));
        when(userRepository.findById(3L)).thenReturn(Optional.of(borrower));
        when(ratingRepository.getAverageScore(3L)).thenReturn(4.5);
        when(ratingRepository.findFirstByBorrowIdAndFromUserId(300L, 3L)).thenReturn(Optional.of(pubRating));
        when(ratingRepository.findFirstByBorrowIdAndFromUserId(300L, userId)).thenReturn(Optional.of(peerRating));

        // 执行
        ContentItemDTO result = adminService.getContentDetail(adminId, itemId, "idle");

        // 断言：对方信息
        assertThat(result).isNotNull();
        assertThat(result.getPeerName()).isEqualTo("借入人");
        assertThat(result.getPeerRating()).isEqualTo(4.5);
        // 时间范围
        assertThat(result.getTimeStart()).isEqualTo(LocalDate.of(2026, 7, 15).atStartOfDay());
        assertThat(result.getTimeEnd()).isEqualTo(LocalDate.of(2026, 7, 20).atStartOfDay());
        // 评价
        assertThat(result.getPublisherRatingScore()).isEqualTo(5.0);
        assertThat(result.getPeerRatingScore()).isEqualTo(4.0);
        // 时间线
        assertThat(result.getApplyAt()).isEqualTo(completedBorrow.getCreatedAt());
        assertThat(result.getApproveAt()).isEqualTo(LocalDate.of(2026, 7, 15).atStartOfDay());
        assertThat(result.getCompleteAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("获取内容详情 - help帮助中状态包含对方信息")
    void should_returnHelpingPeerInfo_when_helpStatusHelping() {
        // 准备
        HelpRequest helpingItem = HelpRequest.builder()
                .id(helpId)
                .userId(userId)
                .tenantId(tenantId)
                .title("帮助中的求助")
                .status(BizStatus.ACTIVE)
                .isProxy(false)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        User helper = User.builder()
                .id(4L)
                .name("帮助者")
                .userType("业主")
                .tenantId(tenantId)
                .build();

        HelpApplication activeApp = HelpApplication.builder()
                .id(500L)
                .helpId(helpId)
                .helperId(4L)
                .status(BizStatus.APPROVED)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpingItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(helpApplicationRepository.findByHelpId(helpId)).thenReturn(List.of(activeApp));
        when(userRepository.findById(4L)).thenReturn(Optional.of(helper));

        // 执行
        ContentItemDTO result = adminService.getContentDetail(adminId, helpId, "help");

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(ContentType.HELP);
        assertThat(result.getPeerName()).isEqualTo("帮助者");
        // 已完成特有字段应为空
        assertThat(result.getPeerRating()).isNull();
        assertThat(result.getPublisherRatingStars()).isNull();
        assertThat(result.getApplyAt()).isNull();
    }

    @Test
    @DisplayName("获取内容详情 - help已完成状态包含评价和时间线")
    void should_returnHelpCompletedPeerInfo_when_helpStatusCompleted() {
        // 准备
        HelpRequest completedItem = HelpRequest.builder()
                .id(helpId)
                .userId(userId)
                .tenantId(tenantId)
                .title("已完成的求助")
                .status(BizStatus.COMPLETED)
                .timeStart(LocalDateTime.of(2026, 7, 10, 9, 0))
                .isProxy(false)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        User helper = User.builder()
                .id(4L)
                .name("帮助者")
                .userType("业主")
                .tenantId(tenantId)
                .build();

        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        HelpApplication completedApp = HelpApplication.builder()
                .id(500L)
                .helpId(helpId)
                .helperId(4L)
                .status(BizStatus.COMPLETED)
                .createdAt(now.minusDays(5))
                .build();
        completedApp.setCompletedAt(now);

        Rating pubRating = Rating.builder()
                .id(1L)
                .helpApplicationId(500L)
                .fromUserId(4L)
                .score(5)
                .build();

        Rating peerR = Rating.builder()
                .id(2L)
                .helpApplicationId(500L)
                .fromUserId(userId)
                .score(3)
                .build();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(completedItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(helpApplicationRepository.findByHelpId(helpId)).thenReturn(List.of(completedApp));
        when(userRepository.findById(4L)).thenReturn(Optional.of(helper));
        when(ratingRepository.getAverageScore(4L)).thenReturn(4.0);
        when(ratingRepository.findFirstByHelpApplicationIdAndFromUserId(500L, 4L)).thenReturn(Optional.of(pubRating));
        when(ratingRepository.findFirstByHelpApplicationIdAndFromUserId(500L, userId)).thenReturn(Optional.of(peerR));

        // 执行
        ContentItemDTO result = adminService.getContentDetail(adminId, helpId, "help");

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getPeerName()).isEqualTo("帮助者");
        assertThat(result.getPeerRating()).isEqualTo(4.0);
        assertThat(result.getPublisherRatingScore()).isEqualTo(5.0);
        assertThat(result.getPeerRatingScore()).isEqualTo(3.0);
        // 时间线：approveAt 用 item.getTimeStart()
        assertThat(result.getApplyAt()).isEqualTo(completedApp.getCreatedAt());
        assertThat(result.getApproveAt()).isEqualTo(LocalDateTime.of(2026, 7, 10, 9, 0));
        assertThat(result.getCompleteAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("获取内容详情 - 不支持的类型抛出异常")
    void should_throwException_when_unsupportedType() {
        // 执行 & 断言
        // 需要先 mock 管理员查询，getContentDetail 会校验 tenant 归属
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> adminService.getContentDetail(adminId, itemId, "unknown"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("不支持的类型，请使用 idle 或 help");
    }

    // ==================== removeContent ====================

    @Test
    @DisplayName("删除内容 - 删除idle类型内容")
    void should_removeIdleContent_when_validRequest() {
        // 准备
        ContentOfflineRequest req = new ContentOfflineRequest();
        req.setTargetType("idle");
        // 仅设置原因列表（customReason 为空，buildDelistReason 走原因拼接）
        req.setReasons(List.of("违规内容"));

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        OperationResultDTO result = adminService.removeContent(adminId, itemId, req);

        // 断言
        assertThat(result.getSuccess()).isEqualTo(true);
        assertThat(result.getMessage()).isEqualTo("内容已删除");
        assertThat(idleItem.getStatus()).isEqualTo(BizStatus.OFFLINE);
        assertThat(idleItem.getDelistReason()).isEqualTo("违规内容");
        verify(operationLogRepository).save(any(OperationLog.class));
    }

    @Test
    @DisplayName("删除内容 - 删除help类型内容")
    void should_removeHelpContent_when_validRequest() {
        // 准备
        ContentOfflineRequest req = new ContentOfflineRequest();
        req.setTargetType("help");
        req.setReasons(List.of("虚假信息"));

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(helpRequestRepository.findById(helpId)).thenReturn(Optional.of(helpRequest));
        when(helpRequestRepository.save(any(HelpRequest.class))).thenReturn(helpRequest);
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        OperationResultDTO result = adminService.removeContent(adminId, helpId, req);

        // 断言
        assertThat(result.getSuccess()).isEqualTo(true);
        assertThat(helpRequest.getStatus()).isEqualTo(BizStatus.OFFLINE);
    }

    @Test
    @DisplayName("删除内容 - 自定义原因与原因列表均为空时默认使用'违规'")
    void should_useDefaultViolationType_when_reasonsNull() {
        // 准备：customReason 与 reasons 均未设置 → buildDelistReason 走默认"违规"
        ContentOfflineRequest req = new ContentOfflineRequest();
        req.setTargetType("idle");

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(idleItemRepository.save(any(IdleItem.class))).thenReturn(idleItem);
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        adminService.removeContent(adminId, itemId, req);

        // 断言
        assertThat(idleItem.getDelistReason()).isEqualTo("违规");
    }

    @Test
    @DisplayName("删除内容 - 不支持的目标类型抛出异常")
    void should_throwException_when_unsupportedTargetType() {
        // 准备：removeContent 先校验管理员身份（requireNotSuperAdmin），再校验目标类型
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        ContentOfflineRequest req = new ContentOfflineRequest();
        req.setTargetType("unknown");

        // 执行 & 断言
        assertThatThrownBy(() -> adminService.removeContent(adminId, itemId, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("不支持的目标类型");
    }

    // ==================== proxyPublishIdle ====================

    @Test
    @DisplayName("代发闲置 - 正常代发闲置物品")
    void should_proxyPublishIdle_when_validInput() {
        // 准备
        IdleItemRequest req = new IdleItemRequest();
        req.setUserId(userId);
        req.setTitle("代发物品");
        req.setDescription("测试代发");
        req.setPostType(PostType.LEND);
        req.setCategory("数码");
        req.setPrice(java.math.BigDecimal.TEN);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.save(any(IdleItem.class))).thenAnswer(inv -> {
            IdleItem item = inv.getArgument(0);
            item.setId(itemId);
            return item;
        });
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        IdleItemDTO result = adminService.proxyPublishIdle(adminId, req);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("代发物品");
        assertThat(result.getStatus()).isEqualTo("online");
        verify(operationLogRepository).save(any(OperationLog.class));
    }

    @Test
    @DisplayName("代发闲置 - userId为null时使用adminId")
    void should_useAdminId_when_userIdNull() {
        // 准备
        IdleItemRequest req = new IdleItemRequest();
        req.setTitle("管理员代发");
        req.setDescription("测试");
        req.setPostType(PostType.LEND);
        req.setCategory("数码");

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.save(any(IdleItem.class))).thenAnswer(inv -> {
            IdleItem item = inv.getArgument(0);
            item.setId(itemId);
            return item;
        });
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        IdleItemDTO result = adminService.proxyPublishIdle(adminId, req);

        // 断言
        assertThat(result.getUserId()).isEqualTo(adminId);
    }

    // ==================== proxyPublishHelp ====================

    @Test
    @DisplayName("代发求助 - 正常代发求助")
    void should_proxyPublishHelp_when_validInput() {
        // 准备
        HelpRequestDTO req = new HelpRequestDTO();
        req.setUserId(userId);
        req.setTitle("代发求助");
        req.setDescription("测试代发求助");
        req.setCategory("搬家");
        req.setIsUrgent(true);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(helpRequestRepository.save(any(HelpRequest.class))).thenAnswer(inv -> {
            HelpRequest hr = inv.getArgument(0);
            hr.setId(helpId);
            return hr;
        });
        when(operationLogRepository.save(any(OperationLog.class))).thenReturn(new OperationLog());

        // 执行
        HelpResponseDTO result = adminService.proxyPublishHelp(adminId, req);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("代发求助");
        assertThat(result.getStatus()).isEqualTo("online");
        assertThat(result.getIsUrgent()).isTrue();
        verify(operationLogRepository).save(any(OperationLog.class));
    }

    // ==================== getRecords ====================

    @Test
    @DisplayName("获取记录 - borrow类型返回借入记录")
    void should_returnBorrowRecords_when_typeBorrow() {
        // 准备
        BorrowRequest br = BorrowRequest.builder()
                .id(300L)
                .idleId(itemId)
                .borrowerId(userId)
                .status(BizStatus.RETURNED)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(borrowRequestRepository.findByStatus("returned")).thenReturn(List.of(br));
        when(idleItemRepository.findById(itemId)).thenReturn(Optional.of(idleItem));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        List<RecordItemDTO> result = adminService.getRecords(adminId, ActivityRole.BORROW);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo(ActivityRole.BORROW);
    }

    @Test
    @DisplayName("获取记录 - 不支持的类型抛出异常")
    void should_throwException_when_unsupportedRecordType() {
        // 准备（先解析管理员所属小区，再校验类型）
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        // 执行 & 断言
        assertThatThrownBy(() -> adminService.getRecords(adminId, "unknown"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("不支持的类型，请使用 borrow、help 或 all");
    }

    // ==================== getOperationLogs ====================

    @Test
    @DisplayName("获取操作日志 - 返回分页日志")
    void should_returnOperationLogs_when_logsExist() {
        // 准备
        OperationLog log = OperationLog.builder()
                .id(400L)
                .adminId(adminId)
                .action("approve_user")
                .targetType("user")
                .targetId(userId)
                .detail("审核通过")
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();

        // 仅超级管理员可查看，且日志按同小区管理员过滤；service 先全量过滤再内存分页
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.findAll()).thenReturn(List.of(admin, user));
        when(operationLogRepository.findAll(any(Sort.class))).thenReturn(List.of(log));

        // 执行
        List<OperationLogDTO> result = adminService.getOperationLogs(adminId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAction()).isEqualTo("approve_user");
        assertThat(result.get(0).getAdminName()).isEqualTo("管理员");
    }

    // ==================== getBuildings ====================

    @Test
    @DisplayName("获取楼栋列表 - 返回管理员所属小区的楼栋")
    void should_returnBuildings_when_called() {
        // 准备
        Building building = Building.builder()
                .id(500L)
                .tenantId(tenantId)
                .buildingNo(3)
                .build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(buildingRepository.findByTenantId(tenantId)).thenReturn(List.of(building));

        // 执行
        List<BuildingDTO> result = adminService.getBuildings(adminId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBuildingNo()).isEqualTo(3);
    }

    // ==================== exportData ====================

    @Test
    @DisplayName("导出数据 - 导出闲置物品为 Excel 字节数组")
    void should_exportExcelBytes_when_exportPosts() {
        // 准备
        ExportRequest req = new ExportRequest();
        req.setOptions(List.of("posts"));
        req.setFormat("xlsx");

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(List.of(idleItem));
        when(helpRequestRepository.findAll()).thenReturn(List.of());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        byte[] result = adminService.exportData(adminId, req);

        // 断言：Excel 字节数组非空
        assertThat(result).isNotNull();
        assertThat(result.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("导出数据 - 多选项导出包含多个 Sheet")
    void should_exportMultipleSheets_when_multipleOptions() {
        // 准备
        ExportRequest req = new ExportRequest();
        req.setOptions(List.of("posts", "borrows"));
        req.setFormat("xlsx");

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(idleItemRepository.findAll()).thenReturn(List.of());
        when(helpRequestRepository.findAll()).thenReturn(List.of());
        // buildBorrowsData 只查已归还的借用记录
        when(borrowRequestRepository.findByStatus(BizStatus.RETURNED)).thenReturn(List.of());

        // 执行
        byte[] result = adminService.exportData(adminId, req);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("导出数据 - 空选项时前端校验（后端至少有一项数据可用）")
    void should_returnNonEmptyBytes_when_exportAllOptions() {
        // 准备
        ExportRequest req = new ExportRequest();
        req.setOptions(List.of("residents", "posts", "borrows", "removals", "ratings"));
        req.setFormat("xlsx");

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.findByTenantIdAndAuthStatusAndUserTypeNotIn(
                anyLong(), anyString(), anyList())).thenReturn(new ArrayList<>());
        when(idleItemRepository.findAll()).thenReturn(List.of());
        when(helpRequestRepository.findAll()).thenReturn(List.of());
        // buildBorrowsData 只查已归还的借用记录
        when(borrowRequestRepository.findByStatus(BizStatus.RETURNED)).thenReturn(List.of());
        when(operationLogRepository.findAll()).thenReturn(List.of());
        when(ratingRepository.findAll()).thenReturn(List.of());

        // 执行
        byte[] result = adminService.exportData(adminId, req);

        // 断言：Excel 文件至少包含表头
        assertThat(result).isNotNull();
        assertThat(result.length).isGreaterThan(0);
    }

    // ==================== requireSuperAdmin（越权口径见类 Javadoc） ====================

    @Test
    @DisplayName("越权 - super_admin 放行")
    void should_pass_when_superAdmin() {
        // 准备
        User superAdmin = User.builder().id(adminId).userType(UserType.SUPER_ADMIN).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(superAdmin));

        // 执行 & 断言：不抛异常即视为放行
        adminService.requireSuperAdmin(adminId);
    }

    @Test
    @DisplayName("越权 - 高级管理员（senior_admin）被拒")
    void should_throw_when_seniorAdmin() {
        // 准备：fixture admin 为 senior_admin
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        // 执行 & 断言
        assertThatThrownBy(() -> adminService.requireSuperAdmin(adminId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("权限不足，仅超级管理员可操作");
    }

    @Test
    @DisplayName("越权 - 普通管理员被拒")
    void should_throw_when_regularAdmin() {
        // 准备
        User regular = User.builder().id(adminId).userType(UserType.ADMIN).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(regular));

        // 执行 & 断言
        assertThatThrownBy(() -> adminService.requireSuperAdmin(adminId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("权限不足，仅超级管理员可操作");
    }

    @Test
    @DisplayName("越权 - 账号不存在抛异常")
    void should_throw_when_adminNotFound() {
        // 准备
        when(userRepository.findById(adminId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> adminService.requireSuperAdmin(adminId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("管理员不存在");
    }

    @Test
    @DisplayName("楼栋筛选 - 按数值楼栋号/单元号精确匹配住户 ID")
    void should_resolveBuildingUserIds_byNumericMatch() {
        Building building = Building.builder().id(1L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(2L).buildingId(1L).unitNo(1).build();
        Room room = Room.builder().id(3L).unitId(2L).roomNumber("1502").build();
        User user = User.builder().id(5L).roomId(3L).build();
        when(buildingRepository.findAll()).thenReturn(List.of(building));
        when(unitRepository.findByBuildingId(1L)).thenReturn(List.of(unit));
        when(roomRepository.findByUnitId(2L)).thenReturn(List.of(room));
        when(userRepository.findByRoomIdIn(List.of(3L))).thenReturn(List.of(user));

        List<Long> ids = ReflectionTestUtils.invokeMethod(
                adminService, "resolveBuildingUserIds", 3, 1);

        assertThat(ids).containsExactly(5L);
    }
}
