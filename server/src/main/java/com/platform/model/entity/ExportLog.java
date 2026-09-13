package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.ExportLogsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 导出日志实体，对应 export_logs 表。
 *
 * <p>每次 B端管理员执行数据导出时记录一条，包含导出范围、筛选条件、各 Sheet 记录数。
 * super_admin 的导出操作 tenantId 为 null（平台级操作）。</p>
 */
@Entity
@Table(name = ExportLogsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExportLog {

    /** 日志 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 导出操作人 ID，外键 → users.id */
    @Column(name = ExportLogsColumn.COL_ADMIN_ID, nullable = false)
    private Long adminId;

    /** 所属小区 ID，外键 → tenants.id。super_admin 导出时为 null（平台级操作） */
    @Column(name = ExportLogsColumn.COL_TENANT_ID)
    private Long tenantId;

    /** 导出格式：xlsx */
    @Column(name = ExportLogsColumn.COL_EXPORT_FORMAT, nullable = false, length = 10)
    private String exportFormat;

    /** 勾选项目（JSON 数组字符串），如 ["residents","posts"] */
    @Column(name = ExportLogsColumn.COL_SELECTED_OPTIONS, nullable = false, columnDefinition = "TEXT")
    private String selectedOptions;

    /** 筛选开始日期（yyyy-MM-dd），NULL 表示不限 */
    @Column(name = ExportLogsColumn.COL_DATE_RANGE_START, length = 10)
    private String dateRangeStart;

    /** 筛选结束日期（yyyy-MM-dd），NULL 表示不限 */
    @Column(name = ExportLogsColumn.COL_DATE_RANGE_END, length = 10)
    private String dateRangeEnd;

    /** 住户清单 Sheet 记录数 */
    @Column(name = ExportLogsColumn.COL_RESIDENTS_COUNT, nullable = false)
    private Integer residentsCount;

    /** 发布记录 Sheet 记录数 */
    @Column(name = ExportLogsColumn.COL_POSTS_COUNT, nullable = false)
    private Integer postsCount;

    /** 互借记录 Sheet 记录数 */
    @Column(name = ExportLogsColumn.COL_BORROWS_COUNT, nullable = false)
    private Integer borrowsCount;

    /** 互助记录（技能求助）Sheet 记录数 */
    @Column(name = ExportLogsColumn.COL_HELPS_COUNT, nullable = false)
    @Builder.Default
    private Integer helpsCount = 0;

    /** 下架记录 Sheet 记录数 */
    @Column(name = ExportLogsColumn.COL_REMOVALS_COUNT, nullable = false)
    private Integer removalsCount;

    /** 评分数据 Sheet 记录数 */
    @Column(name = ExportLogsColumn.COL_RATINGS_COUNT, nullable = false)
    private Integer ratingsCount;

    /** 生成的文件名（不含路径），如 export_2026-07-01_2026-07-25.xlsx */
    @Column(name = ExportLogsColumn.COL_FILE_NAME, nullable = false, length = 200)
    private String fileName;

    /** 创建时间 */
    @Column(name = ExportLogsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
