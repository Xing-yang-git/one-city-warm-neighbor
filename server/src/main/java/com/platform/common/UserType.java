package com.platform.common;

/**
 * 用户类型常量 — users.user_type 字段的唯一合法取值。
 *
 * <p>字符串值与数据库存储值、前端契约严格一致，禁止修改。
 * 与 B端 admin/src/utils/constants.ts 的 USER_TYPE 保持一致；
 * C端 miniprogram/utils/constants.js 未定义用户类型常量，注册页直接提交本类字面量。</p>
 *
 * <p>注意：Entity 默认值使用本类常量（如 {@link #OWNER}），
 * 不再使用中文字面量（如 "业主"）。中文展示标签由 {@link com.platform.common.UserFormatter} 负责。</p>
 */
public final class UserType {

    /** 工具类，禁止实例化 */
    private UserType() {}

    /** 业主（房产所有者） */
    public static final String OWNER = "owner";
    /** 租户（租赁住户） */
    public static final String TENANT = "tenant";
    /** 普通管理员（B端运营人员） */
    public static final String ADMIN = "admin";
    /** 高级管理员 */
    public static final String SENIOR_ADMIN = "senior_admin";
    /** 超级管理员 */
    public static final String SUPER_ADMIN = "super_admin";
}
