package com.platform.common;

/**
 * 用户认证状态常量 — users.auth_status 字段的唯一合法取值。
 *
 * <p>描述住户账号从注册到封禁的整条状态链。与 C端 miniprogram/utils/constants.js 的
 * AUTH_STATUS、B端 admin/src/utils/constants.ts 中对应常量保持一致。</p>
 *
 * <p>典型流转：微信登录 {@code registering} → 提交实名材料 {@code pending} →
 * 管理员审核 {@code approved} / {@code rejected}；违规可置 {@code banned}。</p>
 *
 * <p>注意：本类与内容状态无关——{@code pending}/{@code approved}/{@code rejected}
 * 这三个值在 {@link PostStatus}、{@link BorrowStatus}、{@link HelpApplicationStatus}
 * 中同样存在，但描述的是完全不同的业务域，不可互相引用。</p>
 */
public final class AuthStatus {

    /** 工具类，禁止实例化 */
    private AuthStatus() {
    }

    /** 注册中（用户已微信登录但尚未完成手机号绑定/实名） */
    public static final String REGISTERING = "registering";

    /** 待审核（已提交实名材料，等待管理员审核） */
    public static final String PENDING = "pending";

    /** 审核通过（已完成实名认证） */
    public static final String APPROVED = "approved";

    /** 审核驳回 */
    public static final String REJECTED = "rejected";

    /** 已封禁 */
    public static final String BANNED = "banned";
}
