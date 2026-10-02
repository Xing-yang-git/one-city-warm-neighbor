package com.platform.repository;

import com.platform.common.BizStatus;
import com.platform.model.entity.BorrowRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BorrowRequestRepository extends JpaRepository<BorrowRequest, Long> {

    List<BorrowRequest> findByBorrowerId(Long borrowerId);

    List<BorrowRequest> findByIdleIdInAndStatus(List<Long> idleIds, String status);

    List<BorrowRequest> findByIdleId(Long idleId);

    long countByStatusAndCreatedAtBetween(String status, LocalDateTime start, LocalDateTime end);

    List<BorrowRequest> findByStatus(String status);

    List<BorrowRequest> findByBorrowerIdAndStatus(Long borrowerId, String status);

    /** 检查指定用户对指定闲置物品是否存在给定状态的借入申请 */
    boolean existsByBorrowerIdAndIdleIdAndStatus(Long borrowerId, Long idleId, String status);

    @Query("SELECT br FROM BorrowRequest br WHERE br.idleItem.userId = :ownerId AND br.status = :status")
    List<BorrowRequest> findByOwnerIdAndStatus(@Param("ownerId") Long ownerId, @Param("status") String status);

    /** 按小区统计指定状态的借入申请数（JOIN 闲置物品表） */
    @Query("SELECT COUNT(br) FROM BorrowRequest br JOIN br.idleItem ii WHERE br.status = :status AND ii.tenantId = :tenantId")
    long countByStatusAndTenantId(@Param("status") String status, @Param("tenantId") Long tenantId);

    /** 全局统计指定状态的借入申请数（super_admin 用，不限小区） */
    long countByStatus(String status);

    /**
     * 仅查询申请所属物品 ID，不加载实体。
     *
     * <p>审批流程需要先知道「要锁哪一行物品」，但若此处把申请实体载入一级缓存，
     * 后续对同一实体的加锁查询可能直接命中缓存里的旧值，导致锁内校验读到过期状态。
     * 故此处只取外键，让真正的实体加载发生在加锁之后。</p>
     *
     * @param id 借入申请 ID
     * @return 物品 ID；申请不存在时为空
     */
    @Query("SELECT br.idleId FROM BorrowRequest br WHERE br.id = :id")
    Optional<Long> findIdleIdById(@Param("id") Long id);

    /**
     * 条件更新申请状态 — 仅当申请仍是待审批时才生效。
     *
     * <p>并发重复审批（连点、陈旧页面重放）下，数据库保证只有一次更新能命中，
     * 其余更新到 0 行，调用方据此判定「已被处理」。用条件更新代替「先查后写」，
     * 可避免两个事务同时读到待审批后各自写入。</p>
     *
     * <p>标注 {@code @Transactional}：{@code @Modifying} 查询必须在事务内执行。调用方
     * （BorrowService）本身有类级事务，此处为并入其事务（REQUIRED），行为不变；
     * 标注是为了让方法自身具备独立可执行性，避免今后被无事务的调用方使用时静默失败。</p>
     *
     * @param id         借入申请 ID
     * @param status     目标状态（approved / rejected）
     * @param approvedAt 审批通过时间；拒绝时传 null
     * @return 受影响行数，0 表示申请已被处理或不存在
     */
    @Transactional
    @Modifying
    @Query("UPDATE BorrowRequest br SET br.status = :status, br.approvedAt = :approvedAt "
            + "WHERE br.id = :id AND br.status = '" + BizStatus.PENDING + "'")
    int decideIfPending(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("approvedAt") LocalDateTime approvedAt);
}
