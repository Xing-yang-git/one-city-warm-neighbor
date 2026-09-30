package com.platform.ai.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentTagStreamFilter 意图标记流式过滤器单元测试。
 *
 * <p>核心不变量：无论模型输出被 SSE 切成什么样，转发出去的文本里都不得出现意图标记本身、
 * 标记内容或标记碎片（含被切一半的 {@code <in} / {@code </in}）；而被扣留的正文在判定为
 * 普通文本后必须原样释放，不能丢字。</p>
 */
@DisplayName("IntentTagStreamFilter 意图标记流式过滤器单元测试")
class IntentTagStreamFilterTest {

    /** 一条完整的意图标记块（内容随意，过滤器不关心里面的 JSON 是否合法） */
    private static final String TAG_BLOCK = "<intent>{\"intent\":\"publish_idle\",\"params\":{}}</intent>";

    /**
     * 依次喂入分片，拼接全部可转发文本。
     *
     * @param deltas 分片序列（模拟传输层任意切分）
     * @return 累计转发文本
     */
    private static String forwardAll(String... deltas) {
        IntentTagStreamFilter filter = new IntentTagStreamFilter();
        StringBuilder forwarded = new StringBuilder();
        for (String delta : deltas) {
            forwarded.append(filter.accept(delta));
        }
        return forwarded.toString();
    }

    @Test
    @DisplayName("转发 - 标记块独占一个分片时整块扣留")
    void should_forwardNothing_when_tagBlockAlone() {
        assertThat(forwardAll(TAG_BLOCK)).isEmpty();
    }

    @Test
    @DisplayName("转发 - 起始标记被分片切成两半时不泄漏标记碎片")
    void should_forwardNothing_when_openTagSplit() {
        assertThat(forwardAll("<in", "tent>{\"intent\":\"publish_idle\",\"params\":{}}</intent>")).isEmpty();
    }

    @Test
    @DisplayName("转发 - 闭合标记被分片切成两半时整块扣留且其后正文正常释放")
    void should_forwardTail_when_closeTagSplit() {
        String forwarded = forwardAll("<intent>{\"intent\":\"publish_idle\"}</in", "tent>请确认填写");

        assertThat(forwarded).isEqualTo("请确认填写");
    }

    @Test
    @DisplayName("转发 - 同一分片内「正文 + 标记块 + 正文」只转发两段正文")
    void should_forwardProseOnly_when_textAndTagInSameChunk() {
        String forwarded = forwardAll("好的，帮你整理：", TAG_BLOCK, "请确认填写");

        assertThat(forwarded).isEqualTo("好的，帮你整理：请确认填写");
    }

    @Test
    @DisplayName("转发 - 单个分片内两个标记块之间夹的正文正常释放")
    void should_forwardMiddle_when_twoTagBlocks() {
        String forwarded = forwardAll(TAG_BLOCK + "中间" + TAG_BLOCK);

        assertThat(forwarded).isEqualTo("中间");
    }

    @Test
    @DisplayName("转发 - 纯正文（含裸花括号）逐字原样转发，不丢字")
    void should_forwardVerbatim_when_noTag() {
        assertThat(forwardAll("价格 ", "{0,100} ", "元可以吗")).isEqualTo("价格 {0,100} 元可以吗");
    }

    @Test
    @DisplayName("转发 - 正文末尾的孤立尖括号会暂留，确认不是标记后原样释放")
    void should_releaseLoneAngleBracket_when_notTag() {
        // 「报价 <」的尾部可能是 <intent> 的前缀，需暂留一片；下一片证明不是标记后必须原样吐出
        assertThat(forwardAll("报价 <", "100 元")).isEqualTo("报价 <100 元");
    }

    @Test
    @DisplayName("转发 - 流在标记块中途结束时只转发标记之前的正文，不抛异常")
    void should_dropUnclosedBlock_when_streamEndsInside() {
        String forwarded = forwardAll("帮我发布", "<intent>{\"intent\":\"publish_idle\",\"par");

        assertThat(forwarded).isEqualTo("帮我发布");
    }

    @Test
    @DisplayName("扣留标记 - 出现过意图标记即为 true，纯正文为 false")
    void should_reportSuppressed_when_tagAppears() {
        IntentTagStreamFilter untouched = new IntentTagStreamFilter();
        untouched.accept("价格 {0,100} 元可以吗");
        assertThat(untouched.suppressedAny()).isFalse();

        IntentTagStreamFilter tagged = new IntentTagStreamFilter();
        tagged.accept(TAG_BLOCK);
        assertThat(tagged.suppressedAny()).isTrue();
    }

    @Test
    @DisplayName("扣留标记 - 标记块跨分片、以及流在块中途结束，均记为已扣留")
    void should_reportSuppressed_when_tagSplitOrUnclosed() {
        // 跨分片：标记被劈开，仍算扣留过
        IntentTagStreamFilter split = new IntentTagStreamFilter();
        split.accept("<in");
        split.accept("tent>{\"intent\":\"publish_idle\"}</intent>");
        assertThat(split.suppressedAny()).isTrue();

        // 未闭合：流被截断，同样算扣留过（这正是需要告警的那类异常）
        IntentTagStreamFilter unclosed = new IntentTagStreamFilter();
        unclosed.accept("帮我发布<intent>{\"intent\":\"publish_idle\",\"par");
        assertThat(unclosed.suppressedAny()).isTrue();
    }

    @Test
    @DisplayName("转发 - 空分片与 null 分片不改变状态")
    void should_ignoreEmptyDelta() {
        IntentTagStreamFilter filter = new IntentTagStreamFilter();

        assertThat(filter.accept(null)).isEmpty();
        assertThat(filter.accept("")).isEmpty();
        assertThat(filter.accept(TAG_BLOCK)).isEmpty();
        assertThat(filter.accept("")).isEmpty();
    }
}
