package com.platform.common;

/**
 * 知识库条目状态常量 — knowledge_items.status 字段的唯一合法取值。
 *
 * <p>知识库是 B端维护的 RAG 语料，供 AI 助手检索引用，不参与社区内容的发布/审核流转。
 * 取值与 {@link PostStatus} 的 online/offline 相同，但业务域不同，故独立成类——
 * 若将来知识库需要新增状态（如 pending_embedding），不会波及社区内容状态。</p>
 */
public final class KnowledgeStatus {

    /** 工具类，禁止实例化 */
    private KnowledgeStatus() {
    }

    /** 已上线（可被检索引用） */
    public static final String ONLINE = "online";

    /** 已下线 */
    public static final String OFFLINE = "offline";
}
