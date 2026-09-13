package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.ModerationStatus;
import com.platform.model.entity.column.AgentMessagesColumn;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 消息实体，对应 agent_messages 表（会话+消息分离归档）。
 *
 * <p>软删不物理删（conversation 状态标记 deleted），故本表不设级联删除。
 * 归档目的是恢复会话上下文（回填最近 N 轮给 LLM），必须保留 role 才能重建 messages 序列。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = AgentMessagesColumn.TABLE_NAME)
public class AgentMessage {

    /** 消息 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 ID，外键 → agent_conversations.id（软删不物理删，不加 CASCADE） */
    @Column(name = AgentMessagesColumn.COL_CONVERSATION_ID, nullable = false)
    private Long conversationId;

    /** 消息角色：user(住户消息)/assistant(AI回复)/tool(工具调用结果)；system prompt 动态构建不归档 */
    @Column(name = AgentMessagesColumn.COL_ROLE, nullable = false, length = 20)
    private String role;

    /** 消息内容 */
    @Column(name = AgentMessagesColumn.COL_CONTENT)
    private String content;

    /** 引用来源（JSON 数组字符串，后端检索结果防幻觉） */
    @Column(name = AgentMessagesColumn.COL_SOURCES)
    private String sources;

    /** 动作卡片（JSON 数组字符串，需用户确认的写操作参数） */
    @Column(name = AgentMessagesColumn.COL_ACTIONS)
    private String actions;

    /** 文本审核结果：pending(待审核)/pass(通过)/fail(违规标记)，默认 pending */
    @Column(name = AgentMessagesColumn.COL_MODERATION_STATUS, nullable = false, length = 10)
    @Builder.Default
    private String moderationStatus = ModerationStatus.PENDING;

    /** 违规原因（fail 时填充） */
    @Column(name = AgentMessagesColumn.COL_MODERATION_REASON)
    private String moderationReason;

    /** 创建时间 */
    @Column(name = AgentMessagesColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 插入前自动填充创建时间 */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
