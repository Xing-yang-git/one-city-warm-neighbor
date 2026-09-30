package com.platform.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentRouter 意图路由单元测试 — 覆盖 JSON 意图解析、动作卡片生成与 JSON 剔除。
 */
@DisplayName("IntentRouter 意图路由单元测试")
class IntentRouterTest {

    private final IntentRouter intentRouter = new IntentRouter(new ObjectMapper());

    @Test
    @DisplayName("解析 - 命中写操作意图时生成动作卡片")
    void should_parse_when_validIntent() {
        String reply = "好的，马上帮您。{\"intent\":\"publish_help\",\"params\":{\"title\":\"搬家\"}}";

        AgentAction action = intentRouter.parse(reply);

        assertThat(action).isNotNull();
        assertThat(action.type()).isEqualTo("publish_help");
        assertThat(action.label()).isEqualTo("帮您发起求助");
        assertThat(action.params()).containsEntry("title", "搬家");
    }

    @Test
    @DisplayName("解析 - markdown 代码块包裹的 JSON 也能解析")
    void should_parse_when_markdownWrapped() {
        String reply = "```json\n{\"intent\":\"publish_idle\",\"params\":{\"title\":\"电钻\"}}\n```";

        AgentAction action = intentRouter.parse(reply);

        assertThat(action).isNotNull();
        assertThat(action.type()).isEqualTo("publish_idle");
        assertThat(action.label()).isEqualTo("帮您发布闲置");
    }

    @Test
    @DisplayName("解析 - 未知 intent 返回 null")
    void should_returnNull_when_unknownIntent() {
        AgentAction action = intentRouter.parse("{\"intent\":\"delete_all\",\"params\":{}}");

        assertThat(action).isNull();
    }

    @Test
    @DisplayName("解析 - 非 JSON 普通回答返回 null")
    void should_returnNull_when_notJson() {
        AgentAction action = intentRouter.parse("今天天气不错，有什么可以帮您？");

        assertThat(action).isNull();
    }

    @Test
    @DisplayName("解析 - 空回复返回 null")
    void should_returnNull_when_blankReply() {
        assertThat(intentRouter.parse(null)).isNull();
        assertThat(intentRouter.parse("   ")).isNull();
    }

    @Test
    @DisplayName("剔除 - 从回复中剔除 JSON 意图段保留自然语言")
    void should_stripJson_keepNaturalLanguage() {
        String reply = "好的，已为您准备。{\"intent\":\"publish_help\",\"params\":{}}";

        String stripped = intentRouter.stripJson(reply);

        assertThat(stripped).isEqualTo("好的，已为您准备。");
    }

    @Test
    @DisplayName("剔除 - 纯 JSON 回复剔除后为空")
    void should_stripJson_empty_when_jsonOnly() {
        String reply = "{\"intent\":\"publish_idle\",\"params\":{}}";

        assertThat(intentRouter.stripJson(reply)).isEmpty();
    }

    @Test
    @DisplayName("剔除 - 无 JSON 时原样返回并去首尾空白")
    void should_stripJson_returnOriginal_when_noJson() {
        assertThat(intentRouter.stripJson("  普通回答  ")).isEqualTo("普通回答");
        assertThat(intentRouter.stripJson(null)).isEmpty();
    }

    @Test
    @DisplayName("解析 - params 为空对象时返回空 Map")
    void should_parse_emptyParams_when_missing() {
        AgentAction action = intentRouter.parse("{\"intent\":\"publish_wanted\",\"params\":{}}");

        assertThat(action).isNotNull();
        assertThat(action.type()).isEqualTo("publish_wanted");
        assertThat(action.params()).isEmpty();
    }

    @Test
    @DisplayName("解析 - 发布指引意图 goto_publish 解析成功（带 type 参数）")
    void should_parse_gotoPublish() {
        AgentAction action = intentRouter.parse("{\"intent\":\"goto_publish\",\"params\":{\"type\":\"help\"}}");

        assertThat(action).isNotNull();
        assertThat(action.type()).isEqualTo("goto_publish");
        assertThat(action.label()).isEqualTo("去发布");
        assertThat(action.params()).containsEntry("type", "help");
    }

