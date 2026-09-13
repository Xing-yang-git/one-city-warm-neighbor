package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知未读数 DTO — 对应 /api/notifications/unread-count 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnreadCountDTO {
    /** 未读通知数量 */
    private Long count;
}
