package com.platform.model.entity.column;

/**
 * help_requests 表字段名常量 — 与数据库 schema（db/schema.sql）严格一致。
 *
 * <p>所有使用 help_requests 表字段名的 JPA 注解（@Column、@JoinColumn、@UniqueConstraint）
 * 必须引用本类常量，禁止硬编码字符串。</p>
 */
public final class HelpRequestsColumn {

    /** 工具类，禁止实例化 */
    private HelpRequestsColumn() {}

    /** 表名 */
    public static final String TABLE_NAME = "help_requests";

    /** 求助 ID（自增主键） */
    public static final String COL_ID = "id";
    /** 发布用户 ID，外键 → users.id */
    public static final String COL_USER_ID = "user_id";
    /** 所属小区 ID，外键 → tenants.id */
    public static final String COL_TENANT_ID = "tenant_id";
    /** 求助标题 */
    public static final String COL_TITLE = "title";
    /** 求助描述 */
    public static final String COL_DESCRIPTION = "description";
    /** 求助分类 */
    public static final String COL_CATEGORY = "category";
    /** 是否紧急求助 */
    public static final String COL_IS_URGENT = "is_urgent";
    /** 求助开始时间 */
    public static final String COL_TIME_START = "time_start";
    /** 求助结束时间 */
    public static final String COL_TIME_END = "time_end";
    /** 求助图片 URL 列表（JSON 数组） */
    public static final String COL_IMAGES = "images";
    /** 求助地点 */
    public static final String COL_LOCATION = "location";
    /** 状态：online(展示中) / draft(草稿) / offline(已下架) / pending_review(待AI审核) / pending(已被申请) / active(进行中) / completed(已完成)，引用 {@link com.platform.common.PostStatus} */
    public static final String COL_STATUS = "status";
    /** 统一下架原因 */
    public static final String COL_DELIST_REASON = "delist_reason";
    /** 是否为代发（管理员代住户发布） */
    public static final String COL_IS_PROXY = "is_proxy";
    /** AI 审核状态：pending / green / yellow / red / reviewed */
    public static final String COL_MODERATION_STATUS = "moderation_status";
    /** 审核内容的管理员用户ID，NULL=AI自动，非NULL=管理员手动通过或驳回 */
    public static final String COL_REVIEWED_BY = "reviewed_by";
    /** 创建时间 */
    public static final String COL_CREATED_AT = "created_at";
    /** 更新时间 */
    public static final String COL_UPDATED_AT = "updated_at";
}
