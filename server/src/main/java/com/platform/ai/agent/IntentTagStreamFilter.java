package com.platform.ai.agent;

/**
 * 意图标记流式过滤器 — 跨分片识别并扣留 {@code <intent>...</intent>} 区域，避免写操作意图 JSON
 * 逐字转发给前端。
 *
 * <p>为什么用标记而不是扫花括号：SSE 分片边界由传输层任意切分，意图 JSON 可能整段独占一个分片、
 * 跨多个分片，或与前置正文同处一个分片；靠「首个分片是否以 { 开头」判定会漏，靠括号配对扫描又要
 * 额外处理字符串字面量与转义。改用提示词约定的哨兵标记后，判定退化为一次子串查找，无歧义。</p>
 *
 * <p>扣留的内容直接丢弃、不补发：流结束时 AgentController 会无条件发一次 REPLACE 事件整体刷新气泡，
 * 其文本由累计的完整回复推导，扣掉的意图 JSON 本就不该出现在展示文本里；而意图解析、动作卡片与
 * 会话落库也都基于完整回复而非转发的分片，故丢弃不影响任何下游。</p>
 *
 * <p>线程封闭：实例为单次对话请求私有，仅在 Reactor 订阅者回调（onNext 串行）中调用，无需同步。</p>
 */
public final class IntentTagStreamFilter {

    /** 未决缓冲：已收下但尚未决定去留的文本（可能含不完整标记） */
    private final StringBuilder buffer = new StringBuilder();

    /** 是否正处于一个未闭合的 {@code <intent>} 块内 */
    private boolean insideTag;

    /** 是否扣留过意图标记块 — 供调用方在解析不出意图时判定「模型输出异常」而非「本无意图」 */
    private boolean suppressed;

    /**
     * 处理一个内容分片，返回本次可安全转发的前缀。
     *
     * <p>返回值可能为空串（整片都进缓冲），也可能包含「上一个分片扣住、本分片判定为正文后释放」
     * 的文本——调用方只需按返回顺序转发即可。</p>
     *
     * @param delta 模型输出的内容分片
     * @return 可转发文本（不含任何意图标记及其内容），无内容可转发时为空串
     */
    public String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        buffer.append(delta);
        StringBuilder forwardable = new StringBuilder();
        // 每轮或消费掉一个完整标记块、或判定出「已无法继续推进」——后者置 false 收尾本分片
        boolean scanning = true;
        while (scanning) {
            if (insideTag) {
                int close = buffer.indexOf(IntentRouter.INTENT_TAG_CLOSE);
                if (close < 0) {
                    // 未闭合：块内内容已确定要丢弃，只保留可能是闭合标记前缀的尾部
                    discardHeadKeepingSuffix(buffer, IntentRouter.INTENT_TAG_CLOSE);
                    scanning = false;
                } else {
                    // 整块（含闭合标记）丢弃，其后内容回到块外继续判定
                    buffer.delete(0, close + IntentRouter.INTENT_TAG_CLOSE.length());
                    insideTag = false;
                }
            } else {
                int open = buffer.indexOf(IntentRouter.INTENT_TAG_OPEN);
                if (open < 0) {
                    // 无完整起始标记：转发安全的头部，保留可能是标记前缀的尾部
                    int keep = possibleTagPrefixLength(buffer, IntentRouter.INTENT_TAG_OPEN);
                    forwardable.append(buffer, 0, buffer.length() - keep);
                    buffer.delete(0, buffer.length() - keep);
                    scanning = false;
                } else {
                    // 标记之前的正文立即转发（同一分片「正文 + 意图」的情况）
                    forwardable.append(buffer, 0, open);
                    buffer.delete(0, open + IntentRouter.INTENT_TAG_OPEN.length());
                    insideTag = true;
                    suppressed = true;
                }
            }
        }
        return forwardable.toString();
    }

    /**
     * 本次流中是否出现过 {@code <intent>} 标记（即是否扣留过内容）。
     *
     * <p>用于区分两种「没解析出意图」：本次回答本就没有写操作意图（正常），
     * 与模型输出了意图标记块却解析不出意图（模型输出异常，需要告警）。</p>
     *
     * @return true 表示至少出现过一次 {@code <intent>} 标记
     */
    public boolean suppressedAny() {
        return suppressed;
    }

    /**
     * 计算缓冲末尾有多少字符可能是标记的前缀（标记被分片劈开时需保留，不能提前转发）。
     *
     * @param text 缓冲内容
     * @param tag  完整标记
     * @return 需要保留的尾部长度，取值 [0, tag.length() - 1]
     */
    private static int possibleTagPrefixLength(StringBuilder text, String tag) {
        int max = Math.min(tag.length() - 1, text.length());
        for (int length = max; length > 0; length--) {
            if (regionMatches(text, text.length() - length, tag, 0, length)) {
                return length;
            }
        }
        return 0;
    }

    /**
     * 丢弃缓冲头部、只保留末尾可能是标记前缀的部分。
     *
     * @param text 缓冲内容（原地修改）
     * @param tag  完整标记
     */
    private static void discardHeadKeepingSuffix(StringBuilder text, String tag) {
        int keep = possibleTagPrefixLength(text, tag);
        text.delete(0, text.length() - keep);
    }

    /**
     * 比较缓冲片段与标记前缀是否逐字符相同。
     *
     * @param text   缓冲内容
     * @param offset 缓冲中的起始下标
     * @param tag    完整标记
     * @param tagOffset 标记中的起始下标
     * @param length 比较长度
     * @return true 表示完全相同
     */
    private static boolean regionMatches(StringBuilder text, int offset, String tag, int tagOffset, int length) {
        for (int i = 0; i < length; i++) {
            if (text.charAt(offset + i) != tag.charAt(tagOffset + i)) {
                return false;
            }
        }
        return true;
    }
}
