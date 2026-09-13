package com.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.AppTimeZone;
import com.platform.common.BizStatus;
import com.platform.common.NotificationType;
import com.platform.model.dto.NotificationDTO;
import com.platform.model.dto.WebSocketMessage;
import com.platform.model.entity.BorrowRequest;
import com.platform.model.entity.HelpApplication;
import com.platform.model.entity.Notification;
import com.platform.repository.BorrowRequestRepository;
import com.platform.repository.HelpApplicationRepository;
import com.platform.repository.NotificationRepository;
import com.platform.repository.RatingRepository;
import com.platform.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final BorrowRequestRepository borrowRequestRepository;
    private final HelpApplicationRepository helpApplicationRepository;
    private final RatingRepository ratingRepository;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotificationService(NotificationRepository notificationRepository,
                               BorrowRequestRepository borrowRequestRepository,
                               HelpApplicationRepository helpApplicationRepository,
                               RatingRepository ratingRepository,
                               ChatWebSocketHandler chatWebSocketHandler) {
        this.notificationRepository = notificationRepository;
        this.borrowRequestRepository = borrowRequestRepository;
        this.helpApplicationRepository = helpApplicationRepository;
        this.ratingRepository = ratingRepository;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    public List<NotificationDTO> getNotifications(Long userId) {
        List<Notification> notifications = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId);

        return notifications.stream()
                .map(n -> toDTO(n, userId))
                .collect(Collectors.toList());
    }

    public long getUnreadCount(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    public void markAllRead(Long userId) {
        notificationRepository.markAllRead(userId);
    }

    /** 删除当前用户全部通知（服务通知清空） */
    public void deleteAll(Long userId) {
        notificationRepository.deleteAllByUserId(userId);
    }

    /** 删除指定用户、指定类型、指定关联ID的旧通知（重复申请时清理上一轮通知，避免旧通知仍显示待回应） */
    public void deleteByUserIdAndTypeAndRelatedId(Long userId, String type, Long relatedId) {
        notificationRepository.deleteByUserIdAndTypeAndRelatedId(userId, type, relatedId);
    }

    public NotificationDTO create(Long userId, String type, String title, String content, Long relatedId) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setRelatedId(relatedId);
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        notification = notificationRepository.save(notification);

        NotificationDTO dto = toDTO(notification, userId);

        // 通过 WebSocket 实时推送给目标用户
        try {
            WebSocketMessage msg = new WebSocketMessage();
            // 此处是 WebSocket 信封类型域（与 ChatService 的 "chat_message"/"chat_recall" 同域），
            // 不是 NotificationType（通知自身类型）——两者仅取值恰好相同，勿改为常量引用
            msg.setType("notification");
            msg.setContent(objectMapper.writeValueAsString(dto));
            chatWebSocketHandler.sendToUser(String.valueOf(userId), msg);
        } catch (Exception e) {
            log.warn("WebSocket 推送通知失败: userId={}, title={}", userId, title, e);
        }

        return dto;
    }

    private NotificationDTO toDTO(Notification notification, Long userId) {
        String type = notification.getType();
        Long relatedId = notification.getRelatedId();
        boolean rateable = computeRateable(type, relatedId, userId);
        boolean actionable = computeActionable(type, relatedId, userId);

        return NotificationDTO.builder()
                .id(notification.getId())
                .type(type)
                .title(notification.getTitle())
                .content(notification.getContent())
                .relatedId(relatedId)
                .isRead(notification.getIsRead())
                .createdAt(notification.getCreatedAt())
                .rateable(rateable)
                .actionable(actionable)
                .build();
    }

    /**
     * 验证通知是否确实可评价：关联记录必须处于已完成状态，且当前用户尚未评分。
     */
    private boolean computeRateable(String type, Long relatedId, Long userId) {
        if (relatedId == null) return false;

        if (NotificationType.RETURN_CONFIRM.equals(type)) {
            Optional<BorrowRequest> brOpt = borrowRequestRepository.findById(relatedId);
            if (brOpt.isEmpty()) return false;
            if (!BizStatus.RETURNED.equals(brOpt.get().getStatus())) return false;
            return ratingRepository.findFirstByBorrowIdAndFromUserId(relatedId, userId).isEmpty();
        }

        if (NotificationType.HELP_RESULT.equals(type)) {
            Optional<HelpApplication> appOpt = helpApplicationRepository.findById(relatedId);
            if (appOpt.isEmpty()) return false;
            if (!BizStatus.COMPLETED.equals(appOpt.get().getStatus())) return false;
            return ratingRepository.findFirstByHelpApplicationIdAndFromUserId(relatedId, userId).isEmpty();
        }

        return false;
    }

    /**
     * 验证通知的预期操作是否仍然有效：
     * - 审批类（borrow_request / help_application）：关联记录仍为待处理状态
     * - 评价类（return_confirm / help_result）：同 rateable
     */
    private boolean computeActionable(String type, Long relatedId, Long userId) {
        if (relatedId == null) return false;

        // 审批类：借入申请 / 借出意向是否仍处于待审批
        if (NotificationType.BORROW_REQUEST.equals(type)) {
            Optional<BorrowRequest> brOpt = borrowRequestRepository.findById(relatedId);
            return brOpt.isPresent() && BizStatus.PENDING.equals(brOpt.get().getStatus());
        }

        // 审批类：帮助申请是否仍处于待审批
        if (NotificationType.HELP_APPLICATION.equals(type)) {
            Optional<HelpApplication> appOpt = helpApplicationRepository.findById(relatedId);
            return appOpt.isPresent() && BizStatus.PENDING.equals(appOpt.get().getStatus());
        }

        // 借入/借出申请（申请人视角，relatedId 为闲置物品 ID）：检查是否有待审批的申请
        if (NotificationType.BORROW_APPLICATION.equals(type)) {
            return borrowRequestRepository
                    .existsByBorrowerIdAndIdleIdAndStatus(userId, relatedId, BizStatus.PENDING);
        }

        // 帮助申请（帮助者视角，relatedId 为求助 ID）：检查是否有待审批的申请
        if (NotificationType.HELP_APPLICATION_SUBMITTED.equals(type)) {
            return helpApplicationRepository
                    .existsByHelperIdAndHelpIdAndStatus(userId, relatedId, BizStatus.PENDING);
        }

        // 供需匹配通知：始终可操作（点击即跳转需求详情页，无需状态校验）
        if (NotificationType.MATCH_DEMAND.equals(type)) {
            return true;
        }

        // 内容审核通过通知：可操作（跳转详情页）
        if (NotificationType.CONTENT_APPROVED.equals(type)) {
            return true;
        }

        // 内容审核驳回通知：不可操作（不跳转，仅告知）
        if (NotificationType.CONTENT_REJECTED.equals(type)) {
            return false;
        }

        // 评价类：同 rateable
        if (NotificationType.RETURN_CONFIRM.equals(type) || NotificationType.HELP_RESULT.equals(type)) {
            return computeRateable(type, relatedId, userId);
        }

        return false;
    }
}
