package com.platform.security;

/**
 * 登录用户主体 — 作为 Spring Security {@code Authentication.getPrincipal()} 的载体。
 *
 * <p>取代把用户 ID 直接塞进 {@code auth.getName()} 的做法：getName() 的语义是「用户名」，
 * 存放 ID 属滥用。所有受保护接口统一通过
 * {@code ((LoginUser) auth.getPrincipal()).getUserId()} 获取当前用户 ID。</p>
 *
 * <p>由 {@link JwtAuthenticationFilter} 在鉴权通过后构建并放入 Authentication principal；
 * 未登录（匿名）请求的 principal 不是 LoginUser，调用方需用 {@code instanceof} 判断。</p>
 */
public class LoginUser {

    /** 用户 ID（JWT subject 解析而来，非数字时为空） */
    private final Long userId;
    /** 用户类型：owner / tenant / admin / senior_admin / super_admin */
    private final String userType;

    /**
     * 构造登录主体。
     *
     * @param userId   用户 ID
     * @param userType 用户类型
     */
    public LoginUser(Long userId, String userType) {
        this.userId = userId;
        this.userType = userType;
    }

    /** 用户 ID */
    public Long getUserId() {
        return userId;
    }

    /** 用户类型 */
    public String getUserType() {
        return userType;
    }
}
