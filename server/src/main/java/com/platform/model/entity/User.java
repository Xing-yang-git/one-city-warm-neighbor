package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.BizStatus;
import com.platform.common.UserType;
import com.platform.model.entity.column.UsersColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 用户实体，对应 users 表。
 *
 * <p>用户按类型分为业主（owner）、租户（tenant）、管理员（admin/senior_admin/super_admin）。
 * 业主和租户通过微信小程序注册登录，管理员通过 B端 PC 管理端登录。
 * 认证状态流转：pending（待审核）→ approved（已通过）/ rejected（已驳回）。</p>
 */
@Entity
@Table(name = UsersColumn.TABLE_NAME, uniqueConstraints = {
    @UniqueConstraint(columnNames = {UsersColumn.COL_PHONE, UsersColumn.COL_TENANT_ID}),
    @UniqueConstraint(columnNames = {UsersColumn.COL_ROOM_ID, UsersColumn.COL_USER_TYPE})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /** 用户 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 房间 ID，外键 → rooms.id */
    @Column(name = UsersColumn.COL_ROOM_ID)
    private Long roomId;

    /** 所属小区 ID，外键 → tenants.id */
    @Column(name = UsersColumn.COL_TENANT_ID)
    private Long tenantId;

    /** 微信 openid（C端用户唯一标识） */
    @Column(name = UsersColumn.COL_OPENID, length = 64, unique = true)
    private String openid;

    /** 用户名（B端管理员登录账号） */
    @Column(name = UsersColumn.COL_USERNAME, length = 50, unique = true)
    private String username;

    /** 密码哈希（BCrypt 加密） */
    @Column(name = UsersColumn.COL_PASSWORD_HASH, length = 255)
    private String passwordHash;

    /** 用户类型：owner(业主) / tenant(租户) / admin(管理员) / senior_admin / super_admin */
    @Column(name = UsersColumn.COL_USER_TYPE, nullable = false, length = 20)
    @Builder.Default
    private String userType = UserType.OWNER;

    /** 真实姓名 */
    @Column(name = UsersColumn.COL_NAME, length = 50)
    private String name;

    /** 手机号（11 位） */
    @Column(name = UsersColumn.COL_PHONE, length = 11)
    private String phone;

    /** 手机号是否已验证 */
    @Column(name = UsersColumn.COL_PHONE_VERIFIED, nullable = false)
    @Builder.Default
    private Boolean phoneVerified = false;

    /** 头像 URL */
    @Column(name = UsersColumn.COL_AVATAR_URL, length = 500)
    private String avatarUrl;

    /** 认证状态：pending(待审核) / approved(已通过) / rejected(已驳回)，引用 {@link BizStatus} */
    @Column(name = UsersColumn.COL_AUTH_STATUS, nullable = false, length = 20)
    @Builder.Default
    private String authStatus = BizStatus.PENDING;

    /** 封禁原因（banned 状态下非空） */
    @Column(name = UsersColumn.COL_BANNED_REASON, length = 200)
    private String bannedReason;

    /** 认证材料图片 URL 列表（JSON 数组字符串） */
    @Column(name = UsersColumn.COL_DOC_IMAGES, columnDefinition = "TEXT")
    private String docImages;

    /** 驳回原因（rejected 状态下非空） */
    @Column(name = UsersColumn.COL_REJECT_REASON, length = 200)
    private String rejectReason;

    /** Token 版本号（C端单会话登录控制，每次登录自增，旧 token 立即失效） */
    @Column(name = UsersColumn.COL_TOKEN_VERSION, nullable = false)
    @Builder.Default
    private Integer tokenVersion = 0;

    /** 创建时间 */
    @Column(name = UsersColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = UsersColumn.COL_UPDATED_AT, nullable = false)
    private LocalDateTime updatedAt;

    /** 关联房间实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = UsersColumn.COL_ROOM_ID, insertable = false, updatable = false)
    private Room room;

    /** 关联小区实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = UsersColumn.COL_TENANT_ID, insertable = false, updatable = false)
    private Tenant tenant;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
        updatedAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
