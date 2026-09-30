package com.platform.common;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Agent 对话 SSE 事件类型 — 事件名与事件体 {@code type} 字段的唯一合法取值。
 *
 * <p>同一个取值同时出现在两个位置：SSE 的 {@code event:} 行与 JSON 事件体的 {@code type} 字段。
 * 用枚举收敛后，{@code AgentController#safeSend} 只接收一个参数，两处不可能再写歪
 * （此前是两个裸字符串参数，改了事件名忘了改 type 会静默发给前端一个错类型的事件）。</p>
 *
 * <p>取值即与 C端的通信契约（{@code miniprogram/pages/assistant/assistant.js} 按 {@code type} 分派），
 * 改动需前后端同步；{@link JsonValue} 保证序列化后就是这里登记的小写值，而非枚举常量名。</p>
 */
public enum AgentEventType {

    /** 会话开始，data 为 {message: 用户消息}；分块能力探测（/probe）复用本类型，data 为 {ok: true} */
    START("start"),

    /** 回复文本分块（伪流式逐字/逐句播放），data 为文本字符串 */
    ANSWER("answer"),

    /** 引用来源列表（后端检索命中），data 为 KnowledgeHit 数组 */
    SOURCES("sources"),

    /** 动作卡片（写操作意图，需用户确认），data 为 AgentAction 数组 */
    ACTION("action"),

    /** 整体替换当前气泡正文（剔除意图 JSON、防幻觉修正后的最终文本），data 为文本字符串 */
    REPLACE("replace"),

    /** 清空前端消息列表（/clear、/reset、「清除对话」命中时），data 为 {done: true} */
    CLEAR("clear"),

    /** 会话结束，data 为 {done: true} */
    END("end"),

    /** 错误信息，data 为对用户安全的文案（内部细节只记服务端日志） */
    ERROR("error");

    /** 线上传输值：SSE 事件名与 JSON type 字段共用，与 C端解析逻辑严格一致 */
    private final String value;

    AgentEventType(String value) {
        this.value = value;
    }

    /**
     * 获取线上传输值（枚举常量名是大写，序列化必须用小写值）。
     *
     * @return 事件名字符串（如 {@code answer}）
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
