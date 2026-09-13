package com.platform.repository;

import com.platform.model.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByOpenid(String openid);

    Optional<User> findByUsername(String username);

    Optional<User> findByPhone(String phone);

    Optional<User> findByPhoneAndTenantId(String phone, Long tenantId);

    Optional<User> findByPhoneAndTenantIdAndRoomId(String phone, Long tenantId, Long roomId);

    /** 注册房间查重按 (房间, 身份) 维度：同一房间业主与租客可并存 */
    Optional<User> findByRoomIdAndUserType(Long roomId, String userType);

    /** 鉴权探针：一次查询同时取 token 版本与审核状态 */
    interface AuthProbe {
        Integer getTokenVersion();
        String getAuthStatus();
    }

    @Query("SELECT u.tokenVersion AS tokenVersion, u.authStatus AS authStatus FROM User u WHERE u.id = ?1")
    AuthProbe findAuthProbeById(Long id);

    /** 显式 JOIN FETCH — 确保完整加载房间层级关系，用于显示名称格式化 */
    @Query("SELECT u FROM User u " +
           "LEFT JOIN FETCH u.room r " +
           "LEFT JOIN FETCH r.unit un " +
           "LEFT JOIN FETCH un.building b " +
           "WHERE u.id = :id")
    Optional<User> findByIdWithRoom(@Param("id") Long id);

    List<User> findByAuthStatus(String authStatus);

    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    Page<User> findByAuthStatus(String authStatus, Pageable pageable);

    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    Page<User> findByAuthStatusNot(String authStatus, Pageable pageable);

    /** 仅已审核通过的住户 — 排除 admin/super_admin 账号 */
    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    Page<User> findByAuthStatusAndUserTypeNotIn(String authStatus, List<String> userTypes, Pageable pageable);

    // ── 带 tenant 过滤的审核查询（修复跨小区数据泄露） ──
    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    Page<User> findByTenantIdAndAuthStatus(Long tenantId, String authStatus, Pageable pageable);

    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    Page<User> findByTenantIdAndAuthStatusNot(Long tenantId, String authStatus, Pageable pageable);

    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    Page<User> findByTenantIdAndAuthStatusAndUserTypeNotIn(Long tenantId, String authStatus, List<String> userTypes, Pageable pageable);

    // ── 无分页变体（B端列表一次性取全部） ──
    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    List<User> findByTenantIdAndAuthStatus(Long tenantId, String authStatus);

    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    List<User> findByAuthStatusNot(String authStatus);

    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    List<User> findByTenantIdAndAuthStatusNot(Long tenantId, String authStatus);

    long countByTenantIdAndAuthStatus(Long tenantId, String authStatus);

    long countByTenantIdAndAuthStatusNot(Long tenantId, String authStatus);

    long countByTenantIdAndAuthStatusAndUserTypeNotIn(Long tenantId, String authStatus, List<String> userTypes);

    long countByAuthStatus(String authStatus);

    long countByAuthStatusNot(String authStatus);

    long countByAuthStatusAndUserTypeNotIn(String authStatus, List<String> userTypes);

    List<User> findByRoomIdIn(List<Long> roomIds);

    @Query("SELECT u FROM User u JOIN u.room r JOIN r.unit un JOIN un.building b " +
           "WHERE u.authStatus = 'approved' " +
           "AND b.tenantId = :tenantId " +
           "AND (:buildingNo IS NULL OR b.buildingNo = :buildingNo) " +
           "AND (:unitNo IS NULL OR un.unitNo = :unitNo) " +
           "AND (:room IS NULL OR r.roomNumber LIKE %:room%) " +
           "AND (:userType IS NULL OR u.userType = :userType) " +
           "AND (:keyword IS NULL OR u.name LIKE %:keyword% OR u.phone LIKE %:keyword%)")
    Page<User> findResidents(@Param("tenantId") Long tenantId,
                             @Param("buildingNo") Integer buildingNo,
                             @Param("unitNo") Integer unitNo,
                             @Param("room") String room,
                             @Param("userType") String userType,
                             @Param("keyword") String keyword,
                             Pageable pageable);

    /** 无分页变体（B端住户选择器一次性取全部，按创建时间倒序） */
    @Query("SELECT u FROM User u JOIN u.room r JOIN r.unit un JOIN un.building b " +
           "WHERE u.authStatus = 'approved' " +
           "AND b.tenantId = :tenantId " +
           "AND (:buildingNo IS NULL OR b.buildingNo = :buildingNo) " +
           "AND (:unitNo IS NULL OR un.unitNo = :unitNo) " +
           "AND (:room IS NULL OR r.roomNumber LIKE %:room%) " +
           "AND (:userType IS NULL OR u.userType = :userType) " +
           "AND (:keyword IS NULL OR u.name LIKE %:keyword% OR u.phone LIKE %:keyword%) " +
           "ORDER BY u.createdAt DESC")
    List<User> findResidentsAll(@Param("tenantId") Long tenantId,
                                @Param("buildingNo") Integer buildingNo,
                                @Param("unitNo") Integer unitNo,
                                @Param("room") String room,
                                @Param("userType") String userType,
                                @Param("keyword") String keyword);

    List<User> findByTenantIdAndUserTypeIn(Long tenantId, List<String> userTypes);

    /** 按用户类型列表查询全部小区（super_admin 查看管理员列表时使用） */
    List<User> findByUserTypeIn(List<String> userTypes);

    /** 按小区ID和认证状态获取已认证的非管理员住户列表（用于导出），懒加载房间关联 */
    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    List<User> findByTenantIdAndAuthStatusAndUserTypeNotIn(Long tenantId, String authStatus, List<String> userTypes);

    /** 全局获取已认证的非管理员住户列表（super_admin 导出用，不限小区） */
    @EntityGraph(attributePaths = {"room", "room.unit", "room.unit.building"})
    List<User> findByAuthStatusAndUserTypeNotIn(String authStatus, List<String> userTypes);
}
