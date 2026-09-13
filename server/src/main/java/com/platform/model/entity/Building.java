package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.BuildingsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 楼栋实体，对应 buildings 表。
 *
 * <p>小区下的一级空间划分，每个楼栋属于一个小区（tenant）。
 * 楼栋下包含多个单元（unit）。</p>
 */
@Entity
@Table(name = BuildingsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Building {

    /** 楼栋 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属小区 ID，外键 → tenants.id */
    @Column(name = BuildingsColumn.COL_TENANT_ID, nullable = false)
    private Long tenantId;

    /** 楼栋号（数值，如 3；展示时由 UserFormatter 拼 "3栋"） */
    @Column(name = BuildingsColumn.COL_BUILDING_NO, nullable = false)
    private Integer buildingNo;

    /** 创建时间 */
    @Column(name = BuildingsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 关联小区实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = BuildingsColumn.COL_TENANT_ID, insertable = false, updatable = false)
    private Tenant tenant;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
