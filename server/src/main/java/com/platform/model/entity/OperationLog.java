package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.OperationLogsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 管理员操作日志实体，对应 operation_logs 表。
 *
 * <p>记录 B端管理员的所有关键操作，包括：
 * 用户审核（通过/驳回）、内容下架、代发内容、管理员账号管理。
 * 操作类型由 {@link com.platform.common.OperationAction} 常量类定义。</p>
 */
@Entity
@Table(name = OperationLogsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationLog {

    /** 日志 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作管理员 ID，外键 → users.id */
    @Column(name = OperationLogsColumn.COL_ADMIN_ID, nullable = false)
    private Long adminId;

    /** 所属小区 ID，外键 → tenants.id */
    @Column(name = OperationLogsColumn.COL_TENANT_ID, nullable = false)
    private Long tenantId;

    /** 操作类型：approve_user / reject_user / remove_content / proxy_publish_idle / proxy_publish_help / create_admin / delete_admin，引用 {@link com.platform.common.OperationAction} */
    @Column(name = OperationLogsColumn.COL_ACTION, nullable = false, length = 50)
    private String action;

    /** 操作目标类型：idle / help / borrow / user */
    @Column(name = OperationLogsColumn.COL_TARGET_TYPE, length = 30)
    private String targetType;

    /** 操作目标 ID */
    @Column(name = OperationLogsColumn.COL_TARGET_ID)
    private Long targetId;

    /** 操作详情（JSON） */
    @Column(name = OperationLogsColumn.COL_DETAIL, columnDefinition = "TEXT")
    private String detail;

    /** 创建时间 */
    @Column(name = OperationLogsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 关联操作管理员实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = OperationLogsColumn.COL_ADMIN_ID, insertable = false, updatable = false)
    private User admin;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
