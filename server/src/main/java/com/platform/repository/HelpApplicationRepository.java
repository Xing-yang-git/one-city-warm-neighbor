package com.platform.repository;

import com.platform.common.HelpApplicationStatus;
import com.platform.model.entity.HelpApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface HelpApplicationRepository extends JpaRepository<HelpApplication, Long> {

    List<HelpApplication> findByHelperId(Long helperId);

    List<HelpApplication> findByHelpIdInAndStatus(List<Long> helpIds, String status);

    List<HelpApplication> findByHelpId(Long helpId);

    List<HelpApplication> findByHelpIdAndStatus(Long helpId, String status);

    List<HelpApplication> findByStatus(String status);

    long countByHelperIdAndStatus(Long helperId, String status);

    // 同一 helper 对同一求助是否已有进行中的申请（防重复提交）
    boolean existsByHelpIdAndHelperIdAndStatusIn(Long helpId, Long helperId, List<String> statuses);

    /**
     * 查询指定帮助者对指定求助的全部申请，最新创建的在前。
     *
     * <p>供求助详情返回「当前用户申请状态」使用——按 (求助, 帮助者) 定向查询，
     * 避免为了找一个用户的申请而把该求助下的全部申请都拉出来。</p>
     *
     * @param helpId   求助 ID
     * @param helperId 帮助者用户 ID
     * @return 该用户对该求助的申请列表（按创建时间倒序）
     */
    List<HelpApplication> findByHelpIdAndHelperIdOrderByCreatedAtDesc(Long helpId, Long helperId);

    /** 检查指定用户对指定求助是否存在给定状态的帮助申请 */
    boolean existsByHelperIdAndHelpIdAndStatus(Long helperId, Long helpId, String status);

    /** 按小区统计指定状态的帮助申请数（JOIN 互助请求表） */
    @Query("SELECT COUNT(ha) FROM HelpApplication ha JOIN ha.helpRequest hr WHERE ha.status = :status AND hr.tenantId = :tenantId")
    long countByStatusAndTenantId(@Param("status") String status, @Param("tenantId") Long tenantId);

    /** 全局统计指定状态的帮助申请数（super_admin 用，不限小区） */
    long countByStatus(String status);

    /**
     * 仅查询申请所属求助 ID，不加载实体。
     *
     * <p>审批流程需要先知道「要锁哪一行求助」，但若此处把申请实体载入一级缓存，
     * 后续对同一实体的加锁查询可能直接命中缓存里的旧值，导致锁内校验读到过期状态。
     * 故此处只取外键，让真正的实体加载发生在加锁之后。</p>
     *
     * @param id 帮助申请 ID
     * @return 求助 ID；申请不存在时为空
     */
    @Query("SELECT ha.helpId FROM HelpApplication ha WHERE ha.id = :id")
    Optional<Long> findHelpIdById(@Param("id") Long id);

    /**
     * 条件更新申请状态 — 仅当申请仍是待审批时才生效。
     *
     * <p>并发重复审批（连点、陈旧页面重放）下，数据库保证只有一次更新能命中，
     * 其余更新到 0 行，调用方据此判定「已被处理」。用条件更新代替「先查后写」，
     * 可避免两个事务同时读到待审批后各自写入。</p>
     *
     * <p>标注 {@code @Transactional}：{@code @Modifying} 查询必须在事务内执行。调用方
     * （HelpService）本身有类级事务，此处为并入其事务（REQUIRED），行为不变；
     * 标注是为了让方法自身具备独立可执行性，避免今后被无事务的调用方使用时静默失败。</p>
     *
     * @param id     帮助申请 ID
     * @param status 目标状态（approved / rejected）
     * @return 受影响行数，0 表示申请已被处理或不存在
     */
    @Transactional
    @Modifying
    @Query("UPDATE HelpApplication ha SET ha.status = :status "
            + "WHERE ha.id = :id AND ha.status = '" + HelpApplicationStatus.PENDING + "'")
    int decideIfPending(@Param("id") Long id, @Param("status") String status);
}
