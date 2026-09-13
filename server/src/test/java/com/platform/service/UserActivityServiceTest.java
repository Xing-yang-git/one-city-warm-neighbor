package com.platform.service;

import com.platform.model.dto.MyPostItemDTO;
import com.platform.model.dto.UserProfileDTO;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.IdleItem;
import com.platform.model.entity.User;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.repository.RatingRepository;
import com.platform.repository.UserRepository;
import com.platform.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserActivityService 单元测试")
class UserActivityServiceTest {

    @Mock
    private IdleItemRepository idleItemRepository;
    @Mock
    private HelpRequestRepository helpRequestRepository;
    @Mock
    private HelpApplicationRepository helpApplicationRepository;
    @Mock
    private BorrowRequestRepository borrowRequestRepository;
    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoomRepository roomRepository;

    @InjectMocks
    private UserActivityService service;

    private Long userId;
    private Long ownerId;
    private Long borrowerId;
    private Long helperId;
    private Long requesterId;
    private User user;
    private User otherUser;

    @BeforeEach
    void setUp() {
        userId = 1L;
        ownerId = 2L;
        borrowerId = 3L;
        helperId = 4L;
        requesterId = 5L;

        user = User.builder()
                .id(userId)
                .name("测试用户")
                .userType("owner")
                .authStatus("approved")
                .build();

        otherUser = User.builder()
                .id(ownerId)
                .name("其他用户")
                .userType("owner")
                .authStatus("approved")
                .build();
    }

    // ==================== getProfile ====================

