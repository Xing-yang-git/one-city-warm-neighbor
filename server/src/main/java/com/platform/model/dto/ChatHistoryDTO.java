package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天会话历史 DTO — 游标分页响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryDTO {
    /** 消息列表（倒序，最新在前） */
    private List<ChatMessageDTO> messages;
    /** 是否还有更早的消息 */
    private Boolean hasMore;
    /** 本页最旧消息 ID（翻页游标，无消息为 null） */
    private Long oldestId;
}
