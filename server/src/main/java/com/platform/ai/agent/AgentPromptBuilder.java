package com.platform.ai.agent;

import com.platform.ai.common.PromptRepository;
import com.platform.common.AgentMessageRole;
import com.platform.common.KnowledgeCategory;
import com.platform.model.entity.KnowledgeItem;
import com.platform.repository.KnowledgeItemRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 对话 Prompt 组装器 — 构建「小邻」助手的 System Prompt 与用户消息。
 *
 * <p>System Prompt 从提示词文件 {@code prompts/agent/system.md} 读取（key {@code agent.system}），
 * 运行时替换占位符：{@code {小区名}} → 小区名称、{@code {历史记忆}} → 记忆上下文变量、{@code {平台功能说明}} →
 * 系统内置平台帮助条目（注册认证/发布/借入/求助/AI 审核规则，docId 为空的权威说明）。
 * 知识库资料不再拼进 System Prompt——检索已工具化（search_knowledge），模型按决策路由自主调用。
 * 平台功能问题以内置说明为准（不采信上传文档的相关切片），物业资料仍走知识库检索。</p>
 */
@Component
public class AgentPromptBuilder {

    /** 提示词仓库（启动时已加载全部提示词文件到内存） */
    private final PromptRepository promptRepository;

    /** 知识库条目仓库（读系统内置平台帮助条目注入 System Prompt 权威说明） */
    private final KnowledgeItemRepository knowledgeItemRepository;

    /**
     * 构造器注入。
     *
     * @param promptRepository       提示词仓库（key {@code agent.system} 对应的 System Prompt 模板）
     * @param knowledgeItemRepository 知识库条目仓库（读内置平台帮助条目）
     */
    public AgentPromptBuilder(PromptRepository promptRepository,
                              KnowledgeItemRepository knowledgeItemRepository) {
        this.promptRepository = promptRepository;
        this.knowledgeItemRepository = knowledgeItemRepository;
    }

    /**
     * 构建对话消息列表（无记忆上下文版，{@code {历史记忆}} 固定渲染「无」）。
     *
     * @param tenantName 小区名称（替换 {@code {小区名}} 占位符）
     * @param message    用户消息
     * @param history    多轮历史（Redis 热会话，可为空）
     * @return system + 历史 + user 消息列表
     */
    public List<Message> buildMessages(String tenantName, String message,
                                       List<AgentSession.AgentMessageItem> history) {
        return buildMessages(tenantName, message, history, null);
    }

    /**
     * 构建对话消息列表（system + 历史 + user），模型 options 由 AgentService 组装（含工具注册）。
     *
     * <p>记忆注入：{@code {历史记忆}} 渲染 = memoryText 非空则其值、否则「无」；
     * 由 AgentService 按次实时检索注入，本组件不做检索。</p>
     *
     * @param tenantName 小区名称（替换 {@code {小区名}} 占位符）
     * @param message    用户消息
     * @param history    多轮历史（Redis 热会话，可为空）
     * @param memoryText 当前消息实时检索的记忆摘要文本（null 或「无」时渲染「无」）
     * @return system + 历史 + user 消息列表
     */
    public List<Message> buildMessages(String tenantName, String message,
                                       List<AgentSession.AgentMessageItem> history, String memoryText) {
        String template = promptRepository.get(PromptRepository.KEY_AGENT_SYSTEM);
        String memory = memoryText == null || memoryText.isBlank() ? "无" : memoryText;
        String system = template
                .replace("{小区名}", tenantName == null ? "本小区" : tenantName)
                .replace("{历史记忆}", memory)
                .replace("{平台功能说明}", platformHelpText());

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        // 多轮历史：仅 user/assistant（tool 为工具中间产物，跳过避免上下文噪声）
        if (history != null) {
            for (AgentSession.AgentMessageItem h : history) {
                if (AgentMessageRole.USER.equals(h.role())) {
                    messages.add(new UserMessage(h.content() == null ? "" : h.content()));
                } else if (AgentMessageRole.ASSISTANT.equals(h.role())
                        && h.content() != null && !h.content().isBlank()) {
                    messages.add(new AssistantMessage(h.content()));
                }
            }
        }
        messages.add(new UserMessage(message));
        return messages;
    }

    /**
     * 读取系统内置平台帮助条目并格式化为权威说明文本（供 {@code {平台功能说明}} 占位符替换）。
     *
     * <p>平台功能（注册认证/发布/借入/求助/AI 审核规则）以这些内置条目为唯一可信来源；
     * 上传文档即使包含相关切片也不作为平台功能依据，避免与内置说明冲突。</p>
     *
     * @return 「标题：内容」逐条列表；无内置条目时返回「（暂无内置平台功能说明）」
     */
    private String platformHelpText() {
        List<KnowledgeItem> items = knowledgeItemRepository.findBuiltinByCategory(KnowledgeCategory.HELP);
        if (items == null || items.isEmpty()) {
            return "（暂无内置平台功能说明）";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeItem item : items) {
            sb.append("- ").append(item.getTitle()).append("：").append(item.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}