    @Test
    @DisplayName("兜底剥离 - 中置 JSON 意图段（前后都有文字）移除，保留两侧自然语言")
    void should_stripIntentJson_keepSides_when_jsonInMiddle() {
        String reply = "好的，帮你整理：{\"intent\":\"publish_wanted\",\"params\":{\"title\":\"鼠标\",\"category\":\"电子产品\"}} 请确认填写";

        assertThat(intentRouter.stripIntentJson(reply))
                .isEqualTo("好的，帮你整理： 请确认填写");
    }

    @Test
    @DisplayName("兜底剥离 - 纯 JSON 意图段剥离后为空")
    void should_stripIntentJson_empty_when_jsonOnly() {
        assertThat(intentRouter.stripIntentJson("{\"intent\":\"publish_idle\",\"params\":{}}")).isEmpty();
    }

    @Test
    @DisplayName("兜底剥离 - 正文无 intent 的花括号不误伤（只删含 intent 的 JSON）")
    void should_stripIntentJson_notTouchNormalBraces() {
        assertThat(intentRouter.stripIntentJson("普通回答（无JSON） {a} 结束"))
                .isEqualTo("普通回答（无JSON） {a} 结束");
    }

    @Test
    @DisplayName("兜底剥离 - 嵌套 params 的 JSON 完整移除（逐层括号匹配）")
    void should_stripIntentJson_removeNestedJson() {
        String reply = "{\"intent\":\"publish_help\",\"params\":{\"title\":\"x\",\"nested\":{\"a\":1}}}";

        assertThat(intentRouter.stripIntentJson(reply)).isEmpty();
    }

    @Test
    @DisplayName("兜底剥离 - null 返回空串")
    void should_stripIntentJson_nullReturnsEmpty() {
        assertThat(intentRouter.stripIntentJson(null)).isEmpty();
    }

    @Test
    @DisplayName("解析 - <intent> 标记包裹的 JSON 解析成功")
    void should_parse_when_tagged() {
        String reply = "<intent>{\"intent\":\"publish_idle\",\"params\":{\"title\":\"电钻\"}}</intent>";

        AgentAction action = intentRouter.parse(reply);

        assertThat(action).isNotNull();
        assertThat(action.type()).isEqualTo("publish_idle");
        assertThat(action.params()).containsEntry("title", "电钻");
    }

    @Test
    @DisplayName("解析 - 正文含花括号时仍取标记内的意图（不被正文花括号带偏）")
    void should_parseTaggedInner_when_proseHasBraces() {
        // 若按全文 indexOf('{') 取区间，会从「{0,100}」一路贪到意图 JSON 的 '/'，切出垃圾导致意图丢失
        String reply = "价格写成 {0,100} 元即可。"
                + "<intent>{\"intent\":\"publish_idle\",\"params\":{\"title\":\"电钻\"}}</intent>";

        AgentAction action = intentRouter.parse(reply);

        assertThat(action).isNotNull();
        assertThat(action.type()).isEqualTo("publish_idle");
        assertThat(action.params()).containsEntry("title", "电钻");
    }

    @Test
    @DisplayName("剔除 - 标记块连同标记一并剔除，不残留 <intent> 标签")
    void should_stripTag_when_taggedReply() {
        String reply = "好的，帮你整理：<intent>{\"intent\":\"publish_idle\",\"params\":{}}</intent>";

        assertThat(intentRouter.stripJson(reply)).isEqualTo("好的，帮你整理：");
        assertThat(intentRouter.stripIntentJson(reply)).isEqualTo("好的，帮你整理：");
    }

    @Test
    @DisplayName("剔除 - 未闭合的 <intent> 标记连同其后内容全部剔除（防截断泄漏）")
    void should_stripIntentJson_dropTail_when_tagUnclosed() {
        String reply = "好的，帮你整理：<intent>{\"intent\":\"publish_idle\",\"par";

        assertThat(intentRouter.stripIntentJson(reply)).isEqualTo("好的，帮你整理：");
    }

    @Test
    @DisplayName("剔除 - 正文里的裸花括号不受标记剥离影响")
    void should_stripIntentTag_keepProseBraces() {
        assertThat(intentRouter.stripIntentTag("价格 {0,100} 元")).isEqualTo("价格 {0,100} 元");
        assertThat(intentRouter.stripIntentTag(null)).isEmpty();
    }
}