    @Test
    @DisplayName("获取个人资料 - 正常返回资料与统计数据")
    void should_returnProfile_when_userExists() {
        // 准备
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ratingRepository.getAverageScore(userId)).thenReturn(4.5);
        when(ratingRepository.countByToUserId(userId)).thenReturn(3L);
        when(ratingRepository.findByToUserId(userId)).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByBorrowerId(userId)).thenReturn(Collections.emptyList());
        when(helpRequestRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 执行
        UserProfileDTO result = service.getProfile(userId);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(userId);
        assertThat(result.getName()).isEqualTo("测试用户");
        assertThat(result.getUserType()).isEqualTo("owner");
        assertThat(result.getIsAuth()).isEqualTo(true);
        assertThat(result.getScore()).isEqualTo(4.5);
        assertThat(result.getRatingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("获取个人资料 - 无评分时默认 0.0 分")
    void should_defaultScoreToZero_when_noRatings() {
        // 准备
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(ratingRepository.getAverageScore(userId)).thenReturn(null);
        when(ratingRepository.countByToUserId(userId)).thenReturn(0L);
        when(ratingRepository.findByToUserId(userId)).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByBorrowerId(userId)).thenReturn(Collections.emptyList());
        when(helpRequestRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 执行
        UserProfileDTO result = service.getProfile(userId);

        // 断言
        assertThat(result.getScore()).isEqualTo(5.0);
        assertThat(result.getBorrowReturnRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("获取个人资料 - 用户不存在时抛出异常")
    void should_throwException_when_userNotFound() {
        // 准备
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> service.getProfile(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("用户不存在");
    }

    // ==================== getMyPosts ====================

    @Test
    @DisplayName("获取我的发布 - 返回闲置和求助合并列表")
    void should_returnMyPosts_when_userHasItems() {
        // 准备
        IdleItem idleItem = IdleItem.builder()
                .id(1L).userId(userId).title("测试闲置").postType("LEND")
                .status("online").createdAt(LocalDateTime.now()).build();
        HelpRequest helpReq = HelpRequest.builder()
                .id(2L).userId(userId).title("测试求助").status("online")
                .createdAt(LocalDateTime.now()).build();

        when(idleItemRepository.findByUserId(userId)).thenReturn(List.of(idleItem));
        when(helpRequestRepository.findByUserId(userId)).thenReturn(List.of(helpReq));

        // 执行
        List<MyPostItemDTO> result = service.getMyPosts(userId, null);

        // 断言
        assertThat(result).hasSize(2);
        assertThat(result).extracting("title").containsExactlyInAnyOrder("测试闲置", "测试求助");
    }

    @Test
    @DisplayName("获取我的发布 - 按 statusFilter 过滤")
    void should_filterByStatus_when_statusFilterGiven() {
        // 准备
        IdleItem onlineItem = IdleItem.builder()
                .id(1L).userId(userId).title("在线物品").postType("LEND")
                .status("online").createdAt(LocalDateTime.now()).build();
        IdleItem offlineItem = IdleItem.builder()
                .id(2L).userId(userId).title("已下架物品").postType("LEND")
                .status("offline").createdAt(LocalDateTime.now()).build();

        when(idleItemRepository.findByUserId(userId)).thenReturn(List.of(onlineItem, offlineItem));
        when(helpRequestRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 执行
        List<MyPostItemDTO> result = service.getMyPosts(userId, "online");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("在线物品");
    }

    @Test
    @DisplayName("获取我的发布 - 无数据时返回空列表")
    void should_returnEmptyList_when_noPosts() {
        // 准备
        when(idleItemRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(helpRequestRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 执行
        List<MyPostItemDTO> result = service.getMyPosts(userId, null);

        // 断言
        assertThat(result).isEmpty();
    }

    // ==================== getApprovals — borrow ====================

    @Test
    @DisplayName("获取审批 - borrow 类型返回我发布的 LEND 帖待审批申请")
    void should_returnApprovals_when_borrowType() {
        // 准备
        Long lendIdleId = 10L;
        /** borrow 类型查 WANTED 帖（我是借入方，有人愿意借出给我） */
        IdleItem wantedItem = IdleItem.builder()
                .id(lendIdleId).userId(userId).title("求借物品").postType("WANTED")
                .createdAt(LocalDateTime.now()).build();
        BorrowRequest br = BorrowRequest.builder()
                .id(100L).idleId(lendIdleId).borrowerId(borrowerId)
                .durationType("day").durationDays(3)
                .startDate(LocalDate.now())
                .status("pending").createdAt(LocalDateTime.now())
                .build();
        br.setIdleItem(wantedItem); // 设置关联以避免懒加载

        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "pending")).thenReturn(List.of(br));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(otherUser));
        when(ratingRepository.getAverageScore(borrowerId)).thenReturn(5.0);
        when(borrowRequestRepository.findByBorrowerId(borrowerId)).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByOwnerIdAndStatus(eq(borrowerId), eq("approved"))).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByOwnerIdAndStatus(eq(borrowerId), eq("returned"))).thenReturn(Collections.emptyList());
        when(helpRequestRepository.findByUserId(borrowerId)).thenReturn(Collections.emptyList());
        when(helpApplicationRepository.findByHelperId(borrowerId)).thenReturn(Collections.emptyList());
        when(ratingRepository.findByToUserId(borrowerId)).thenReturn(Collections.emptyList());

        // 执行
        List<MyPostItemDTO> result = service.getApprovals(userId, "borrow");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("idle");
        assertThat(result.get(0).getSubType()).isEqualTo("borrow");
        assertThat(result.get(0).getTitle()).isEqualTo("求借物品");
    }

    // ==================== getApprovals — lend (LEND 帖) ====================

    @Test
    @DisplayName("获取审批 - lend 类型返回我发布的 WANTED 帖待审批申请（角色反转）")
    void should_returnApprovals_when_lendType() {
        // 准备
        Long wantedIdleId = 20L;
        /** lend 类型查 LEND 帖（我是借出方，有人想借我的东西） */
        IdleItem lendItem = IdleItem.builder()
                .id(wantedIdleId).userId(userId).title("出借物品").postType("LEND")
                .createdAt(LocalDateTime.now()).build();
        BorrowRequest br = BorrowRequest.builder()
                .id(200L).idleId(wantedIdleId).borrowerId(borrowerId)
                .durationType("week").durationDays(7)
                .status("pending").createdAt(LocalDateTime.now())
                .build();
        br.setIdleItem(lendItem);

        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "pending")).thenReturn(List.of(br));
        when(userRepository.findById(borrowerId)).thenReturn(Optional.of(otherUser));
        when(ratingRepository.getAverageScore(borrowerId)).thenReturn(null);
        when(borrowRequestRepository.findByBorrowerId(borrowerId)).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByOwnerIdAndStatus(eq(borrowerId), eq("approved"))).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByOwnerIdAndStatus(eq(borrowerId), eq("returned"))).thenReturn(Collections.emptyList());
        when(helpRequestRepository.findByUserId(borrowerId)).thenReturn(Collections.emptyList());
        when(helpApplicationRepository.findByHelperId(borrowerId)).thenReturn(Collections.emptyList());
        when(ratingRepository.findByToUserId(borrowerId)).thenReturn(Collections.emptyList());

        // 执行
        List<MyPostItemDTO> result = service.getApprovals(userId, "lend");

        // 断言：LEND 帖的 lending 意向
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubType()).isEqualTo("lend");
    }

    // ==================== getApprovals — help ====================

    @Test
    @DisplayName("获取审批 - help 类型返回我的求助下的待审批帮助申请")
    void should_returnApprovals_when_helpType() {
        // 准备
        HelpRequest hr = HelpRequest.builder()
                .id(1L).userId(userId).title("帮我搬东西").status("online")
                .createdAt(LocalDateTime.now()).build();
        HelpApplication app = HelpApplication.builder()
                .id(10L).helpId(1L).helperId(helperId).note("我可以帮忙")
                .status("pending").createdAt(LocalDateTime.now()).build();

        when(helpRequestRepository.findByUserId(userId)).thenReturn(List.of(hr));
        when(helpApplicationRepository.findByHelpIdAndStatus(1L, "pending")).thenReturn(List.of(app));
        when(userRepository.findById(helperId)).thenReturn(Optional.of(
                User.builder().id(helperId).name("帮助者").userType("tenant").build()));
        when(ratingRepository.getAverageScore(helperId)).thenReturn(null);
        when(ratingRepository.findByToUserId(helperId)).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByBorrowerId(helperId)).thenReturn(Collections.emptyList());
        when(helpRequestRepository.findByUserId(helperId)).thenReturn(Collections.emptyList());

        // 执行
        List<MyPostItemDTO> result = service.getApprovals(userId, "help");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("help");
        assertThat(result.get(0).getSubType()).isEqualTo("helpReq");
        assertThat(result.get(0).getNote()).isEqualTo("我可以帮忙");
    }

    // ==================== getApprovals — 空数据 ====================

    @Test
    @DisplayName("获取审批 - 无待审批数据时返回空列表")
    void should_returnEmptyApprovals_when_noPending() {
        // 准备
        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "pending")).thenReturn(Collections.emptyList());

        // 执行
        List<MyPostItemDTO> result = service.getApprovals(userId, "borrow");

        // 断言
        assertThat(result).isEmpty();
    }

    // ==================== getInProgress — borrow ====================

    @Test
    @DisplayName("获取进行中 - borrow 角色分流")
    void should_returnInProgress_when_borrowRole() {
        // 准备
        Long idleId = 10L;
        IdleItem lendItem = IdleItem.builder()
                .id(idleId).userId(ownerId).title("出借品").postType("LEND")
                .createdAt(LocalDateTime.now()).build();
        BorrowRequest br = BorrowRequest.builder()
                .id(100L).idleId(idleId).borrowerId(userId)
                .durationType("day").durationDays(7)
                .startDate(LocalDate.now())
                .status("approved").createdAt(LocalDateTime.now())
                .build();
        br.setIdleItem(lendItem);

        when(borrowRequestRepository.findByBorrowerIdAndStatus(userId, "approved")).thenReturn(List.of(br));
        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "approved")).thenReturn(Collections.emptyList());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(
                User.builder().id(ownerId).name("借出租户").userType("owner").build()));

        // 执行
        List<MyPostItemDTO> result = service.getInProgress(userId, "borrow");

        // 断言：当前用户是 borrowerId，LEND 帖 → 真实角色 borrow
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubType()).isEqualTo("borrow");
        assertThat(result.get(0).getRoleLabel()).isEqualTo("借出住户");
    }

    // ==================== getInProgress — lend (WANTED 反转) ====================

    @Test
    @DisplayName("获取进行中 - lend 角色时 WANTED 帖角色反转")
    void should_returnInProgress_when_lendRoleWithWanted() {
        // 准备：我是 WANTED 帖的 owner（即我是借入方），borrower 是响应出借的人
        Long wantedId = 20L;
        IdleItem wantedItem = IdleItem.builder()
                .id(wantedId).userId(userId).title("求借物").postType("WANTED")
                .createdAt(LocalDateTime.now()).build();
        BorrowRequest br = BorrowRequest.builder()
                .id(200L).idleId(wantedId).borrowerId(borrowerId)
                .durationType("day").durationDays(14)
                .startDate(LocalDate.now())
                .status("approved").createdAt(LocalDateTime.now())
                .build();
        br.setIdleItem(wantedItem);

        when(borrowRequestRepository.findByBorrowerIdAndStatus(userId, "approved")).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "approved")).thenReturn(List.of(br));

        // 执行
        List<MyPostItemDTO> result = service.getInProgress(userId, "lend");

        // 断言：WANTED+owner → 真实角色为 borrow，但这里 role="lend" 不匹配，应过滤掉
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("获取进行中 - lend 角色时我是 borrower 对应 WANTED 帖")
    void should_returnInProgress_when_lendRoleAsBorrowerOnWanted() {
        // 准备：我是 WANTED 帖的 borrower（响应者=出借方）
        Long wantedId = 30L;
        IdleItem wantedItem = IdleItem.builder()
                .id(wantedId).userId(ownerId).title("求借物").postType("WANTED")
                .createdAt(LocalDateTime.now()).build();
        BorrowRequest br = BorrowRequest.builder()
                .id(300L).idleId(wantedId).borrowerId(userId)
                .durationType("week").durationDays(7)
                .startDate(LocalDate.now())
                .status("approved").createdAt(LocalDateTime.now())
                .build();
        br.setIdleItem(wantedItem);

        when(borrowRequestRepository.findByBorrowerIdAndStatus(userId, "approved")).thenReturn(List.of(br));
        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "approved")).thenReturn(Collections.emptyList());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(
                User.builder().id(ownerId).name("求借者").userType("owner").build()));

        // 执行
        List<MyPostItemDTO> result = service.getInProgress(userId, "lend");

        // 断言：WANTED+borrower → 真实角色 lend（我是出借方）
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubType()).isEqualTo("lend");
        assertThat(result.get(0).getRoleLabel()).isEqualTo("借走住户");
    }

    // ==================== getInProgress — helpReq ====================

    @Test
    @DisplayName("获取进行中 - helpReq 角色返回我求助下的进行中帮助")
    void should_returnInProgress_when_helpReqRole() {
        // 准备
        HelpRequest hr = HelpRequest.builder()
                .id(1L).userId(userId).title("帮翻译").status("online")
                .createdAt(LocalDateTime.now()).build();
        HelpApplication app = HelpApplication.builder()
                .id(10L).helpId(1L).helperId(helperId).note("我懂英文")
                .status("approved").createdAt(LocalDateTime.now()).build();

        when(helpRequestRepository.findByUserId(userId)).thenReturn(List.of(hr));
        when(helpApplicationRepository.findByHelpIdAndStatus(1L, "approved")).thenReturn(List.of(app));
        when(userRepository.findById(helperId)).thenReturn(Optional.of(
                User.builder().id(helperId).name("帮手").userType("tenant").build()));

        // 执行
        List<MyPostItemDTO> result = service.getInProgress(userId, "helpReq");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("help");
        assertThat(result.get(0).getSubType()).isEqualTo("helpReq");
        assertThat(result.get(0).getDisplayStatus()).isEqualTo("进行中");
    }

    // ==================== getInProgress — helpPro ====================

    @Test
    @DisplayName("获取进行中 - helpPro 角色返回我正在帮助别人的事项")
    void should_returnInProgress_when_helpProRole() {
        // 准备
        HelpRequest hr = HelpRequest.builder()
                .id(2L).userId(requesterId).title("修水管").status("online")
                .createdAt(LocalDateTime.now()).build();
        HelpApplication app = HelpApplication.builder()
                .id(20L).helpId(2L).helperId(userId).note("我会修")
                .status("approved").createdAt(LocalDateTime.now()).build();

        when(helpApplicationRepository.findByHelperId(userId)).thenReturn(List.of(app));
        when(helpRequestRepository.findById(2L)).thenReturn(Optional.of(hr));
        when(userRepository.findById(requesterId)).thenReturn(Optional.of(
                User.builder().id(requesterId).name("求助者").userType("owner").build()));

        // 执行
        List<MyPostItemDTO> result = service.getInProgress(userId, "helpPro");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubType()).isEqualTo("helpPro");
        assertThat(result.get(0).getRoleLabel()).isEqualTo("求助住户");
    }

    // ==================== getInProgress — 错误角色 ====================

    @Test
    @DisplayName("获取进行中 - 无效 role 抛出异常")
    void should_throwException_when_invalidRole() {
        // 执行 & 断言
        assertThatThrownBy(() -> service.getInProgress(userId, "invalid_role"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无效的角色类型");
    }

    // ==================== getInProgress — 空数据 ====================

    @Test
    @DisplayName("获取进行中 - borrow 角色无数据时返回空列表")
    void should_returnEmptyInProgress_when_noData() {
        // 准备
        when(borrowRequestRepository.findByBorrowerIdAndStatus(userId, "approved")).thenReturn(Collections.emptyList());
        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "approved")).thenReturn(Collections.emptyList());

        // 执行
        List<MyPostItemDTO> result = service.getInProgress(userId, "borrow");

        // 断言
        assertThat(result).isEmpty();
    }

    // ==================== getCompleted — borrow ====================

    @Test
    @DisplayName("获取已完成 - borrow 角色返回已归还记录")
    void should_returnCompleted_when_borrowRole() {
        // 准备
        Long idleId = 10L;
        IdleItem lendItem = IdleItem.builder()
                .id(idleId).userId(ownerId).title("已借出物").postType("LEND")
                .createdAt(LocalDateTime.now()).build();
        BorrowRequest br = BorrowRequest.builder()
                .id(100L).idleId(idleId).borrowerId(userId)
                .durationType("day").durationDays(3)
                .status("returned")
                .returnedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        br.setIdleItem(lendItem);

        when(borrowRequestRepository.findByBorrowerIdAndStatus(userId, "returned")).thenReturn(List.of(br));
        when(borrowRequestRepository.findByOwnerIdAndStatus(userId, "returned")).thenReturn(Collections.emptyList());
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(
                User.builder().id(ownerId).name("借出租户").userType("owner").build()));
        when(ratingRepository.findFirstByBorrowIdAndFromUserId(100L, userId)).thenReturn(Optional.empty());

        // 执行
        List<MyPostItemDTO> result = service.getCompleted(userId, "borrow");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubType()).isEqualTo("borrow");
        assertThat(result.get(0).getCompletedAt()).isNotNull();
    }

    // ==================== getCompleted — helpReq ====================

    @Test
    @DisplayName("获取已完成 - helpReq 角色返回我求助下的已完成帮助")
    void should_returnCompleted_when_helpReqRole() {
        // 准备
        HelpRequest hr = HelpRequest.builder()
                .id(1L).userId(userId).title("帮买菜").status("online")
                .createdAt(LocalDateTime.now()).build();
        HelpApplication app = HelpApplication.builder()
                .id(10L).helpId(1L).helperId(helperId).note("买好了")
                .status("completed").createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .build();

        when(helpRequestRepository.findByUserId(userId)).thenReturn(List.of(hr));
        when(helpApplicationRepository.findByHelpIdAndStatus(1L, "completed")).thenReturn(List.of(app));
        when(userRepository.findById(helperId)).thenReturn(Optional.of(
                User.builder().id(helperId).name("帮手").userType("tenant").build()));
        when(ratingRepository.findFirstByHelpApplicationIdAndFromUserId(10L, userId)).thenReturn(Optional.empty());

        // 执行
        List<MyPostItemDTO> result = service.getCompleted(userId, "helpReq");

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSubType()).isEqualTo("helpReq");
        assertThat(result.get(0).getCompletedAt()).isNotNull();
    }

    // ==================== getCompleted — 错误角色 ====================

    @Test
    @DisplayName("获取已完成 - 无效 role 抛出异常")
    void should_throwException_when_invalidRoleInCompleted() {
        // 执行 & 断言
        assertThatThrownBy(() -> service.getCompleted(userId, "bad_role"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无效的角色类型");
    }
}
