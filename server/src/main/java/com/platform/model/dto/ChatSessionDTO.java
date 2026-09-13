package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天会话摘要 DTO — 消息列表页的会话条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionDTO {
    /** 会话 ID */
    private String sessionId;
    /** 对方用户 ID（字符串） */
    private String otherUserId;
    /** 对方用户姓名 */
    private String otherUserName;
    /** 最后一条消息内容 */
    private String lastMessage;
    /** 最后一条消息类型：text / voice / image 等 */
    private String lastMessageType;
    /** 最后一条消息时间（字符串） */
    private String lastTime;
}
