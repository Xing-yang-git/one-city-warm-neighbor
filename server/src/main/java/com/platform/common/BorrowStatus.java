package com.platform.common;

/**
 * 借用申请状态常量 — borrow_requests.status 字段的唯一合法取值。
 *
 * <p>描述住户之间一次闲置借用的完整流转。与 C端 miniprogram/utils/constants.js 的
 * BORROW_STATUS、B端 admin/src/utils/constants.ts 中对应常量保持一致。</p>
 *
 * <p>典型流转：{@code pending}（待审批）→ {@code approved}（已同意）/ {@code rejected}（已拒绝）
 * → {@code returned}（已归还）→ {@code completed}（已完成）。</p>
 *
 * <p><b>为何同时存在 {@code approved} 与 {@code active}</b>：审批通过后写 {@code approved}；
 * 全仓无任何代码或种子数据写入 {@code active}（种子数据用的也是 {@code approved}），
 * 它只作为历史遗留值存在。但管理端统计与 AI 工具按「active 或 approved」双状态兼容读取，
 * 为免这些读取点失去常量支撑，故保留在本类中；新代码不应再写入该值。</p>
 */
public final class BorrowStatus {

    /** 工具类，禁止实例化 */
    private BorrowStatus() {
    }

    /** 待审批 */
    public static final String PENDING = "pending";

    /** 已同意（审批通过，借用成立） */
    public static final String APPROVED = "approved";

    /** 已拒绝 */
    public static final String REJECTED = "rejected";

    /** 已归还 */
    public static final String RETURNED = "returned";

    /** 已完成 */
    public static final String COMPLETED = "completed";

    /** 进行中（历史遗留值，全仓无写入方；读取处需与 {@link #APPROVED} 兼容） */
    public static final String ACTIVE = "active";
}
