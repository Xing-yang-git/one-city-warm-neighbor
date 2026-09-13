package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员账号 DTO — 管理员列表 / 创建管理员响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminDTO {
    /** 管理员用户 ID */
    private Long id;
    /** 管理员姓名 */
    private String name;
    /** 管理员类型：admin / senior_admin / super_admin */
    private String userType;
    /** 管理员类型中文标签（普通管理员/高级管理员/超级管理员） */
    private String userTypeLabel;
    /** 所属小区名（super_admin 显示"全部小区"） */
    private String tenantName;
    /** 手机号（已脱敏） */
    private String phone;
    /** 创建时间 */
    private LocalDateTime createdAt;
}
