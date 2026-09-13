package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.TenantsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 小区实体，对应 tenants 表。
 *
 * <p>小区是最顶层的空间划分单位，是租户隔离的核心维度。
 * 小区下包含多个楼栋（building），每个楼栋包含多个单元（unit），
 * 每个单元包含多个房间（room）。</p>
 */
@Entity
@Table(name = TenantsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    /** 小区 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 小区名称 */
    @Column(name = TenantsColumn.COL_NAME, nullable = false, length = 100)
    private String name;

    /** 创建时间 */
    @Column(name = TenantsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
