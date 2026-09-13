package com.platform.controller;

import com.platform.common.Result;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.NotificationDTO;
import com.platform.model.dto.UnreadCountDTO;
import com.platform.security.LoginUser;
import com.platform.service.NotificationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 系统通知 REST API — 获取通知列表、未读数、标记已读、清空通知。
 *
 * <p>各类业务事件（借用申请/审批、帮助申请/处理、审核结果、违规处理等）
 * 会生成通知推送给目标用户。通过 isRead 标记已读状态。</p>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 获取当前用户的全部通知列表（按时间倒序）。
     *
     * @param auth 当前认证用户
     * @return 通知列表（含已读/未读状态）
     */
    @GetMapping
    public Result<ListDTO<NotificationDTO>> getNotifications(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(notificationService.getNotifications(userId)));
    }

    /**
     * 获取当前用户的未读通知计数。
     *
     * @param auth 当前认证用户
     * @return 未读通知数量
     */
    @GetMapping("/unread-count")
    public Result<UnreadCountDTO> getUnreadCount(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new UnreadCountDTO(notificationService.getUnreadCount(userId)));
    }

    /**
     * 一键标记当前用户全部通知为已读。
     *
     * @param auth 当前认证用户
     * @return 空响应
     */
    @PutMapping("/read-all")
    public Result<Void> markAllRead(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        notificationService.markAllRead(userId);
        return Result.ok();
    }

    /**
     * 清空当前用户全部通知（物理删除）。
     *
     * @param auth 当前认证用户
     * @return 空响应
     */
    @DeleteMapping("/all")
    public Result<Void> deleteAll(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        notificationService.deleteAll(userId);
        return Result.ok();
    }
}
