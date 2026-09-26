package com.platform.ai.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 热会话（Redis 存储）— 单设备单会话，key = agent:session:{userId}。
 *
 * <p>conversationId 为<b>会话级 id</b>（滑动窗口多条归档记录共享同一会话 id，首次归档时用归档行自身
 * id 充当并回填；未归档为 null）；messages 为多轮对话上下文（含 user/assistant/tool 角色，恢复时可重建
 * LLM messages 序列）。</p>
 */
@Data
public class AgentSession {

    /** 会话级 id（首次归档后回填；未归档为 null；滑动窗口多段归档共享） */
    private Long conversationId;

    /**
     * 消息列表中<b>已归档回填的前缀条数</b>（resume 恢复时从归档表回填的消息，下标 0..archivedPrefixCount-1）。
     *
     * <p>用于避免重复归档：这些回填消息已持久化在 PG，切换/退出/空闲/阈值归档时只归档
     * 下标 archivedPrefixCount 之后的新增消息，回填部分不重复建行（历史消息条数不虚高）。
     * 新会话为 0；旧 Redis 序列化缺此字段时反序列化默认为 0（视为全部未归档）。</p>
     */
    private int archivedPrefixCount;

    /** 会话消息（role/content/sources/actions/createTime） */
    private List<AgentMessageItem> messages = new ArrayList<>();

    /** 最近活跃时间（append 时更新，供空闲归档调度判断） */
    private LocalDateTime lastActive;

    /**
     * 会话消息单条 — Redis 热会话与归档回填共用的消息载体。
     *
     * <p>createTime 当前是时间链路的数据载体：Redis 热会话留存 + resume 回填保留；LLM 实际消费的时间
     * 标注来自记忆段 created_at 的相对时间标签（MemoryRetrievalService），本字段是后续渲染/检索的铺垫。</p>
     *
     * @param role       消息角色：user(住户提问) / assistant(AI 回复) / tool(工具调用结果)，
     *                   唯一合法取值见 {@link com.platform.common.AgentMessageRole}
     * @param content    消息正文。assistant 为剔除意图 JSON 后的原文（<b>未做敏感词掩码</b>，
     *                   掩码只作用于展示文本，历史保留原文以免污染后续上下文判断）
     * @param sources    引用来源的 JSON 数组字符串，由 KnowledgeHit 列表序列化而来，
     *                   仅供前端渲染「参考来源」；非 assistant 消息或本次无命中时为 null
     * @param actions    动作卡片的 JSON 数组字符串，由 AgentAction 列表序列化而来，
     *                   仅供前端渲染待确认卡片；非 assistant 消息或本次无动作时为 null
     * @param createTime 消息产生时间（应用时区）；旧 Redis 序列化数据 / 测试构造可为 null
     */
    public record AgentMessageItem(String role, String content, String sources, String actions,
                                   LocalDateTime createTime) {

        /** 便捷构造器：未提供时间时默认 null（旧 Redis 序列化数据 / 测试构造） */
        public AgentMessageItem(String role, String content, String sources, String actions) {
            this(role, content, sources, actions, null);
        }
    }
}
