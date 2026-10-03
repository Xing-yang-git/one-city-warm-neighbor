package com.platform.repository;

import com.platform.common.PostStatus;
import com.platform.common.ModerationStatus;
import com.platform.model.entity.IdleItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IdleItemRepository extends JpaRepository<IdleItem, Long> {

    Page<IdleItem> findByStatusAndPostType(String status, String postType, Pageable pageable);

    Page<IdleItem> findByStatusAndPostTypeAndTenantId(String status, String postType, Long tenantId, Pageable pageable);

    Page<IdleItem> findByPostTypeAndStatusIn(String postType, List<String> statuses, Pageable pageable);

    @Query("SELECT i FROM IdleItem i WHERE i.status = :status AND i.postType = :postType " +
           "AND (i.title LIKE %:title% OR i.description LIKE %:desc%)")
    Page<IdleItem> findByStatusAndPostTypeAndTitleContainingOrDescriptionContaining(
            @Param("status") String status,
            @Param("postType") String postType,
            @Param("title") String title,
            @Param("desc") String desc,
            Pageable pageable);

    /** 带租户隔离的关键词搜索——搜索必须与 getHomeList 一样限制在本小区内 */
    @Query("SELECT i FROM IdleItem i WHERE i.status = :status AND i.postType = :postType " +
           "AND i.tenantId = :tenantId AND (i.title LIKE %:title% OR i.description LIKE %:desc%)")
    Page<IdleItem> searchByTenant(
            @Param("status") String status,
            @Param("postType") String postType,
            @Param("tenantId") Long tenantId,
            @Param("title") String title,
            @Param("desc") String desc,
            Pageable pageable);

    List<IdleItem> findByUserId(Long userId);

    Page<IdleItem> findByUserIdAndPostType(Long userId, String postType, Pageable pageable);

    Page<IdleItem> findByStatusIn(List<String> statuses, Pageable pageable);

    long countByStatus(String status);

    /** 按小区统计指定状态的闲置物品数 */
    long countByTenantIdAndStatus(Long tenantId, String status);

    long countByStatusAndPostType(String status, String postType);

    long countByStatusAndCreatedAtBetween(String status, LocalDateTime start, LocalDateTime end);

    List<IdleItem> findByStatus(String status);

    /**
     * 带悲观写锁（SELECT ... FOR UPDATE）的按 ID 查询。
     * 用于并发借入申请，确保状态检查 → 修改在同一把锁内原子执行，
     * 防止两个住户同时申请借入同一物品时出现双重借入。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM IdleItem i WHERE i.id = :id")
    Optional<IdleItem> findByIdWithLock(@Param("id") Long id);

    /**
     * 语义搜索候选集查询 — 拉取同小区、指定类型、在架且有 embedding 的物品，
     * 供 SemanticSearchService 通过 pgvector 原生查询做向量检索。
     */
    @Query("SELECT i FROM IdleItem i WHERE i.tenantId = :tenantId " +
           "AND i.postType = :postType AND i.status = :status " +
           "AND i.embedding IS NOT NULL")
    List<IdleItem> findCandidatesForSearch(
            @Param("tenantId") Long tenantId,
            @Param("postType") String postType,
            @Param("status") String status);

    /**
     * 向量相似度匹配 — 使用 pgvector 余弦距离查找与目标向量最相似的闲置物品。
     *
     * <p>用于供需匹配场景：发布 WANTED 时，查找历史 LEND 中语义相似的物品。</p>
     *
     * <p>返回 {@code Object[]} 数组，每行元素：
     * <ul>
     *   <li>[0] — 物品 ID（Long）</li>
     *   <li>[1] — 物品标题（String）</li>
     *   <li>[2] — 发布者用户 ID（Long）</li>
     *   <li>[3] — 余弦距离（Double），越小越相似</li>
     * </ul></p>
     */
    @Query(value = "SELECT i.id, i.title, i.user_id, " +
           "CAST(i.embedding AS vector) <=> CAST(:embedding AS vector) AS distance " +
           "FROM idle_items i " +
           "WHERE i.tenant_id = :tenantId " +
           "  AND i.post_type = :postType " +
           "  AND i.user_id != :excludeUserId " +
           "  AND i.status IN (:statuses) " +
           "  AND i.updated_at > :since " +
           "  AND i.embedding IS NOT NULL " +
           "  AND i.embedding <=> CAST(:embedding AS vector) < :threshold " +
           "ORDER BY distance ASC " +
           "LIMIT :limit", nativeQuery = true)
    List<Object[]> findSimilarByEmbedding(
            @Param("embedding") String embedding,
            @Param("tenantId") Long tenantId,
            @Param("postType") String postType,
            @Param("excludeUserId") Long excludeUserId,
            @Param("statuses") List<String> statuses,
            @Param("since") LocalDateTime since,
            @Param("threshold") double threshold,
            @Param("limit") int limit);

    /**
     * 条件写入 AI 审核结果 — 仅当内容仍处于「待审核 + 待 AI 审核」时才生效。
     *
     * <p>审核是异步的，结果回来时内容可能已被用户下架、被并发申请占用、或被管理员处理。
     * 此处让数据库在同一条语句内完成「判断 + 写入」，受影响行数为 0 即表示结果已过期，
     * 调用方应丢弃该结果而不是覆盖当前状态。</p>
     *
     * <p>条件中的状态值取自 {@link PostStatus} / {@link ModerationStatus} 常量拼接（注解需编译期常量），
     * 避免在 JPQL 中硬编码字面量。</p>
     *
     * <p><b>本方法自带 {@code @Transactional}</b>：调用它的审核链路跑在线程池线程上、没有外层事务，
     * 而 {@code @Modifying} 查询必须在事务内执行——缺事务会抛 {@code TransactionRequiredException}，
     * 导致审核结论写不进去、内容永远停在 pending_review。故事务必须标注在这里，而不是调用方
     * （调用方 {@code applyResult} 是类内私有方法自调用，加在它上面代理不生效）。</p>
     *
     * @param id         物品 ID
     * @param newStatus  目标内容状态（green → online / yellow → pending_review / red → offline）
     * @param level      审核等级（写入 moderation_status）
     * @param reason     下架/复核原因；审核通过时传 null
     * @return 受影响行数，0 表示审核结果已过期
     */
    @Transactional
    @Modifying
    @Query("UPDATE IdleItem i SET i.status = :newStatus, i.moderationStatus = :level, i.delistReason = :reason "
            + "WHERE i.id = :id AND i.status = '" + PostStatus.PENDING_REVIEW + "' "
            + "AND i.moderationStatus = '" + ModerationStatus.PENDING + "'")
    int applyModerationResult(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("level") String level,
                              @Param("reason") String reason);

    /**
     * 仅更新语义向量列。
     *
     * <p>向量在后台异步生成，期间用户可能已下架或修改该物品。此处只写 embedding 一列，
     * 避免用生成向量时的旧快照整行覆盖，导致并发期间的状态变更被抹掉。</p>
     *
     * <p>标注 {@code @Transactional}：{@code @Modifying} 查询必须在事务内执行。调用方
     * （EmbeddingService）本身有类级事务，此处为并入其事务（REQUIRED），行为不变。</p>
     *
     * @param id        物品 ID
     * @param embedding pgvector 字面量文本（如 '[0.1, 0.2, ...]'）
     * @return 受影响行数，0 表示物品已不存在
     */
    @Transactional
    @Modifying
    @Query("UPDATE IdleItem i SET i.embedding = :embedding WHERE i.id = :id")
    int updateEmbeddingById(@Param("id") Long id, @Param("embedding") String embedding);
}
