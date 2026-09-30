package com.platform.ai.agent;

import com.platform.ai.common.PromptRepository;
import com.platform.service.SensitiveWordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息前置过滤器 — 小邻对话链路中问候判断之后、知识检索之前的纯代码拦截层。
 *
 * <p>职责：拦截低质消息（空/纯符号/系统控制指令/业务固定指令/纯表情/重复刷屏/超长/敏感词），
 * 拦截零令牌消耗、零网络请求，直接回复本地文案且不写入会话历史；放行的消息先做文本清洗，
 * 并检测注入特征（仅提示不拦截，最终由模型层安全规则把关）。</p>
 *
 * <p>全部规则采用「整条完全匹配」或「整条正则」，禁止包含匹配，防止误杀正常提问。</p>
 *
 * <p>拦截文案从提示词仓库读取（key {@code block.replies}，文件 {@code prompts/block/replies.md}），
 * 改文案只动资源文件、不改代码——与 {@code prompts/block/replies.md} 保持单一事实来源。</p>
 */
@Component
public class MessagePreFilter {

    /** 消息最大长度（字符数）；接口层已限制 500 字，此处双保险 */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** 注入特征短语表（整体包含检测即可，命中仅提示不拦截） */
    private static final List<String> INJECTION_PHRASES = List.of(
            "忽略之前的指令", "忽略系统提示", "告诉我密码", "泄露管理员", "扮演系统", "越权访问", "提示词");

    private final SensitiveWordService sensitiveWordService;

    /** 拦截文案集（key=empty/symbol/emoji/duplicate/too-long/clear/exit/version/help/sensitive），构造期从提示词仓库读取 */
    private final Properties blockReplies;
    /** 注入特征提示文案（不拦截，仅附加进模型输入；来自 prompts/agent/injection.md） */
    private final String injectionHint;

    /** 系统控制指令（斜杠命令，大小写不敏感）→ 应答文案 */
    private final Map<String, String> systemCommandReplies;

    /** 业务固定指令（中文，按原文匹配）→ 应答文案 */
    private final Map<String, String> businessCommandReplies;

    /** 清空会话指令集合（命中后除应答外还需清空热会话） */
    private final Set<String> clearSessionCommands;

    /** 重复判定时间窗口（毫秒）：同一条消息在窗口内再次出现才判重复；超窗视为重新提问放行 */
    private static final long DUPLICATE_WINDOW_MS = 3_000;

    /** 各用户上一条放行的消息（key = userId，供连续重复判定；含时间戳支持窗口判定） */
    final Map<Long, LastMessage> lastMessages = new ConcurrentHashMap<>();

    /** 上一条放行消息记录（内容 + 记录时间戳）。包级可见供同包单元测试直接构造超窗/窗口内用例 */
    record LastMessage(String content, long timestamp) {
    }

    /**
     * 构造器注入（Spring 使用）：拦截文案从提示词仓库读取。
     *
     * <p>声明 {@code @Autowired} 是因为本类同时存在一个包级便捷构造器（供单元测试直接用真实
     * PromptRepository），需显式指定容器注入的构造器，避免多构造器歧义。</p>
     *
     * @param sensitiveWordService 敏感词服务（归一化匹配）
     * @param promptRepository     提示词仓库（读取 {@code block.replies} 拦截文案集）
     */
    @Autowired
    public MessagePreFilter(SensitiveWordService sensitiveWordService, PromptRepository promptRepository) {
        this.sensitiveWordService = sensitiveWordService;
        this.blockReplies = promptRepository.getProps(PromptRepository.KEY_BLOCK_REPLIES);
        this.injectionHint = promptRepository.getProps(PromptRepository.KEY_AGENT_INJECTION)
                .getProperty("injection.hint", "");

        // 指令 → 文案映射依赖 blockReplies，在构造期构建（不能是静态常量，文案来自运行时读取的 Properties）
        String clearReply = reply("clear");
        String helpReply = reply("help");
        String versionReply = reply("version");
        String exitReply = reply("exit");
        this.systemCommandReplies = Map.of(
                "/clear", clearReply,
                "/reset", clearReply,
                "/help", helpReply,
                "/version", versionReply);
        this.businessCommandReplies = Map.of(
                "帮助", helpReply,
                "使用说明", helpReply,
                "退出", exitReply,
                "清除对话", clearReply);
        this.clearSessionCommands = Set.of("/clear", "/reset", "清除对话");
    }

    /**
     * 测试便捷构造器：直接用真实 PromptRepository（从 classpath 加载提示词文件）。
     *
     * <p>仅供 {@code MessagePreFilterTest} 等单元测试使用——测试无需 mock PromptRepository，
     * 提示词文件在 mvn test 阶段已位于 classpath（与 PromptRepositoryTest 同一假设）。</p>
     *
     * @param sensitiveWordService 敏感词服务
     */
    MessagePreFilter(SensitiveWordService sensitiveWordService) {
        this(sensitiveWordService, new PromptRepository());
    }

