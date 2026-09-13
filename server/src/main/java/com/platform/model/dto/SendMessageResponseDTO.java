package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送聊天消息响应 DTO — 返回落库后的消息 ID 供客户端替换乐观更新的临时 ID。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageResponseDTO {
    /** 消息数据库 ID */
    private Long id;
    /** 消息创建时间（字符串） */
    private String createdAt;
}
