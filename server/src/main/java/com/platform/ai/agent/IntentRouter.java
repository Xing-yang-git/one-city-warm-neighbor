package com.platform.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 意图路由 — 解析模型最终回复中的 JSON 写操作意图，生成动作卡片。
 *
 * <p>写操作（发布闲置/求助/借入）不注册为可自动执行的工具（避免落库），
 * 而是由 System Prompt 指示模型用 {@code <intent>} 与 {@code </intent>} 包裹 JSON：
 * {@code <intent>{"intent":"publish_xxx","params":{...}}</intent>}，
 * 本组件解析后交给前端渲染确认卡片。</p>
 *
 * <p>标记是流式抑制（{@link IntentTagStreamFilter}）与文本剥离共用的唯一锚点，改动需与
 * {@code prompts/agent/system.md} 的【5.0 输出格式硬约束】保持一致。</p>
 */
@Slf4j
@Component
public class IntentRouter {

    /** 写操作意图起始标记 — 与 system.md 的约定一致，流式过滤与文本剥离共用 */
    public static final String INTENT_TAG_OPEN = "<intent>";

    /** 写操作意图结束标记 */
    public static final String INTENT_TAG_CLOSE = "</intent>";

    private final ObjectMapper objectMapper;

    /** 写操作 intent → 动作卡片按钮文案；goto_publish 为「发布指引」快捷跳转（前端渲染成蓝色"去发布 ›"链接） */
    private static final Map<String, String> ACTION_LABEL = Map.of(
            "publish_help", "帮您发起求助",
            "publish_idle", "帮您发布闲置",
            "publish_wanted", "帮您发布借入需求",
            "goto_publish", "去发布"
    );

    public IntentRouter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析回复文本中的 JSON 意图。
     *
     * @param reply 模型最终回复
     * @return 动作卡片；若文本非写操作 JSON 意图则返回 null（作为普通回答展示）
     */
    public AgentAction parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String json = extractJson(reply);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String intent = node.path("intent").asText("");
            if (!ACTION_LABEL.containsKey(intent)) {
                return null;
            }
            Map<String, Object> params = objectMapper.convertValue(
                    node.path("params"), new TypeReference<Map<String, Object>>() {});
            return new AgentAction(intent, ACTION_LABEL.get(intent), params);
        } catch (Exception e) {
            log.debug("意图解析失败（按普通回答处理）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从回复中剔除 JSON 意图段，返回气泡展示的自然语言文本。
     *
     * <p>模型按指示返回写操作 JSON 时，原始 JSON 不应逐字播放给用户（气泡只展示
     * 自然语言 + 动作卡片）。若剔除后为空（模型只返回纯 JSON），由调用方替换为友好提示。</p>
     *
     * @param reply 模型原始回复
     * @return 剔除 JSON 段后的文本（可能为空）
     */
    public String stripJson(String reply) {
        if (reply == null) {
            return "";
        }
        // 先剥离 <intent> 标记块（含标记本身）：否则 JSON 虽被下文的花括号裁剪去掉，标记会残留显示
        String text = stripIntentTag(reply);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return (text.substring(0, start) + text.substring(end + 1)).trim();
        }
        return text.trim();
    }

    /**
     * 剥离 {@code <intent>...</intent>} 意图块（含标记本身）。
     *
     * <p>未闭合的起始标记（模型输出被截断）连同其后的全部内容一并剥离——宁可少显示，
     * 也不让意图 JSON 泄漏给用户。</p>
     *
     * @param text 模型原始回复
     * @return 剥离标记块后的文本（未 trim）
     */
    public String stripIntentTag(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            int open = text.indexOf(INTENT_TAG_OPEN, cursor);
            int close = open < 0 ? -1 : text.indexOf(INTENT_TAG_CLOSE, open + INTENT_TAG_OPEN.length());
            if (close < 0) {
                // 无起始标记 → 余下全是正文；有起始标记但无闭合 → 保留标记之前的正文，其后一律丢弃
                result.append(text, cursor, open < 0 ? text.length() : open);
                break;
            }
            result.append(text, cursor, open);
            cursor = close + INTENT_TAG_CLOSE.length();
        }
        return result.toString();
    }

    /**
     * 从文本中提取 JSON 部分：优先取 {@code <intent>} 标记区间，无标记时退回全文按首尾花括号切。
     *
     * <p>优先取标记区间是因为正文里可能出现花括号（如「价格 {0,100} 元」）——按全文
     * {@code indexOf('{')} 会从正文的花括号一路贪到意图 JSON 的 {@code }}，切出垃圾导致意图丢失。
     * 标记区间内取不到 JSON 时仍退回全文，兼容模型未按约定加标记的输出。</p>
     *
     * @param text 模型返回的完整文本
     * @return 纯 JSON 字符串，或 null
     */
    private String extractJson(String text) {
        String trimmed = text.trim();
        int tagOpen = trimmed.indexOf(INTENT_TAG_OPEN);
        int tagClose = tagOpen < 0 ? -1 : trimmed.indexOf(INTENT_TAG_CLOSE, tagOpen + INTENT_TAG_OPEN.length());
        if (tagClose > tagOpen) {
            String tagged = extractBraceJson(trimmed.substring(tagOpen + INTENT_TAG_OPEN.length(), tagClose));
            if (tagged != null) {
                return tagged;
            }
        }
        return extractBraceJson(trimmed);
    }

    /**
     * 按首尾花括号从文本中切出 JSON 片段（兼容 markdown 代码块包裹）。
     *
     * @param text 待提取文本
     * @return 纯 JSON 字符串，或 null
     */
    private String extractBraceJson(String text) {
        String trimmed = text.trim();
        // 去除 markdown 代码块 ```json ... ```
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                trimmed = trimmed.substring(start, end).trim();
            }
        }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }
        return null;
    }

    /**
     * 兜底剥离回复中的 JSON 写操作意图段（供展示文本使用）。
     *
     * <p>与 {@link #stripJson} 不同：只移除包含 {@code "intent"} 字段的 JSON 对象块，
     * 用逐层括号匹配定位完整对象，不误伤正文中的花括号（如普通文字里的 {xx}）。
     * 即使意图解析失败（格式偏差/非法 intent），也能保证 JSON 不泄漏给用户。</p>
     *
     * @param text 模型原始回复
     * @return 剥离意图 JSON 后的文本（可能为空；调用方对空做兜底文案）
     */
    public String stripIntentJson(String text) {
        if (text == null) {
            return "";
        }
        // 先剥离 <intent> 标记块（含标记本身与未闭合标记之后的内容），再按括号配对兜底无标记的 JSON
        String body = stripIntentTag(text);
        int start = body.indexOf('{');
        while (start >= 0) {
            int end = matchBrace(body, start);
            if (end < 0) {
                break;
            }
            if (body.substring(start, end + 1).contains("\"intent\"")) {
                return (body.substring(0, start) + body.substring(end + 1)).trim();
            }
            start = body.indexOf('{', end + 1);
        }
        return body.trim();
    }

    /** 从 start（指向 '{'）逐层括号匹配，返回对应 '}' 的下标；不匹配返回 -1 */
    private int matchBrace(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