    /**
     * 从拦截文案集读取指定 key 的文案。
     *
     * @param key 文案 key（empty/symbol/emoji/duplicate/too-long/clear/exit/version/help/sensitive）
     * @return 文案文本；key 缺失返回空串（key 缺失属于提示词文件契约破坏，由 PromptRepositoryTest 全等断言兜底）
     */
    private String reply(String key) {
        String value = blockReplies.getProperty(key);
        return value == null ? "" : value;
    }

    /**
     * 消息过滤结果载体。
     *
     * @param message       放行时的清洗后消息（非空 = 放行）
     * @param blockReply    拦截应答文案（非空 = 被拦截，直接回复本地文案）
     * @param clearSession  是否需清空会话（控制指令 /clear、/reset、清除对话 时为 true）
     * @param injectionHint 注入特征提示（非空 = 放行但附加提示给模型）
     */
    public record PreFilterResult(String message, String blockReply, boolean clearSession, String injectionHint) {
    }

    /**
     * 处理原始用户消息：按 9 类规则顺序拦截，命中即返回；放行则清洗文本并检测注入特征。
     *
     * @param userId     当前用户 ID（用于连续重复判定）
     * @param rawMessage 用户原始消息
     * @return 过滤结果（message 非空 = 放行；blockReply 非空 = 拦截）
     */
    public PreFilterResult process(Long userId, String rawMessage) {
        // 规则 1：空/纯空白（去首尾空白后为空）
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return blocked(reply("empty"));
        }
        String trimmed = rawMessage.trim();

        // 规则 2：纯符号（去空白后整条只含标点/符号，覆盖 @ ￥ & 等非标点符号类）
        if (isSymbolOrPunctuationOnly(trimmed)) {
            return blocked(reply("symbol"));
        }

        // 规则 3：系统控制指令（斜杠命令，转小写后整条完全匹配）
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (systemCommandReplies.containsKey(lower)) {
            return commandResult(lower, systemCommandReplies.get(lower));
        }

        // 规则 4：业务固定指令（中文按原文整条完全匹配）
        if (businessCommandReplies.containsKey(trimmed)) {
            return commandResult(trimmed, businessCommandReplies.get(trimmed));
        }

        // 规则 5：纯表情（整条只含 emoji 及修饰字符，如 👍、😂、👍🏻）
        if (isEmojiOnly(trimmed)) {
            return blocked(reply("emoji"));
        }

        // 规则 6：重复刷屏（同一条消息在短时间窗口内再次出现才拦截；
        // 超窗视为重新提问——覆盖切后台回来、新会话等会话状态不可靠的场景，不误伤隔段时间的重问）
        if (userId != null) {
            LastMessage last = lastMessages.get(userId);
            if (last != null && trimmed.equals(last.content())
                    && System.currentTimeMillis() - last.timestamp() <= DUPLICATE_WINDOW_MS) {
                return blocked(reply("duplicate"));
            }
        }

