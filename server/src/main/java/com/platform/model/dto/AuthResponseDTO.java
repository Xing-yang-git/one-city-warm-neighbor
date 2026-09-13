package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录/注册响应 DTO — 微信登录、管理端登录、手机号登录、注册共用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponseDTO {
    /** JWT 访问令牌 */
    private String token;
    /** 登录用户信息 */
    private UserDTO user;
    /** 是否需引导补充注册资料（微信登录首登时为 true，其余场景为 null） */
    private Boolean needRegister;
}
