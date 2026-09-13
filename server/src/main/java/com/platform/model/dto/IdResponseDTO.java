package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用 ID 响应 DTO — 操作类接口返回被操作对象 ID（如知识库文档删除/重试）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdResponseDTO {
    /** 被操作对象的 ID */
    private Long id;
}
