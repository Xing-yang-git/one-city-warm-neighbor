package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户认证状态 DTO — 对应 /api/auth/status 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthStatusDTO {
    /** 认证状态：pending(待审核) / approved(已通过) / rejected(已驳回) / registering(注册中) */
    private String authStatus;
    /** 驳回原因（被驳回时填充） */
    private String rejectReason;
    /** 封禁原因（被封禁时填充） */
    private String bannedReason;
}
