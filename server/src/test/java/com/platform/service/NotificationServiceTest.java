package com.platform.service;

import com.platform.common.AppTimeZone;
import com.platform.model.dto.NotificationDTO;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.Notification;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 单元测试")
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private BorrowRequestRepository borrowRequestRepository;

    @Mock
    private HelpApplicationRepository helpApplicationRepository;

    @InjectMocks
    private NotificationService notificationService;

    private Long userId;
    private Long relatedId;
    private Notification notification;

    @BeforeEach
    void setUp() {
        userId = 1L;
        relatedId = 100L;

        notification = Notification.builder()
                .id(1000L)
                .userId(userId)
                .type("system")
                .title("测试通知")
                .content("这是一条测试通知")
                .relatedId(relatedId)
                .isRead(false)
                .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE))
                .build();
    }

    @Test
    @DisplayName("获取通知列表 - 正常返回用户的通知列表")
    void should_returnNotifications_when_userHasNotifications() {
        // 准备
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(notification));

        // 执行
        List<NotificationDTO> result = notificationService.getNotifications(userId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("测试通知");
        assertThat(result.get(0).getContent()).isEqualTo("这是一条测试通知");
        assertThat(result.get(0).getType()).isEqualTo("system");
        assertThat(result.get(0).getRelatedId()).isEqualTo(relatedId);
        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Test
    @DisplayName("获取通知列表 - 用户没有通知时返回空列表")
    void should_returnEmptyList_when_userHasNoNotifications() {
        // 准备
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(Collections.emptyList());

        // 执行
        List<NotificationDTO> result = notificationService.getNotifications(userId);

        // 断言
        assertThat(result).isEmpty();
        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Test
    @DisplayName("获取未读数量 - 返回正确的未读计数")
    void should_returnUnreadCount_when_userHasUnreadNotifications() {
        // 准备
        when(notificationRepository.countByUserIdAndIsReadFalse(userId)).thenReturn(5L);

        // 执行
        long count = notificationService.getUnreadCount(userId);

        // 断言
        assertThat(count).isEqualTo(5L);
        verify(notificationRepository).countByUserIdAndIsReadFalse(userId);
    }

    @Test
    @DisplayName("获取未读数量 - 用户没有未读通知时返回0")
    void should_returnZero_when_userHasNoUnreadNotifications() {
        // 准备
        when(notificationRepository.countByUserIdAndIsReadFalse(userId)).thenReturn(0L);

        // 执行
        long count = notificationService.getUnreadCount(userId);

        // 断言
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("全部标为已读 - 调用Repository的markAllRead方法")
    void should_markAllRead_when_called() {
        // 准备
        doNothing().when(notificationRepository).markAllRead(userId);

        // 执行
        notificationService.markAllRead(userId);

        // 断言
        verify(notificationRepository).markAllRead(userId);
    }

    @Test
    @DisplayName("创建通知 - 正常创建并返回DTO")
    void should_createNotification_when_validInput() {
        // 准备
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            n.setId(2000L);
            return n;
        });

        // 执行
        NotificationDTO result = notificationService.create(userId, "system", "新通知", "内容", relatedId);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("新通知");
        assertThat(result.getContent()).isEqualTo("内容");
        assertThat(result.getType()).isEqualTo("system");
        assertThat(result.getRelatedId()).isEqualTo(relatedId);
        assertThat(result.getIsRead()).isFalse();
        assertThat(result.getCreatedAt()).isNotNull();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getIsRead()).isFalse();
    }

    // ── computeActionable 新类型测试（borrow_application / help_application_submitted） ──

    @Test
    @DisplayName("borrow_application 待审批 → actionable=true")
    void should_actionableTrue_when_borrowApplicationPending() {
        // 准备：借入申请仍为 PENDING 状态
        when(borrowRequestRepository.existsByBorrowerIdAndIdleIdAndStatus(userId, relatedId, "pending"))
                .thenReturn(true);
        notification.setType("borrow_application");
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(notification));

        // 执行
        List<NotificationDTO> result = notificationService.getNotifications(userId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActionable()).isTrue();
    }

    @Test
    @DisplayName("borrow_application 已审批 → actionable=false")
    void should_actionableFalse_when_borrowApplicationNotPending() {
        // 准备：借入申请已不再是 PENDING
        when(borrowRequestRepository.existsByBorrowerIdAndIdleIdAndStatus(userId, relatedId, "pending"))
                .thenReturn(false);
        notification.setType("borrow_application");
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(notification));

        // 执行
        List<NotificationDTO> result = notificationService.getNotifications(userId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActionable()).isFalse();
    }

    @Test
    @DisplayName("help_application_submitted 待审批 → actionable=true")
    void should_actionableTrue_when_helpApplicationSubmittedPending() {
        // 准备：帮助申请仍为 PENDING 状态
        when(helpApplicationRepository.existsByHelperIdAndHelpIdAndStatus(userId, relatedId, "pending"))
                .thenReturn(true);
        notification.setType("help_application_submitted");
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(notification));

        // 执行
        List<NotificationDTO> result = notificationService.getNotifications(userId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActionable()).isTrue();
    }

    @Test
    @DisplayName("help_application_submitted 已审批 → actionable=false")
    void should_actionableFalse_when_helpApplicationSubmittedNotPending() {
        // 准备：帮助申请已不再是 PENDING
        when(helpApplicationRepository.existsByHelperIdAndHelpIdAndStatus(userId, relatedId, "pending"))
                .thenReturn(false);
        notification.setType("help_application_submitted");
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(notification));

        // 执行
        List<NotificationDTO> result = notificationService.getNotifications(userId);

        // 断言
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActionable()).isFalse();
    }
}
