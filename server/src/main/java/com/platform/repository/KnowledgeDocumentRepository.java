package com.platform.repository;

import com.platform.model.entity.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 知识库源文档数据访问层 — 文档管理 CRUD + 定时巡检查询。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    /**
     * 按小区 + ID 查询（校验归属用）。
     *
     * @param tenantId 小区 ID
     * @param id       文档 ID
     * @return 文档（可选）
     */
    Optional<KnowledgeDocument> findByTenantIdAndId(Long tenantId, Long id);

    /**
     * 按小区 + 文件名查询（同名替换判定）。
     *
     * @param tenantId 小区 ID
     * @param fileName 文件名
     * @return 文档（可选）
     */
    Optional<KnowledgeDocument> findByTenantIdAndFileName(Long tenantId, String fileName);

    /**
     * 按小区 + MD5 查询（重复上传拦截）。
     *
     * @param tenantId 小区 ID
     * @param fileMd5  文件 MD5
     * @return 文档（可选）
     */
    Optional<KnowledgeDocument> findByTenantIdAndFileMd5(Long tenantId, String fileMd5);

    /**
     * 文档列表 — tenantId/status 可空过滤（super_admin 传 null 查全部小区）。
     *
     * @param tenantId 小区 ID（可空）
     * @param status   状态（可空）
     * @param pageable 分页参数
     * @return 分页结果
     */
    @Query("SELECT d FROM KnowledgeDocument d "
            + "WHERE (:tenantId IS NULL OR d.tenantId = :tenantId) "
            + "AND (:status IS NULL OR d.status = :status) "
            + "ORDER BY d.createdAt DESC")
    Page<KnowledgeDocument> search(@Param("tenantId") Long tenantId,
                                   @Param("status") String status,
                                   Pageable pageable);

    /** 无分页变体（B端知识库列表一次性取全部） */
    @Query("SELECT d FROM KnowledgeDocument d "
            + "WHERE (:tenantId IS NULL OR d.tenantId = :tenantId) "
            + "AND (:status IS NULL OR d.status = :status) "
            + "ORDER BY d.createdAt DESC")
    List<KnowledgeDocument> searchAll(@Param("tenantId") Long tenantId,
                                      @Param("status") String status);

    /**
     * 按状态 + 更新时间阈值查询（卡死解析重置 / 过期文件清理）。
     *
     * @param status 状态
     * @param before 更新时间早于该时间
     * @return 匹配文档列表
     */
    List<KnowledgeDocument> findByStatusAndUpdatedAtBefore(String status, LocalDateTime before);
}
