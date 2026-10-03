package com.platform.model.entity.column;

/**
 * idle_items 表字段名常量 — 与数据库 schema（db/schema.sql）严格一致。
 *
 * <p>所有使用 idle_items 表字段名的 JPA 注解（@Column、@JoinColumn、@UniqueConstraint）
 * 必须引用本类常量，禁止硬编码字符串。</p>
 */
public final class IdleItemsColumn {

    /** 工具类，禁止实例化 */
    private IdleItemsColumn() {}

    /** 表名 */
    public static final String TABLE_NAME = "idle_items";

    /** 物品 ID（自增主键） */
    public static final String COL_ID = "id";
    /** 发布用户 ID，外键 → users.id */
    public static final String COL_USER_ID = "user_id";
    /** 所属小区 ID，外键 → tenants.id */
    public static final String COL_TENANT_ID = "tenant_id";
    /** 发布类型：LEND(出借) / WANTED(求借)，引用 {@link com.platform.common.PostType} */
    public static final String COL_POST_TYPE = "post_type";
    /** 物品标题 */
    public static final String COL_TITLE = "title";
    /** 物品描述 */
    public static final String COL_DESCRIPTION = "description";
    /** 物品分类 */
    public static final String COL_CATEGORY = "category";
    /** 物品成色：like-new(几乎全新) / normal(正常使用痕迹) / worn(有明显磨损)，引用 {@link com.platform.common.ItemCondition} */
    public static final String COL_CONDITION = "condition";
    /** 价格（元） */
    public static final String COL_PRICE = "price";
    /** 物品图片 URL 列表（JSON 数组） */
    public static final String COL_IMAGES = "images";
    /** 单次最多借出天数 */
    public static final String COL_MAX_DURATION = "max_duration";
    /** 借出时长单位：day(天) / week(周) / month(月) */
    public static final String COL_DURATION_UNIT = "duration_unit";
    /** 取货方式：self_pickup(需自提) / both(自提或送上门) */
    public static final String COL_PICKUP_METHOD = "pickup_method";
    /** 状态：online(展示中) / draft(草稿) / offline(已下架) / pending_review(待AI审核) / pending(已被申请) / active(进行中) / completed(已完成)，引用 {@link com.platform.common.PostStatus} */
    public static final String COL_STATUS = "status";
    /** 统一下架原因 */
    public static final String COL_DELIST_REASON = "delist_reason";
    /** 是否为代发（管理员代住户发布） */
    public static final String COL_IS_PROXY = "is_proxy";
    /** 语义向量（pgvector 768 维），用于语义搜索和供需匹配 */
    public static final String COL_EMBEDDING = "embedding";
    /** AI 审核状态：pending / green / yellow / red / reviewed */
    public static final String COL_MODERATION_STATUS = "moderation_status";
    /** 审核内容的管理员用户ID，NULL=AI自动，非NULL=管理员手动通过或驳回 */
    public static final String COL_REVIEWED_BY = "reviewed_by";
    /** 创建时间 */
    public static final String COL_CREATED_AT = "created_at";
    /** 更新时间 */
    public static final String COL_UPDATED_AT = "updated_at";
}
