package com.platform.model.entity;

import com.platform.common.AgentConversationStatus;
import com.platform.common.AppTimeZone;
import com.platform.model.entity.column.AgentConversationsColumn;
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
 * Agent 会话实体，对应 agent_conversations 表（长期记忆归档）。
 *
 * <p>滑动窗口设计：每次归档创建一条新行（archived），同一会话的多次归档通过 conversation_id
 * 关联（首次归档行的 conversation_id = 自身 id，充当会话级 id；后续归档行共享该值）。
 * Redis 热会话（active）在触发归档后写入本表（archived），用户软删后标记 deleted 保留审计。
 * last_message_at（业务时间，仅新消息更新）与 updated_at（审计时间，任意变更更新）语义不同。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = AgentConversationsColumn.TABLE_NAME)
public class AgentConversation {

    /** 会话 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 住户用户 ID，外键 → users.id */
    @Column(name = AgentConversationsColumn.COL_USER_ID, nullable = false)
    private Long userId;

    /** 所属小区 ID，外键 → tenants.id */
    @Column(name = AgentConversationsColumn.COL_TENANT_ID, nullable = false)
    private Long tenantId;

    /** 会话级 conversation_id（滑动窗口多条归档记录共享同一会话 id；历史数据 NULL 视作自身 id） */
    @Column(name = AgentConversationsColumn.COL_CONVERSATION_ID)
    private Long conversationId;

    /** 会话标题（归档时由压缩流程异步回填；未回填为 null） */
    @Column(name = AgentConversationsColumn.COL_TITLE, length = 200)
    private String title;

    /** 消息条数（归档阈值判断） */
    @Column(name = AgentConversationsColumn.COL_MESSAGE_COUNT, nullable = false)
    @Builder.Default
    private Integer messageCount = 0;

    /** 状态：active(进行中)/archived(已归档)/deleted(已软删)，引用 {@link AgentConversationStatus} */
    @Column(name = AgentConversationsColumn.COL_STATUS, nullable = false, length = 10)
    @Builder.Default
    private String status = AgentConversationStatus.ACTIVE;

    /** 最后一条消息时间（空闲归档判断，区别于 updated_at 审计时间） */
    @Column(name = AgentConversationsColumn.COL_LAST_MESSAGE_AT)
    private LocalDateTime lastMessageAt;

    /** 创建时间 */
    @Column(name = AgentConversationsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（任意变更，审计用） */
    @Column(name = AgentConversationsColumn.COL_UPDATED_AT, nullable = false)
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
