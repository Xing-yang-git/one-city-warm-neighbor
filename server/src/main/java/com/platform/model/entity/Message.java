package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.MessageStatus;
import com.platform.common.MessageType;
import com.platform.model.entity.column.MessagesColumn;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 聊天消息实体，对应 messages 表。
 *
 * <p>记录 C端用户之间的一对一聊天消息。通过 sessionId 关联同一对话双方。
 * 消息状态流转：sent（已发送）→ delivered（已送达）→ read（已读）。
 * 支持文本消息和系统消息（如撤回提示）两种类型。
 * 撤回后 content 置为 NULL，recalledAt 记录撤回时间。</p>
 */
@Entity
@Table(name = MessagesColumn.TABLE_NAME)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    /** 消息 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话标识：IDLE_42_3_7 / HELP_42_3_7 / USER_3_7 */
    @Column(name = MessagesColumn.COL_SESSION_ID, nullable = false, length = 255)
    private String sessionId;

    /** 发送方用户 ID，外键 → users.id */
    @Column(name = MessagesColumn.COL_FROM_USER_ID, nullable = false)
    private Long fromUserId;

    /** 接收方用户 ID，外键 → users.id */
    @Column(name = MessagesColumn.COL_TO_USER_ID, nullable = false)
    private Long toUserId;

    /** 消息内容正文；撤回后置为 NULL */
    @Column(name = MessagesColumn.COL_CONTENT, columnDefinition = "TEXT")
    private String content;

    /** 消息类型：text(文本) / system(系统消息)，引用 {@link MessageType} */
    @Column(name = MessagesColumn.COL_MESSAGE_TYPE, nullable = false, length = 20)
    @Builder.Default
    private String messageType = MessageType.TEXT;

    /** 消息送达状态：sent(已发送) / delivered(已送达) / read(已读)，引用 {@link MessageStatus} */
    @Column(name = MessagesColumn.COL_STATUS, nullable = false, length = 20)
    @Builder.Default
    private String status = MessageStatus.SENT;

    /** 撤回时间（NULL 表示未撤回） */
    @Column(name = MessagesColumn.COL_RECALLED_AT)
    private LocalDateTime recalledAt;

    /** 创建时间 */
    @Column(name = MessagesColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = MessagesColumn.COL_UPDATED_AT, nullable = false)
    private LocalDateTime updatedAt;

    /** 关联发送方用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = MessagesColumn.COL_FROM_USER_ID, insertable = false, updatable = false)
    private User fromUser;

    /** 关联接收方用户实体（懒加载） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = MessagesColumn.COL_TO_USER_ID, insertable = false, updatable = false)
    private User toUser;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
