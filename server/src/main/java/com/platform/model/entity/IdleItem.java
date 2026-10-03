package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.ItemCondition;
import com.platform.common.PostStatus;
import com.platform.common.DurationUnit;
import com.platform.common.PickupMethod;
import com.platform.common.PostType;
import com.platform.model.entity.column.IdleItemsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 闲置物品实体，对应 idle_items 表。
 *
 * <p>支持出借（LEND）和求借（WANTED）两种发布类型。
 * 物品状态流转：pending_review（待AI审核）→ online（展示中）→ reserved（已预订）→ returned（已归还）/ draft（草稿，用户下架）/ offline（已下架）。
 * 物品成色分为 like-new（几乎全新）、normal（正常）、worn（有磨损）。
 * 借出时长可按天、周、月计算；取货方式支持自取和快递。</p>
 */
@Entity
@Table(name = IdleItemsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdleItem {

    /** 物品 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发布用户 ID，外键 → users.id */
    @Column(name = IdleItemsColumn.COL_USER_ID, nullable = false)
    private Long userId;

    /** 所属小区 ID，外键 → tenants.id */
    @Column(name = IdleItemsColumn.COL_TENANT_ID, nullable = false)
    private Long tenantId;

    /** 发布类型：LEND(出借) / WANTED(求借)，引用 {@link PostType} */
    @Column(name = IdleItemsColumn.COL_POST_TYPE, nullable = false, length = 10)
    @Builder.Default
    private String postType = PostType.LEND;

    /** 物品标题 */
    @Column(name = IdleItemsColumn.COL_TITLE, nullable = false, length = 100)
    private String title;

    /** 物品描述 */
    @Column(name = IdleItemsColumn.COL_DESCRIPTION, length = 200)
    private String description;

    /** 物品分类（如：书籍、电子产品、工具等） */
    @Column(name = IdleItemsColumn.COL_CATEGORY, nullable = false, length = 20)
    private String category;

    /** 物品成色：like-new(几乎全新) / normal(正常使用痕迹) / worn(有明显磨损)，引用 {@link ItemCondition} */
    @Column(name = IdleItemsColumn.COL_CONDITION, nullable = false, length = 10)
    @Builder.Default
    private String condition = ItemCondition.NORMAL;

    /** 价格（元），0 表示免费出借 */
    @Column(name = IdleItemsColumn.COL_PRICE, nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    /** 物品图片 URL 列表（JSON 数组字符串） */
    @Column(name = IdleItemsColumn.COL_IMAGES, columnDefinition = "TEXT")
    private String images;

    /** 单次最多借出天数 */
    @Column(name = IdleItemsColumn.COL_MAX_DURATION)
    @Builder.Default
    private Integer maxDuration = 7;

    /** 借出时长单位：day(天) / week(周) / month(月)，引用 {@link DurationUnit} */
    @Column(name = IdleItemsColumn.COL_DURATION_UNIT, nullable = false, length = 10)
    @Builder.Default
    private String durationUnit = DurationUnit.DAY;

    /** 取货方式：self_pickup(需自提) / both(自提或送上门)，引用 {@link PickupMethod} */
    @Column(name = IdleItemsColumn.COL_PICKUP_METHOD, nullable = false, length = 30)
    @Builder.Default
    private String pickupMethod = PickupMethod.SELF_PICKUP;

    /** 状态：online(展示中) / draft(草稿) / offline(已下架) / pending_review(待AI审核) / pending(已被申请) / active(进行中) / completed(已完成)，引用 {@link PostStatus} */
    @Column(name = IdleItemsColumn.COL_STATUS, nullable = false, length = 20)
    @Builder.Default
    private String status = PostStatus.ONLINE;

    /** 统一下架原因：AI审核原因、管理员驳回/下架原因、用户自行下架原因，新原因直接覆盖旧值 */
    @Column(name = IdleItemsColumn.COL_DELIST_REASON, length = 200)
    private String delistReason;

    /** 是否为代发（管理员代住户发布） */
    @Column(name = IdleItemsColumn.COL_IS_PROXY, nullable = false)
    @Builder.Default
    private Boolean isProxy = false;

    /** 语义向量（TEXT 存储 pgvector 字面量，如 '[0.1, 0.2, ...]'），查询时 CAST 为 vector */
    @Column(name = IdleItemsColumn.COL_EMBEDDING, columnDefinition = "TEXT")
    private String embedding;

    /** AI 审核状态：pending（待审核）/ green（通过）/ yellow（待复核）/ red（驳回）/ reviewed（已人工复核） */
    @Column(name = IdleItemsColumn.COL_MODERATION_STATUS, length = 10)
    private String moderationStatus;

    /** 审核内容的管理员用户ID，外键→users.id，NULL表示AI自动处理，非NULL表示该管理员手动通过或驳回了审核 */
    @Column(name = IdleItemsColumn.COL_REVIEWED_BY)
    private Long reviewedBy;

    /** 创建时间 */
    @Column(name = IdleItemsColumn.COL_CREATED_AT, nullable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = IdleItemsColumn.COL_UPDATED_AT, nullable = false)
    private LocalDateTime updatedAt;

    /** 关联发布用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = IdleItemsColumn.COL_USER_ID, insertable = false, updatable = false)
    private User user;

    /** 关联小区实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = IdleItemsColumn.COL_TENANT_ID, insertable = false, updatable = false)
    private Tenant tenant;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
        updatedAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
