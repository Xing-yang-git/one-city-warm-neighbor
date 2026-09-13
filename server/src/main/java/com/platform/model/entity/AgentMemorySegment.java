package com.platform.model.entity;

import com.platform.common.AppTimeZone;
import com.platform.common.MemorySegmentStatus;
import com.platform.model.entity.column.AgentMemorySegmentsColumn;
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
 * 会话长期记忆压缩段实体，对应 agent_memory_segments 表。
 *
 * <p>滑动窗口归档每段一条：标题+摘要+向量（embedding-3 1024 维），供新窗口开始时的记忆检索注入
 * {@code {历史记忆}}。embedding 存 pgvector 字面量（String），与 {@link KnowledgeItem} 同款声明；
 * 压缩失败时 status=RETRY，会话结束时补压。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = AgentMemorySegmentsColumn.TABLE_NAME)
public class AgentMemorySegment {

    /** 压缩段 ID（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 住户用户 ID，外键 → users.id（记忆检索强制按用户过滤，越权防护） */
    @Column(name = AgentMemorySegmentsColumn.COL_USER_ID, nullable = false)
    private Long userId;

    /** 所属小区 ID，外键 → tenants.id */
    @Column(name = AgentMemorySegmentsColumn.COL_TENANT_ID, nullable = false)
    private Long tenantId;

    /** 会话级 id（滑动窗口多条归档记录共享同一会话 id；供记忆检索与联动删除） */
    @Column(name = AgentMemorySegmentsColumn.COL_CONVERSATION_ID, nullable = false)
    private Long conversationId;

    /** 对应归档行 id（agent_conversations.id，供回溯原始消息与 RETRY 补压） */
    @Column(name = AgentMemorySegmentsColumn.COL_ARCHIVE_ROW_ID, nullable = false)
    private Long archiveRowId;

    /** 压缩段序号（会话内递增，从 1 起） */
    @Column(name = AgentMemorySegmentsColumn.COL_SEGMENT_NO, nullable = false)
    @Builder.Default
    private Integer segmentNo = 1;

    /** 标题（≤20 字，LLM 压缩产出；失败时用首条用户消息截断兜底） */
    @Column(name = AgentMemorySegmentsColumn.COL_TITLE, length = 50)
    private String title;

    /** 摘要（目标 100~200 字，上限 300 字） */
    @Column(name = AgentMemorySegmentsColumn.COL_SUMMARY, length = 600)
    private String summary;

    /** 摘要向量（pgvector 1024 维字面量 '[0.1,0.2,...]'，与 knowledge_items 同款：TEXT 列存字面量，检索/索引时 ::vector 强转；生成失败留空，补压时补齐） */
    @Column(name = AgentMemorySegmentsColumn.COL_EMBEDDING)
    private String embedding;

    /** 状态：SUCCESS(成功)/RETRY(失败待补压)，引用 {@link MemorySegmentStatus} */
    @Column(name = AgentMemorySegmentsColumn.COL_STATUS, nullable = false, length = 20)
    @Builder.Default
    private String status = MemorySegmentStatus.SUCCESS;

    /** 创建时间 */
    @Column(name = AgentMemorySegmentsColumn.COL_CREATED_AT, nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 插入前自动填充创建时间 */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(AppTimeZone.APP_ZONE);
    }
}
