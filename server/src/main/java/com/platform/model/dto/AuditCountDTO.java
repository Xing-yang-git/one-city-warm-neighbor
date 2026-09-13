package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 住户审核各状态计数 DTO — 对应 /api/admin/audits/counts 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditCountDTO {
    /** 待审核住户数 */
    private Long pending;
    /** 已通过住户数（不含管理员） */
    private Long approved;
    /** 已驳回住户数 */
    private Long rejected;
    /** 全部住户数（排除注册中状态） */
    private Long all;
}
