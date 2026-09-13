package com.platform.ai;

import com.platform.common.ActivityRole;
import com.platform.common.AiGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AI 文案优化客户端 — 调用 deepseek-v4-flash 进行文本生成与润色。
 *
 * <p>基于 Spring AI {@link OpenAiChatModel}（deepseek-v4-flash，OpenAI 兼容端点），
 * 当前支持互助感想智能生成（mode=feedback），未来可扩展标题润色等场景。</p>
 *
 * <p>温度 0.7（创意文案场景，保持既有规范）；max_tokens 500 为 reasoning 模型思维链预留空间（300 实测不足）。</p>
 */
@Slf4j
@Component
public class PolishingClient {

    private final OpenAiChatModel deepseekChatModel;

    public PolishingClient(OpenAiChatModel deepseekChatModel) {
        this.deepseekChatModel = deepseekChatModel;
    }

    /** 角色中文映射 — 用于 System Prompt 背景 */
    private static final Map<String, String> ROLE_DESC_MAP = Map.of(
            ActivityRole.BORROW, "我是借入方，邻居把物品借给了我",
            ActivityRole.LEND, "我是借出方，我把闲置物品借给了邻居",
            ActivityRole.HELP_REQ, "我是求助方，邻居帮我解决了问题",
            ActivityRole.HELP_PRO, "我是帮忙方，我帮邻居解决了问题"
    );

    /** 角色对应的 User Message — 强制 AI 理解借贷方向 */
    private static final Map<String, String> ROLE_USER_MSG = Map.of(
            ActivityRole.BORROW, "我向邻居借了「%s」来用，现在写一段感想。注意：我是借东西进来的人，物品是邻居借给我的。",
            ActivityRole.LEND, "我把「%s」借给了邻居，现在写一段感想。注意：我是借出东西的人，物品是我的。",
            ActivityRole.HELP_REQ, "邻居帮我「%s」，现在写一段感想。注意：我是接受帮助的人。",
            ActivityRole.HELP_PRO, "我帮邻居「%s」，现在写一段感想。注意：我是提供帮助的人。"
    );

    /** 互助感想生成的 System Prompt */
    private static final String FEEDBACK_SYSTEM_PROMPT =
            "你是社区互助平台评价助手。用第一人称\"我\"写一段 20-60 字的互助感想。\n\n" +
            "铁则：\n" +
            "- 只根据补充说明中已提到的事实来写，不要编造任何未提及的事情（包括不要凭空说\"逾期\"\"损坏\"\"磨损\"）\n" +
            "- 补充说明中没提到问题 = 一切顺利，只写正面感受\n" +
            "- 口语化但不要网络用语（如\"超\"\"爆\"），不要以\"邻居\"\"你好\"等称呼开头\n" +
            "- 不用\"唉\"\"哎呀\"\"嘛\"\"呢\"\"啦\"\"呀\"等感叹语气词，不用 emoji\n" +
            "- 不写具体人名房号，不编造信息\n\n" +
            "角色：%s\n" +
            "事项：%s\n" +
            "补充：%s\n\n" +
            "直接返回感想。";

    /**
     * 生成互助感想评价文本。
     *
     * @param role        角色标识：borrow / lend / helpReq / helpPro
     * @param itemTitle   物品标题或求助标题
     * @param description 补充背景（归还情况、物品状况、用户草稿等），可为空
     * @return AI 生成的评价文本
     */
    public String generateFeedback(String role, String itemTitle, String description) {
        String roleDesc = ROLE_DESC_MAP.getOrDefault(role, role);
        String desc = (description != null && !description.isBlank()) ? description : "无";
        String title = (itemTitle != null && !itemTitle.isBlank()) ? itemTitle : "未知";

        String systemPrompt = String.format(FEEDBACK_SYSTEM_PROMPT, roleDesc, title, desc);

        // 角色专属 User Message，强制 AI 区分借贷方向
        String userMsgTemplate = ROLE_USER_MSG.getOrDefault(role, "请根据以上背景生成一段互助感想。");
        String userContent = String.format(userMsgTemplate, title);

        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userContent)),
                OpenAiChatOptions.builder()
                        .temperature(0.7)
                        // reasoning 模型的思维链(reasoning_content)会占用大量 token，太小会导致 content 为空（PoC 实测 300 不够、500 完整）
                        .maxTokens(500)
                        .build());

        ChatResponse response = deepseekChatModel.call(prompt);
        String content = response.getResult().getOutput().getText();

        if (content == null || content.isBlank()) {
            throw new AiGenerationException("AI 返回的评价文本为空");
        }

        // 去除可能的引号包裹
        content = content.trim();
        if ((content.startsWith("\"") && content.endsWith("\""))
                || (content.startsWith("'") && content.endsWith("'"))) {
            content = content.substring(1, content.length() - 1).trim();
        }

        return content;
    }
}
