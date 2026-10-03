package com.platform.common;

/**
 * 帮助申请状态常量 — help_applications.status 字段的唯一合法取值。
 *
 * <p>描述住户响应一条求助信息后的申请流转。与 C端 miniprogram/utils/constants.js 的
 * HELP_APPLICATION_STATUS、B端 admin/src/utils/constants.ts 中对应常量保持一致。</p>
 *
 * <p>典型流转：{@code pending}（待审批）→ {@code approved}（已同意）/ {@code rejected}（已拒绝）
 * → {@code completed}（已完成）。</p>
 *
 * <p>注意：求助信息本身的在线/下架等状态属于 {@link PostStatus}，不在本类。</p>
 */
public final class HelpApplicationStatus {

    /** 工具类，禁止实例化 */
    private HelpApplicationStatus() {
    }

    /** 待审批 */
    public static final String PENDING = "pending";

    /** 已同意 */
    public static final String APPROVED = "approved";

    /** 已拒绝 */
    public static final String REJECTED = "rejected";

    /** 已完成 */
    public static final String COMPLETED = "completed";
}
