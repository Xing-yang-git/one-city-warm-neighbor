package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员个人信息更新响应 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponseDTO {
    /** 更新后的管理员姓名 */
    private String name;
    /** 管理员类型：admin / senior_admin / super_admin */
    private String userType;
}
