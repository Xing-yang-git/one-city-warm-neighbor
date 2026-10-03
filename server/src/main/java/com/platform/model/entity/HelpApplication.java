package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.HelpApplicationStatus;
import com.platform.model.entity.column.HelpApplicationsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 帮助申请实体，对应 help_applications 表。
 *
 * <p>记录社区成员对他人的求助信息的接单申请及状态流转。
 * 申请状态流转：pending（待审批）→ approved（已同意）/ rejected（已拒绝）→ completed（已完成）。</p>
 */
@Entity
@Table(name = HelpApplicationsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HelpApplication {

    /** 申请 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 求助信息 ID，外键 → help_requests.id */
    @Column(name = HelpApplicationsColumn.COL_HELP_ID, nullable = false)
    private Long helpId;

    /** 接单用户 ID，外键 → users.id */
    @Column(name = HelpApplicationsColumn.COL_HELPER_ID, nullable = false)
    private Long helperId;

    /** 申请备注（接单方留言） */
    @Column(name = HelpApplicationsColumn.COL_NOTE, length = 200)
    private String note;

    /** 申请状态：pending(待审批) / approved(已同意) / rejected(已拒绝) / completed(已完成)，引用 {@link HelpApplicationStatus} */
    @Column(name = HelpApplicationsColumn.COL_STATUS, nullable = false, length = 20)
    @Builder.Default
    private String status = HelpApplicationStatus.PENDING;

    /** 完成时间（帮助完成后设置） */
    @Column(name = HelpApplicationsColumn.COL_COMPLETED_AT)
    private LocalDateTime completedAt;

    /** 创建时间 */
    @Column(name = HelpApplicationsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = HelpApplicationsColumn.COL_UPDATED_AT, nullable = false)
    private LocalDateTime updatedAt;

    /** 关联求助信息实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = HelpApplicationsColumn.COL_HELP_ID, insertable = false, updatable = false)
    private HelpRequest helpRequest;

    /** 关联接单用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = HelpApplicationsColumn.COL_HELPER_ID, insertable = false, updatable = false)
    private User helper;

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
