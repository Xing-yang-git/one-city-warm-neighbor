package com.platform.ai.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PromptRepository 提示词仓库单元测试 — 验证 classpath:prompts/ 下提示词文件的加载、
 * UTF-8 中文无乱码、properties 文案集解析与缺 key 时的 fail-fast 行为。
 *
 * <p>构造器即完成全部加载（fail-fast 设计），测试直接 {@code new PromptRepository()} 即可：
 * mvn test 阶段 resources 已在 classpath 上，无需反射或 Spring 上下文。</p>
 */
@DisplayName("PromptRepository 提示词仓库单元测试")
class PromptRepositoryTest {

    /** 抽查的 3 个 key（引用 PromptRepository 常量，避免测试与登记表各自维护一份字面量） */
    private static final List<String> KEYS = List.of(
            PromptRepository.KEY_AGENT_SYSTEM, PromptRepository.KEY_MEMORY_COMPRESS,
            PromptRepository.KEY_BLOCK_REPLIES);

    private final PromptRepository repository = new PromptRepository();

    @Test
    @DisplayName("加载 - 抽查的 3 个 key 均可取到非空内容且中文无乱码")
    void should_loadAllPrompts_when_constructor() {
        for (String key : KEYS) {
            String content = repository.get(key);
            assertThat(content).as("key=%s 内容非空", key).isNotBlank();
            // UTF-8 解码验证：中文原样保留（乱码通常表现为替换符 U+FFFD）
            assertThat(content).as("key=%s 中文无乱码", key).doesNotContain("�");
            // 每个提示词文件均以「小邻」为主题词，验证中文内容真实可读
            assertThat(content).as("key=%s 含中文主题词", key).contains("小邻");
        }
    }

    @Test
    @DisplayName("加载 - agent.system 含 {小区名} 与 {历史记忆} 占位符")
    void should_containPlaceholders_when_systemPrompt() {
        String system = repository.get(PromptRepository.KEY_AGENT_SYSTEM);

        assertThat(system).contains("{小区名}").contains("{历史记忆}");
    }

    @Test
    @DisplayName("解析 - block.replies 按 properties 格式解析出 11 个 key 且中文值正确")
    void should_parseRepliesProps_when_getProps() {
        Properties props = repository.getProps(PromptRepository.KEY_BLOCK_REPLIES);

        assertThat(props).hasSize(11);
        assertThat(props.getProperty("empty")).isEqualTo("请输入有效问题");
        assertThat(props.getProperty("symbol")).isEqualTo("请输入有效问题");
        assertThat(props.getProperty("emoji")).isEqualTo("请输入文字描述你的问题");
        assertThat(props.getProperty("duplicate")).isEqualTo("请勿重复发送相同消息");
        assertThat(props.getProperty("too-long")).isEqualTo("输入内容过长，请精简后重试");
        assertThat(props.getProperty("clear")).isEqualTo("已清空对话上下文，我们可以重新开始啦");
        assertThat(props.getProperty("exit")).isEqualTo("好的，再见！有需要随时来找我");
        assertThat(props.getProperty("version")).isEqualTo("小邻 v1.0.0");
        assertThat(props.getProperty("help")).isEqualTo(
                "我是小邻，小区的智能助手，可以帮你：1. 查物业信息——客服电话、办公时间、办事指南、应急联系；" +
                        "2. 搜闲置物品——小区里的借出/求借物品；3. 了解平台怎么用。" +
                        "常用指令：/clear 清空对话记忆 ｜ /help 帮助 ｜ /version 版本。有问题直接问我～");
        assertThat(props.getProperty("sensitive")).isEqualTo("消息包含不当内容，请文明发言");
        assertThat(props.getProperty("greeting")).isEqualTo(
                "你好呀！我是小邻，小区里的智能助手，可以帮你查物业服务、搜闲置物品、了解平台使用。有什么想问的吗？");
    }

    @Test
    @DisplayName("获取 - 不存在的 key 抛 IllegalStateException（fail-fast 防引用失误）")
    void should_throw_when_keyMissing() {
        assertThatThrownBy(() -> repository.get("agent.nonexistent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提示词不存在");

        assertThatThrownBy(() -> repository.getProps("block.nonexistent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提示词不存在");
    }
}
