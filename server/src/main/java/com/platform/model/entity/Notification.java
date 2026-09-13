package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.NotificationsColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 系统通知实体，对应 notifications 表。
 *
 * <p>各类业务事件会生成通知推送给目标用户，包括：
 * 借用申请/审批结果、帮助申请/处理结果、用户审核结果、违规处理、归还确认等。
 * 通过 isRead 标记已读状态，relatedId 关联业务记录。</p>
 */
@Entity
@Table(name = NotificationsColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    /** 通知 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目标用户 ID，外键 → users.id */
    @Column(name = NotificationsColumn.COL_USER_ID, nullable = false)
    private Long userId;

    /** 通知类型：borrow_request / borrow_result / help_application / help_result / audit_result / violation / return_confirm / notification，引用 {@link com.platform.common.NotificationType} */
    @Column(name = NotificationsColumn.COL_TYPE, nullable = false, length = 30)
    private String type;

    /** 通知标题 */
    @Column(name = NotificationsColumn.COL_TITLE, nullable = false, length = 100)
    private String title;

    /** 通知内容正文 */
    @Column(name = NotificationsColumn.COL_CONTENT, columnDefinition = "TEXT")
    private String content;

    /** 关联业务 ID（如借用记录 ID、帮助申请 ID） */
    @Column(name = NotificationsColumn.COL_RELATED_ID)
    private Long relatedId;

    /** 是否已读 */
    @Column(name = NotificationsColumn.COL_IS_READ, nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    /** 创建时间 */
    @Column(name = NotificationsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 关联目标用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = NotificationsColumn.COL_USER_ID, insertable = false, updatable = false)
    private User user;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
