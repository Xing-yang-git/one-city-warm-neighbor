package com.platform.common;

/**
 * 通知类型常量 — notifications.type 字段的唯一合法取值。
 *
 * <p>与 C端 miniprogram/utils/constants.js 的 NOTIFICATION_TYPE 和
 * B端 admin/src/utils/constants.ts 的 NOTIFICATION_TYPE 保持一致。</p>
 */
public final class NotificationType {

    /** 工具类，禁止实例化 */
    private NotificationType() {}

    /** 借用申请通知 — 物品发布者收到「有人申请借用」 */
    public static final String BORROW_REQUEST = "borrow_request";
    /** 借用申请已提交通知 — 借入方收到「你的借用申请待对方回应」 */
    public static final String BORROW_APPLICATION = "borrow_application";
    /** 借用审批结果通知 — 借入方收到「对方已同意/拒绝」 */
    public static final String BORROW_RESULT = "borrow_result";
    /** 帮助申请通知 — 求助人收到「有人愿意帮忙」 */
    public static final String HELP_APPLICATION = "help_application";
    /** 帮助申请已提交通知 — 帮忙方收到「你的帮助申请待对方回应」 */
    public static final String HELP_APPLICATION_SUBMITTED = "help_application_submitted";
    /** 帮助处理结果通知 */
    public static final String HELP_RESULT = "help_result";
    /** 帮助申请已通过通知 — 帮忙方收到「对方已接受你的帮助」 */
    public static final String HELP_APPROVED = "help_approved";
    /** 帮助申请已拒绝通知 — 帮忙方收到「对方已谢绝你的帮助」 */
    public static final String HELP_REJECTED = "help_rejected";
    /** 用户审核结果通知 */
    public static final String AUDIT_RESULT = "audit_result";
    /** 违规处理通知 */
    public static final String VIOLATION = "violation";
    /** 归还确认通知 */
    public static final String RETURN_CONFIRM = "return_confirm";
    /** 通用通知 */
    public static final String NOTIFICATION = "notification";
    /** 供需匹配通知 — 有人求借时，通知曾出借过类似物品的用户 */
    public static final String MATCH_DEMAND = "match_demand";
    /** AI 内容审核驳回通知 */
    public static final String CONTENT_REJECTED = "content_rejected";
    /** AI 内容审核通过通知 */
    public static final String CONTENT_APPROVED = "content_approved";
}