        // 规则 7：超长
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            return blocked(reply("too-long"));
        }

        // 规则 8：敏感词（归一化匹配，命中任一启用词）
        if (sensitiveWordService.contains(trimmed)) {
            return blocked(reply("sensitive"));
        }

        // 规则 9：注入特征（不拦截，仅设置提示）
        String hint = detectInjection(trimmed);

        // 文本清洗：折叠连续换行 → 去首尾空白 → 去乱码控制字符
        String cleaned = clean(trimmed);
        if (cleaned.isEmpty()) {
            // 清洗后为空 → 归入空消息拦截
            return blocked(reply("empty"));
        }

        // 放行：记录本条消息供重复判定（拦截/问候命中的消息不更新）
        if (userId != null) {
            lastMessages.put(userId, new LastMessage(trimmed, System.currentTimeMillis()));
        }
        return hint == null ? pass(cleaned) : passWithHint(cleaned, hint);
    }

    /**
     * 记录一条用户消息为上一条消息（供重复判定）。
     *
     * <p>供问候等不进入 {@link #process} 的消息调用——问候命中时也应更新「上一条消息」，
     * 否则「今天星期几 → 你好 → 今天星期几」会被误判为连续重复（问候在重复链中成了透明消息）。
     * 记录逻辑与 process 放行分支一致：去首尾空白后存入。</p>
     *
     * @param userId  住户用户 ID（null 时不记录）
     * @param message 用户原始消息（内部 trim 后记录）
     */
    public void recordMessage(Long userId, String message) {
        if (userId != null && message != null) {
            lastMessages.put(userId, new LastMessage(message.trim(), System.currentTimeMillis()));
        }
    }

    /**
     * 清除该用户的上一条消息记录（供会话边界调用）。
     *
     * <p>{@code lastMessages} 是模块级容器，跨会话/清空/退出不会自动清空——若不重置，
     * 新会话的第一条消息若恰好等于历史会话的最后一条放行消息（如再次点击同一快捷问题），
     * 会被规则 6 误判为重复刷屏。在清空会话、退出会话、恢复历史后调用，避免跨会话误拦截。</p>
     *
     * @param userId 住户用户 ID（null 时不处理）
     */
    public void resetUser(Long userId) {
        if (userId != null) {
            lastMessages.remove(userId);
        }
    }

    /**
     * 文本清洗：折叠连续 3 个以上换行为 2 个 → 去首尾空白 → 移除乱码控制字符。
     *
     * @param text 原始消息
     * @return 清洗后的消息（可能为空）
     */
    private String clean(String text) {
        String folded = text.replaceAll("\n{3,}", "\n\n");
        return stripControlChars(folded.trim());
    }

    /**
     * 移除乱码控制字符（U+0000-U+001F、U+007F-U+009F），保留换行/回车/制表符。
     *
     * @param text 文本
     * @return 移除控制字符后的文本
     */
    private String stripControlChars(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean control = (c >= 0x0000 && c <= 0x001F) || (c >= 0x007F && c <= 0x009F);
            boolean keep = c == '\n' || c == '\r' || c == '\t';
            if (!control || keep) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 判定去空白后整条消息是否只含标点或符号（排除 emoji，emoji 由规则 5 单独拦截）。
     *
     * <p>覆盖 Unicode 标点类别（中英文标点）与符号类别（数学符号 @ & +、货币符号 ￥ $ 等），
     * 否则纯符号消息如 {@code "@#￥%"} 会漏拦截走 LLM；但须排除 emoji 码点——emoji 多为
     * OTHER_SYMBOL（So），若一并纳入会抢先于规则 5 拦截纯 emoji，返回「请输入有效问题」
     * 而非 emoji 应答文案。</p>
     *
     * @param text 已去首尾空白的消息
     * @return true = 纯符号/标点消息（不含 emoji）
     */
    private boolean isSymbolOrPunctuationOnly(String text) {
        String noSpace = text.replaceAll("\\s", "");
        if (noSpace.isEmpty()) {
            return false;
        }
        return noSpace.codePoints().allMatch(this::isSymbolOrPunctuationCodepoint);
    }

    /**
     * 判断码点是否属于纯标点或纯符号类别，且不是 emoji 表情码点。
     *
     * <p>实际生效的符号类别为 MATH_SYMBOL（@ & +）与 CURRENCY_SYMBOL（￥ $）；
     * MODIFIER_SYMBOL（如 ^ `）与 OTHER_SYMBOL（如 © ®）已被 {@link #isEmojiCodepoint}
     * 归为 emoji 类而排除，不在此重复列出，避免死代码。</p>
     *
     * @param cp 码点
     * @return true = 纯标点/符号（非 emoji）
     */
    private boolean isSymbolOrPunctuationCodepoint(int cp) {
        int type = Character.getType(cp);
        boolean punctuation = type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
        boolean symbol = type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL;
        return (punctuation || symbol) && !isEmojiCodepoint(cp);
    }

    /**
     * 判定去空白后整条消息是否只含 emoji 表情（含修饰字符/零宽连接符/变体选择符）。
     *
     * @param text 已去首尾空白的消息
     * @return true = 纯表情消息
     */
    private boolean isEmojiOnly(String text) {
        String noSpace = text.replaceAll("\\s", "");
        return !noSpace.isEmpty() && noSpace.codePoints().allMatch(this::isEmojiCodepoint);
    }

    /**
     * 判断码点是否属于 emoji 或 emoji 修饰字符。
     *
     * <p>OTHER_SYMBOL/MODIFIER_SYMBOL 覆盖绝大多数 emoji 符号；NON_SPACING_MARK（变体选择符 U+FE0F）、
     * ENCLOSING_MARK（组合键帽 U+20E3）、FORMAT（零宽连接符 U+200D）为 emoji 组合修饰字符。</p>
     *
     * @param cp 码点
     * @return true = emoji 相关字符
     */
    private boolean isEmojiCodepoint(int cp) {
        int type = Character.getType(cp);
        return type == Character.OTHER_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT;
    }

    /**
     * 检测注入特征短语（整体包含检测），命中返回提示文案，否则返回 null。
     *
     * @param text 已去首尾空白的消息
     * @return 注入特征提示文案，或 null
     */
    private String detectInjection(String text) {
        for (String phrase : INJECTION_PHRASES) {
            if (text.contains(phrase)) {
                return injectionHint;
            }
        }
        return null;
    }

    /** 构造拦截结果（不清会话） */
    private static PreFilterResult blocked(String reply) {
        return new PreFilterResult(null, reply, false, null);
    }

    /** 构造控制指令结果：清会话指令额外携带 clearSession=true */
    private PreFilterResult commandResult(String command, String reply) {
        return new PreFilterResult(null, reply, clearSessionCommands.contains(command), null);
    }

    /** 构造放行结果（无注入提示） */
    private static PreFilterResult pass(String cleaned) {
        return new PreFilterResult(cleaned, null, false, null);
    }

    /** 构造放行结果（带注入特征提示） */
    private static PreFilterResult passWithHint(String cleaned, String hint) {
        return new PreFilterResult(cleaned, null, false, hint);
    }
}
