package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.DocumentStatus;
import com.platform.model.entity.column.KnowledgeDocumentsColumn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库源文档实体，对应 knowledge_documents 表。
 *
 * <p>B端上传的源文件元数据（落盘路径、类型、状态），其切片写入 knowledge_items
 * （doc_id 关联）。删除文档时切片经 DB 外键 CASCADE 级联清理。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = KnowledgeDocumentsColumn.TABLE_NAME)
public class KnowledgeDocument {

    /** 源文档 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属小区 ID，外键 → tenants.id */
    @Column(name = KnowledgeDocumentsColumn.COL_TENANT_ID, nullable = false)
    private Long tenantId;

    /** 分类：rules(规章制度)/service(服务手册)/help(平台帮助)/guide(办事指南) */
    @Column(name = KnowledgeDocumentsColumn.COL_CATEGORY, nullable = false, length = 20)
    private String category;

    /** 原始文件名（含扩展名） */
    @Column(name = KnowledgeDocumentsColumn.COL_FILE_NAME, nullable = false, length = 255)
    private String fileName;

    /** 文件类型：md/txt/pdf/docx/xlsx/csv */
    @Column(name = KnowledgeDocumentsColumn.COL_FILE_TYPE, nullable = false, length = 10)
    private String fileType;

    /** 文件 MD5（同租户重复上传拦截） */
    @Column(name = KnowledgeDocumentsColumn.COL_FILE_MD5, length = 32)
    private String fileMd5;

    /** 展示来源名（默认去扩展名文件名），问答引用出处 */
    @Column(name = KnowledgeDocumentsColumn.COL_SOURCE, length = 100)
    private String source;

    /** 逗号分隔标签，关键词检索兜底 */
    @Column(name = KnowledgeDocumentsColumn.COL_TAGS, length = 500)
    private String tags;

    /** 相对 file.knowledge-dir 的落盘路径 */
    @Column(name = KnowledgeDocumentsColumn.COL_STORAGE_PATH, length = 500)
    private String storagePath;

    /** 处理状态：parsing(解析中)/ready(就绪)/failed(失败可重试)，引用 {@link DocumentStatus} */
    @Column(name = KnowledgeDocumentsColumn.COL_STATUS, nullable = false, length = 20)
    @Builder.Default
    private String status = DocumentStatus.PARSING;

    /** 生成的切片数 */
    @Column(name = KnowledgeDocumentsColumn.COL_CHUNK_COUNT, nullable = false)
    @Builder.Default
    private Integer chunkCount = 0;

    /** 失败原因 / 部分内容未处理警告（如 OCR 页数超限、embedding 失败条数） */
    @Column(name = KnowledgeDocumentsColumn.COL_ERROR_MESSAGE)
    private String errorMessage;

    /** 上传管理员用户 ID → users.id */
    @Column(name = KnowledgeDocumentsColumn.COL_CREATED_BY)
    private Long createdBy;

    /** 创建时间 */
    @Column(name = KnowledgeDocumentsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = KnowledgeDocumentsColumn.COL_UPDATED_AT, nullable = false)
    private LocalDateTime updatedAt;

    /** 插入前自动填充创建/更新时间 */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
        updatedAt = createdAt;
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
