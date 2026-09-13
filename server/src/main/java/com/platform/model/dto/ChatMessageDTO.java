package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天消息 DTO — 会话历史中的单条消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {
    /** 消息 ID */
    private Long id;
    /** 会话 ID */
    private String sessionId;
    /** 发送方用户 ID */
    private Long fromUserId;
    /** 接收方用户 ID */
    private Long toUserId;
    /** 消息内容（撤回后为 null） */
    private String content;
    /** 消息类型：text / voice / image / system */
    private String messageType;
    /** 消息状态：sent / delivered / read */
    private String status;
    /** 撤回时间（未撤回为 null） */
    private String recalledAt;
    /** 创建时间（字符串） */
    private String createdAt;
}
