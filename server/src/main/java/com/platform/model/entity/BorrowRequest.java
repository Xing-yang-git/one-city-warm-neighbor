package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.BizStatus;
import com.platform.model.entity.column.BorrowRequestsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 借用申请实体，对应 borrow_requests 表。
 *
 * <p>记录用户对闲置物品的借用申请及完整流转过程。
 * 借用状态流转：pending（待审批）→ approved（已同意）/ rejected（已拒绝）→ returned（已归还）。
 * 归还时借出方可评价物品损坏程度：normal（正常损耗）/ severe（非正常损坏）/ broken（完全损坏）。</p>
 */
@Entity
@Table(name = BorrowRequestsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BorrowRequest {

    /** 借用记录 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被借用物品 ID，外键 → idle_items.id */
    @Column(name = BorrowRequestsColumn.COL_IDLE_ID, nullable = false)
    private Long idleId;

    /** 借入方用户 ID，外键 → users.id */
    @Column(name = BorrowRequestsColumn.COL_BORROWER_ID, nullable = false)
    private Long borrowerId;

    /** 借出时长类型：day(按天) / hour(按小时) */
    @Column(name = BorrowRequestsColumn.COL_DURATION_TYPE, nullable = false, length = 10)
    private String durationType;

    /** 借出天数 */
    @Column(name = BorrowRequestsColumn.COL_DURATION_DAYS, nullable = false)
    private Integer durationDays;

    /** 借用备注（借入方留言） */
    @Column(name = BorrowRequestsColumn.COL_NOTE, length = 200)
    private String note;

    /** 借出开始日期 */
    @Column(name = BorrowRequestsColumn.COL_START_DATE)
    private LocalDate startDate;

    /** 借用状态：pending(待审批) / approved(已同意) / rejected(已拒绝) / returned(已归还)，引用 {@link BizStatus} */
    @Column(name = BorrowRequestsColumn.COL_STATUS, nullable = false, length = 20)
    @Builder.Default
    private String status = BizStatus.PENDING;

    /** 交接照片 URL 列表（JSON 数组字符串） */
    @Column(name = BorrowRequestsColumn.COL_HANDOFF_PHOTOS, columnDefinition = "TEXT")
    private String handoffPhotos;

    /** 审批通过时间（物主同意借出时设置） */
    @Column(name = BorrowRequestsColumn.COL_APPROVED_AT)
    private LocalDateTime approvedAt;

    /** 归还完成时间（confirmReturn 时显式设置，不受 @PreUpdate 影响） */
    @Column(name = BorrowRequestsColumn.COL_RETURNED_AT)
    private LocalDateTime returnedAt;

    /** 归还状态：ontime(按时归还) / delayed(逾期归还) / not_returned(未归还)，引用 {@link com.platform.common.ReturnStatus} */
    @Column(name = BorrowRequestsColumn.COL_RETURN_STATUS, length = 20)
    private String returnStatus;

    /** 归还备注（借出方填写） */
    @Column(name = BorrowRequestsColumn.COL_RETURN_NOTE, length = 200)
    private String returnNote;

    /** 损坏类型：normal(正常损耗) / severe(非正常损坏) / broken(完全损坏)，引用 {@link com.platform.common.DamageType} */
    @Column(name = BorrowRequestsColumn.COL_DAMAGE_TYPE, length = 20)
    private String damageType;

    /** 损坏详细描述 */
    @Column(name = BorrowRequestsColumn.COL_DAMAGE_NOTE, length = 200)
    private String damageNote;

    /** 是否按时归还（null 表示尚未归还） */
    @Column(name = BorrowRequestsColumn.COL_IS_ON_TIME)
    private Boolean isOnTime;

    /** 归还照片 URL 列表（JSON 数组字符串） */
    @Column(name = BorrowRequestsColumn.COL_RETURN_PHOTOS, columnDefinition = "TEXT")
    private String returnPhotos;

    /** 创建时间 */
    @Column(name = BorrowRequestsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = BorrowRequestsColumn.COL_UPDATED_AT, nullable = false)
    private LocalDateTime updatedAt;

    /** 关联闲置物品实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = BorrowRequestsColumn.COL_IDLE_ID, insertable = false, updatable = false)
    private IdleItem idleItem;

    /** 关联借入方用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = BorrowRequestsColumn.COL_BORROWER_ID, insertable = false, updatable = false)
    private User borrower;

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
