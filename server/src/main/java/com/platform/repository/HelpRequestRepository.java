package com.platform.repository;

import com.platform.common.BizStatus;
import com.platform.common.ModerationStatus;
import com.platform.model.entity.HelpRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface HelpRequestRepository extends JpaRepository<HelpRequest, Long> {

    Page<HelpRequest> findByStatus(String status, Pageable pageable);

    Page<HelpRequest> findByStatusAndTenantId(String status, Long tenantId, Pageable pageable);

    @Query("SELECT h FROM HelpRequest h WHERE h.status = :status " +
           "AND (h.title LIKE %:title% OR h.description LIKE %:desc%)")
    Page<HelpRequest> findByStatusAndTitleContainingOrDescriptionContaining(
            @Param("status") String status,
            @Param("title") String title,
            @Param("desc") String desc,
            Pageable pageable);

    /** 带租户隔离的关键词搜索——搜索必须与 getHomeList 一样限制在本小区内 */
    @Query("SELECT h FROM HelpRequest h WHERE h.status = :status AND h.tenantId = :tenantId " +
           "AND (h.title LIKE %:title% OR h.description LIKE %:desc%)")
    Page<HelpRequest> searchByTenant(
            @Param("status") String status,
            @Param("tenantId") Long tenantId,
            @Param("title") String title,
            @Param("desc") String desc,
            Pageable pageable);

    List<HelpRequest> findByUserId(Long userId);

    Page<HelpRequest> findByStatusIn(List<String> statuses, Pageable pageable);

    long countByStatus(String status);

    /** 按小区统计指定状态的互助请求数 */
    long countByTenantIdAndStatus(Long tenantId, String status);

    List<HelpRequest> findByStatus(String status);

    /**
     * 带悲观写锁的按 ID 查询，防并发双人申请同一求助。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM HelpRequest h WHERE h.id = :id")
    Optional<HelpRequest> findByIdWithLock(@Param("id") Long id);

    /**
     * 条件写入 AI 审核结果 — 仅当内容仍处于「待审核 + 待 AI 审核」时才生效。
     *
     * <p>审核是异步的，结果回来时内容可能已被用户下架、被并发申请占用、或被管理员处理。
     * 此处让数据库在同一条语句内完成「判断 + 写入」，受影响行数为 0 即表示结果已过期，
     * 调用方应丢弃该结果而不是覆盖当前状态。</p>
     *
     * <p>条件中的状态值取自 {@link BizStatus} / {@link ModerationStatus} 常量拼接（注解需编译期常量），
     * 避免在 JPQL 中硬编码字面量。</p>
     *
     * <p><b>本方法自带 {@code @Transactional}</b>：调用它的审核链路跑在线程池线程上、没有外层事务，
     * 而 {@code @Modifying} 查询必须在事务内执行——缺事务会抛 {@code TransactionRequiredException}，
     * 导致审核结论写不进去、内容永远停在 pending_review。故事务必须标注在这里，而不是调用方
     * （调用方 {@code applyResult} 是类内私有方法自调用，加在它上面代理不生效）。</p>
     *
     * @param id        求助 ID
     * @param newStatus 目标内容状态（green → online / yellow → pending_review / red → offline）
     * @param level     审核等级（写入 moderation_status）
     * @param reason    下架/复核原因；审核通过时传 null
     * @return 受影响行数，0 表示审核结果已过期
     */
    @Transactional
    @Modifying
    @Query("UPDATE HelpRequest h SET h.status = :newStatus, h.moderationStatus = :level, h.delistReason = :reason "
            + "WHERE h.id = :id AND h.status = '" + BizStatus.PENDING_REVIEW + "' "
            + "AND h.moderationStatus = '" + ModerationStatus.PENDING + "'")
    int applyModerationResult(@Param("id") Long id,
                              @Param("newStatus") String newStatus,
                              @Param("level") String level,
                              @Param("reason") String reason);
}
