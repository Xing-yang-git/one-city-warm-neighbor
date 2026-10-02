package com.platform.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ai.common.PromptRepository;
import com.platform.ai.search.KnowledgeHit;
import com.platform.common.AgentMessageRole;
import com.platform.common.AiGenerationException;
import com.platform.common.BizException;
import com.platform.model.entity.Tenant;
import com.platform.model.entity.User;
import com.platform.repository.TenantRepository;
import com.platform.repository.UserRepository;
import com.platform.service.SensitiveWordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.net.SocketException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Agent「小邻」对话编排服务。
 *
 * <p>知识检索工具化（Step 3b）：去掉对非问候消息的无条件知识检索，检索改由模型按需调用
 * {@code search_knowledge} 工具（requestId 贯穿工具计数与命中缓存生命周期）；新增 4 个常用业务工具
 * （查日期/查通知/查互助/我的待办）。回复经防幻觉校验：移除非法 [N] 引用、检测资料外数字（仅记日志）。</p>
 *
 * <p>输出敏感词替换（Step 4c）：展示文本在发给前端前经敏感词掩码（命中词替换为 {@code ***}），
 * 写入会话历史保持原文，避免掩码污染后续上下文判断。关键链路日志（Step 4d）：拦截命中、LLM 耗时、
 * 路由决策（本次请求是否调用了知识工具）。</p>
 *
 * <p>长期记忆（本任务）：按次实时检索——每个用户消息以其自身为 query 检索用户压缩段摘要，
 * 直接注入 System Prompt 的 {@code {历史记忆}}（无窗口缓存、无 LLM 整合）；
 * 归档触发改走 archiveWindow（滑动窗口，最旧 N 条归档）；清空会话指令先 archiveRemaining 再 clearSession。</p>
 *
 * <p>写操作走 IntentRouter 解析模型返回的 JSON 意图，生成动作卡片（不自动落库，前端确认后走既有 API）。</p>
 */
@Slf4j
@Service
public class AgentService {

    private final AgentPromptBuilder promptBuilder;
    private final PromptRepository promptRepository;
    private final AgentToolDispatcher toolDispatcher;
    private final IntentRouter intentRouter;
    private final SessionService sessionService;
    private final ArchiveService archiveService;
    private final MessagePreFilter preFilter;
    private final OpenAiChatModel deepseekChatModel;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;
    private final SensitiveWordService sensitiveWordService;
    private final MemoryRetrievalService memoryRetrievalService;
    private final AgentSessionGuard sessionGuard;

    /** 归档轮次阈值（达到该轮数即触发归档；1 次用户提问+agent 回复=1 轮，内部 ×2 换算消息数，
     *  与 max-turns×2 截断封顶一致，防阈值 > 截断上限永不触发） */
    @Value("${ai.agent.archive-turn-count:10}")
    private int archiveTurnCount;

    /** DeepSeek 流式连接失败自动重试次数（外部 AI keep-alive 连接空闲被服务端关闭，复用陈旧连接会 Connection reset） */
    private static final int STREAM_CONNECT_RETRY = 1;

    /** 流式掩码窗口下限：词库为空或词极短时仍保留的最小尾巴，避免窗口退化为 0 而漏掉跨分片词 */
    private static final int MIN_STREAM_CARRY_WINDOW = 8;

    /** 流式掩码窗口的干扰余量：覆盖敏感词两截之间夹的空格/标点/emoji（归一化会剔除它们但仍占窗口） */
    private static final int STREAM_CARRY_INTERFERENCE_MARGIN = 12;

    /** 问候语关键词（纯寒暄走快速通道，不调外部 API，保证秒回；含常用礼貌语，去重后无重复项） */
    private static final List<String> GREETING_KEYWORDS = List.of(
            "你好", "您好", "嗨", "哈喽", "hello", "hi", "在吗", "在不在",
            "早上好", "中午好", "下午好", "晚上好", "早安", "午安", "晚安",
            "嘿嘿", "哈哈", "哈喽哈", "嗨嗨", "在嘛", "在不在呀", "有人吗", "有人在吗",
            "打扰一下", "请问在吗", "早", "新年好", "节日快乐", "周末好", "好久不见", "好久没见",
            "谢谢", "感谢", "再见", "拜拜");

    // ==================== 防幻觉常量 ====================

    /** 引用标记 [N]（N 为数字），用于来源引用校验 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\d+\\]");

    /** 资料型数字/电话模式：连字符号码、连续 ≥5 位数字、400 段、1xx 短码 */
    private static final Pattern DATA_NUMBER_PATTERN = Pattern.compile(
            "(?<![\\d-])\\d{1,4}(?:[- ]\\d{1,4}){1,2}[- ]\\d{3,}(?![\\d-])" +
                    "|(?<!\\d)\\d{5,}(?!\\d)" +
                    "|(?<![\\d-])400\\d{0,3}(?![\\d-])" +
                    "|(?<![\\d-])1\\d{2}(?![\\d-])");

    /** 相对量词起始字符（数字后紧跟这些中文量词视为相对表达，如「2天后」「3小时后」，不校验） */
    private static final String RELATIVE_TIME_CHARS = "天小时分秒钟";

    public AgentService(AgentPromptBuilder promptBuilder,
                        PromptRepository promptRepository,
                        AgentToolDispatcher toolDispatcher,
                        IntentRouter intentRouter,
                        SessionService sessionService,
                        ArchiveService archiveService,
                        OpenAiChatModel deepseekChatModel,
                        UserRepository userRepository,
                        TenantRepository tenantRepository,
                        ObjectMapper objectMapper,
                        MessagePreFilter preFilter,
                        SensitiveWordService sensitiveWordService,
                        MemoryRetrievalService memoryRetrievalService,
                        AgentSessionGuard sessionGuard) {
        this.promptBuilder = promptBuilder;
        this.promptRepository = promptRepository;
        this.toolDispatcher = toolDispatcher;
        this.intentRouter = intentRouter;
        this.sessionService = sessionService;
        this.archiveService = archiveService;
        this.deepseekChatModel = deepseekChatModel;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
        this.preFilter = preFilter;
        this.sensitiveWordService = sensitiveWordService;
        this.memoryRetrievalService = memoryRetrievalService;
        this.sessionGuard = sessionGuard;
    }

    /**
     * 在当前用户的热会话互斥下写入一问一答（user + assistant）并触发阈值归档。
     *
     * <p>两次 append 必须处于同一临界区：若中途被另一请求插队，会得到 u1、u2、a1 的顺序，
     * 即「连续两条用户消息」脏数据。{@code archiveIfNeeded} 一并纳入同一个临界区，
     * 顺带消除它「先读消息数判阈值、再调归档」两次读之间的 TOCTOU 窗口。</p>
     *
     * @param userId         当前用户 ID
     * @param userMessage    用户消息
     * @param assistantReply 助手回复（写入历史的原文）
     * @param sourcesJson    引用来源 JSON（可为 null）
     * @param actionsJson    动作卡片 JSON（可为 null）
     */
    private void appendTurn(Long userId, String userMessage, String assistantReply,
                            String sourcesJson, String actionsJson) {
        sessionGuard.executeExclusive(userId, () -> {
            sessionService.append(userId, AgentMessageRole.USER, userMessage, null, null);
            sessionService.append(userId, AgentMessageRole.ASSISTANT, assistantReply, sourcesJson, actionsJson);
            archiveIfNeeded(userId);
        });
    }

    /**
     * 在当前用户的热会话互斥下写入一条问候问答（user + assistant），<b>不触发归档</b>。
     *
     * <p>与普通问答分开：问候走本地文案快速通道，目标是不依赖任何外部服务的秒回；
     * 若在此路径同步触发归档（PG 写入），会给一条原本不会失败的链路引入新的失败点。
     * 与 {@link #appendTurn} 的唯一差别即在不调用 archiveIfNeeded，两条问候路径（阻塞版/流式版）均走本方法以保持一致。</p>
     *
     * @param userId       当前用户 ID
     * @param userMessage  用户消息
     * @param greetingReply 问候回复文案
     */
    private void appendGreetingTurn(Long userId, String userMessage, String greetingReply) {
        sessionGuard.executeExclusive(userId, () -> {
            sessionService.append(userId, AgentMessageRole.USER, userMessage, null, null);
            sessionService.append(userId, AgentMessageRole.ASSISTANT, greetingReply, null, null);
        });
    }

    /**
     * 在当前用户的热会话互斥下执行会话结束语义：归档剩余全部消息 + 清空热会话。
     *
     * <p>两句必须处于同一临界区：{@code clearSession} 是整体覆盖写，若中间被 append 插队，
     * 那条消息既不在归档表也不在热会话里，永久丢失。</p>
     *
     * @param userId 当前用户 ID
     */
    private void archiveAndClear(Long userId) {
        sessionGuard.executeExclusive(userId, () -> {
            archiveService.archiveRemaining(userId);
            sessionService.clearSession(userId);
        });
    }

    /**
     * 处理一次对话（阻塞版）：消息前置过滤 → Prompt 组装（含读工具）→ 生成回复 → 意图解析 → 防幻觉校验。
     *
     * <p>供非流式调用方与单元测试使用；线上流式走 {@link #chatStream}。
     * 被前置过滤器拦截的消息直接返回本地文案，不调 LLM、不写入会话历史。</p>
     *
     * @param userId  当前用户 ID（从认证上下文取，租户隔离；工具执行时注入，模型不可伪造）
     * @param message 用户消息
     * @return 回复文本 + 引用来源 + 动作卡片
     */
    public AgentChatResult chat(Long userId, String message) {
        // 新会话入口检测：热会话为空即新对话（首次/清空/退出/空闲归档后），重置上一条消息记录，
        // 避免新会话第一条与历史会话最后一条被规则 6 误判为重复（覆盖所有会话结束路径，含空闲归档）
        List<AgentSession.AgentMessageItem> history = sessionService.getHistory(userId);
        if (history == null || history.isEmpty()) {
            preFilter.resetUser(userId);
        }
        String greetingReply = greetingReply(message);
        if (greetingReply != null) {
            appendGreetingTurn(userId, message, greetingReply);
            // 落库成功后才更新「上一条消息」：否则锁忙抛异常时问候未落库却已计入，
            // 下次同样的问候会被误判为连续重复。问候命中仍要更新是为避免
            // 「今天星期几 → 你好 → 今天星期几」被误判为重复
            preFilter.recordMessage(userId, message);
            return new AgentChatResult(greetingReply, List.of(), List.of());
        }

        // 消息前置过滤器：低质消息直接本地应答，不调 LLM、不写会话历史
        MessagePreFilter.PreFilterResult filter = preFilter.process(userId, message);
        if (filter.blockReply() != null) {
            if (filter.clearSession()) {
                // 清空即归档：先归档剩余全部消息（纯 DB 搬运，会话结束语义），再清空热会话
                archiveAndClear(userId);
                // 清空会话是会话边界：重置上一条消息记录，避免新会话第一条与历史会话最后一条重复误判
                preFilter.resetUser(userId);
            }
            log.info("Agent 消息被前置过滤器拦截: userId={}, clearSession={}, reply={}",
                    userId, filter.clearSession(), filter.blockReply());
            return new AgentChatResult(filter.blockReply(), List.of(), List.of());
        }

        // 请求级上下文：requestId 贯穿工具计数与命中缓存生命周期
        String requestId = UUID.randomUUID().toString();
        toolDispatcher.reset(requestId);
        try {
            Prompt prompt = prepare(userId, filter.message(), filter.injectionHint(), requestId);

            // deepseek 调用（Spring AI 自动执行读工具循环）；非流式首字延迟 ≈ 总耗时，故仅记录总耗时
            long llmStartMs = System.currentTimeMillis();
            ChatResponse response = deepseekChatModel.call(prompt);
            log.info("Agent LLM 调用耗时（非流式）: userId={}, 总耗时={}ms",
                    userId, System.currentTimeMillis() - llmStartMs);
            String reply = response.getResult().getOutput().getText();
            if (reply == null || reply.isBlank()) {
                throw new AiGenerationException("AI 回复为空，请稍后重试");
            }

            AgentAction action = intentRouter.parse(reply);
            String clean = cleanReply(reply, action);
            // 防幻觉：取回本次工具命中（引用来源），移除非法 [N]、检测资料外数字（仅记日志）
            List<KnowledgeHit> sources = toolDispatcher.takeHits(requestId);
            String guarded = applyHallucinationGuard(userId, clean, sources);
            // 输出敏感词替换：只影响展示文本；历史保留 guarded 原文，避免掩码污染后续上下文判断
            String display = maskForDisplay(guarded);
            // 路由决策日志：本次请求是否调用了知识工具（以命中缓存是否有结果为判断依据）
            log.info("Agent 路由决策: userId={}, 本次调用了知识工具={}", userId, !sources.isEmpty());

            // 写入热会话（user + assistant），并触发消息数阈值归档
            List<AgentAction> actions = action != null ? List.of(action) : List.of();
            appendTurn(userId, message, guarded, writeJsonOrNull(sources), writeJsonOrNull(actions));

            return new AgentChatResult(display, sources, actions);
        } finally {
            // 无论成功或异常都清理请求级工具状态（计数 + 命中缓存）
            toolDispatcher.reset(requestId);
        }
    }

    /**
     * 流式对话入口：问候/前置过滤器拦截走快速通道秒回；否则完成 Prompt 组装后返回模型流。
     *
     * <p>流式的意图解析、会话落库由 Controller 在流结束后回调
     * {@link #completeStream} 完成（因为需要累计到完整回复才能解析 JSON 意图）。
     * 引用来源在流结束时由 Controller 从 {@link AgentToolDispatcher#takeHits} 取回。</p>
     *
     * @param userId  当前用户 ID
     * @param message 用户消息
     * @return 对话流（问候为 {@code isGreeting()}、拦截为 {@code isBlocked()} 快捷回复，普通为模型内容流）
     */
    public AgentChatStream chatStream(Long userId, String message) {
        // 获取当前系统时间戳
        long setupStartMs = System.currentTimeMillis();
        // 新会话入口检测：热会话为空即新对话（首次/清空/退出/空闲归档后），重置上一条消息记录，
        // 避免新会话第一条与历史会话最后一条被规则 6 误判为重复（覆盖所有会话结束路径，含空闲归档）
        List<AgentSession.AgentMessageItem> history = sessionService.getHistory(userId);
        if (history == null || history.isEmpty()) {
            preFilter.resetUser(userId);
        }
        // 问候语快速通道：纯寒暄不调任何外部 API（LLM），秒回保证 3~5s 目标
        String greetingReply = greetingReply(message);
        if (greetingReply != null) {
            // 问候也写入热会话，须与普通问答走同一临界区（否则与并发请求交错产生连续同角色消息）
            appendGreetingTurn(userId, message, greetingReply);
            // 落库成功后才更新「上一条消息」（理由同 chat()：锁忙抛异常时不该把未落库的问候计入）
            preFilter.recordMessage(userId, message);
            return AgentChatStream.greeting(greetingReply);
        }

        // 消息前置过滤器：拦截（空消息/纯符号/控制指令/纯表情/重复/超长/敏感词）直接本地应答
        MessagePreFilter.PreFilterResult filter = preFilter.process(userId, message);
        if (filter.blockReply() != null) {
            if (filter.clearSession()) {
                // 清空即归档：先归档剩余全部消息（纯 DB 搬运，会话结束语义），再清空热会话
                archiveAndClear(userId);
                // 清空会话是会话边界：重置上一条消息记录，避免新会话第一条与历史会话最后一条重复误判
                preFilter.resetUser(userId);
            }
            log.info("Agent 消息被前置过滤器拦截: userId={}, clearSession={}, reply={}",
                    userId, filter.clearSession(), filter.blockReply());
            return AgentChatStream.blocked(filter.blockReply(), filter.clearSession());
        }

        // 请求级上下文：requestId 贯穿工具计数与命中缓存生命周期（流结束时取回并清理）
        String requestId = UUID.randomUUID().toString();
        toolDispatcher.reset(requestId);

        // 放行：用清洗后的消息继续原有链路（注入特征提示仅附加进模型输入）
        Prompt prompt = prepare(userId, filter.message(), filter.injectionHint(), requestId);
        // 前置准备耗时（历史检索 + 前置过滤 + 记忆检索 + 提示组装），便于定位首字延迟前的耗时分布
        log.info("Agent 流式前置准备耗时: userId={}, 耗时={}ms", userId, System.currentTimeMillis() - setupStartMs);
        // Spring AI 真流式：工具调用在流内自动执行（流式工具循环），内容分块直出；
        // 包一层连接失败重试（复用陈旧 keep-alive 连接抛 Connection reset 时自动重建，见 withStreamConnectRetry）
        Flux<ChatResponse> contentFlux = withStreamConnectRetry(deepseekChatModel.stream(prompt));
        return new AgentChatStream(null, null, contentFlux, userId, message, requestId, false);
    }

    /**
     * DeepSeek 流式连接失败自动重试：外部 AI API 的 keep-alive 连接空闲后会被服务端关闭，
     * WebClient（Reactor Netty）连接池复用陈旧连接会抛 Connection reset——SSE 首包即失败，
     * 前端报"网络中断"、用户需手动重试才能恢复（每次进入小程序首次消息必现）。
     *
     * <p>仅当「未收到任何内容」时重试一次：连接建立阶段失败（尚未 emit onNext）重建连接安全；
     * 流中途失败（已收到内容）重试会重复输出，不重试。</p>
     *
     * @param stream DeepSeek 原始内容流
     * @return 带连接失败重试的内容流
     */
    private Flux<ChatResponse> withStreamConnectRetry(Flux<ChatResponse> stream) {
        AtomicBoolean emittedAny = new AtomicBoolean(false);
        return stream
                .doOnNext(cr -> emittedAny.set(true))
                .retryWhen(Retry.max(STREAM_CONNECT_RETRY)
                        .filter(ex -> !emittedAny.get() && isConnectionReset(ex)));
    }

    /** 是否为 DeepSeek 连接重置异常（WebClient 抛、cause 为 SocketException "Connection reset"） */
    private boolean isConnectionReset(Throwable ex) {
        if (ex instanceof WebClientRequestException) {
            Throwable cause = ex.getCause();
            return cause instanceof SocketException && "Connection reset".equals(cause.getMessage());
        }
        return false;
    }

    /**
     * 流式结束后回填会话并触发归档（由 Controller 在累计完整回复后调用）。
     *
     * @param userId         当前用户 ID
     * @param userMessage    用户消息
     * @param assistantReply 写入历史的助手回复（已剔除意图 JSON、经防幻觉引用校验，但未做敏感词掩码——历史保留原文）
     * @param hits           引用来源（requestId 缓存取回的工具命中）
     * @param action         写操作意图（可为 null）
     * @param requestId      请求 ID（用于清理工具计数与命中缓存）
     */
    public void completeStream(Long userId, String userMessage, String assistantReply,
                               List<KnowledgeHit> hits, AgentAction action, String requestId) {
        try {
            List<AgentAction> actions = action != null ? List.of(action) : List.of();
            appendTurn(userId, userMessage, assistantReply, writeJsonOrNull(hits), writeJsonOrNull(actions));
            // 路由决策日志：本次请求是否调用了知识工具（以命中缓存是否有结果为判断依据）
            log.info("Agent 路由决策: userId={}, 本次调用了知识工具={}", userId, hits != null && !hits.isEmpty());
        } finally {
            // 请求级工具状态清理（计数 + 命中缓存）必须放 finally：appendTurn 现在会因热会话锁等待超时抛异常，
            // 若仍按顺序调用，异常会跳过清理，导致本次请求的计数与命中缓存残留在 dispatcher 中
            toolDispatcher.reset(requestId);
        }
    }

    /**
     * Prompt 组装（含读工具注册）。
     *
     * @param userId        当前用户 ID
     * @param message       清洗后的用户消息
     * @param injectionHint 注入特征提示（非空时附加进送入模型的用户消息，历史保持原文）
     * @param requestId     请求 ID（随工具闭包传入，供计数与命中缓存使用）
     * @return 组装好的 Prompt
     */
    private Prompt prepare(Long userId, String message, String injectionHint, String requestId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BizException("用户不存在"));
        String tenantName = resolveTenantName(user.getTenantId());

        // 读取多轮历史（Redis 热会话，Redis 不可用降级为空）
        List<AgentSession.AgentMessageItem> history = sessionService.getHistory(userId);

        // 按次实时记忆检索：每个用户消息以其自身为 query 检索压缩段摘要，直接注入 {历史记忆}（无窗口缓存）
        String memoryText = memoryRetrievalService.retrieveMemory(userId, message);

        // 注入特征提示：仅附加进本次请求送入模型的用户消息（与用户内容用空行明确分隔）
        String modelUserMessage = injectionHint == null
                ? message
                : message + "\n\n（系统提示：" + injectionHint + "）";

        // 组装 Prompt：消息（system + 历史 + user）+ 读工具注册 + 参数（知识检索由模型按需调工具，不再无条件注入）
        List<Message> messages = promptBuilder.buildMessages(tenantName, modelUserMessage, history, memoryText);
        return new Prompt(messages, OpenAiChatOptions.builder()
                .temperature(0.2)
                // reasoning 模型思维链占用大，1024 预留足够空间
                .maxTokens(1024)
                .toolCallbacks(buildToolCallbacks(userId, requestId))
                .build());
    }

    /**
     * 剔除意图 JSON 后的展示回复（空则替换为友好提示）。
     *
     * <p>流式 Controller 在累计完整回复后也调用此方法计算最终展示文案。</p>
     *
     * @param reply  模型原始回复
     * @param action 解析出的写操作意图（可为 null）
     * @return 气泡展示文本
     */
    public String cleanReply(String reply, AgentAction action) {
        String displayReply = reply == null ? "" : reply.trim();
        // 坑3：模型偶发用 ```markdown ... ``` 包裹整段正文，剥掉首尾代码块标记，避免展示露出 ``` 字符
        if (displayReply.startsWith("```")) {
            int firstNewline = displayReply.indexOf('\n');
            if (firstNewline > 0) {
                displayReply = displayReply.substring(firstNewline + 1);
            }
            int lastFence = displayReply.lastIndexOf("```");
            if (lastFence > 0) {
                displayReply = displayReply.substring(0, lastFence);
            }
            displayReply = displayReply.trim();
        }
        if (action != null) {
            displayReply = intentRouter.stripJson(displayReply);
            if (displayReply.isBlank()) {
                displayReply = "已为您准备好发布草稿，请点击下方卡片确认填写。";
            }
        }
        return displayReply;
    }

    /**
     * 对展示文本做敏感词掩码（命中词替换为 {@code ***}）。
     *
     * <p>只影响发给前端的展示文本；写入会话历史的内容保持原文（不调用本方法），
     * 避免掩码污染后续多轮对话的上下文判断。流式 Controller 对每个分块与最终展示文本均调用。</p>
     *
     * @param text 展示文本
     * @return 掩码后的文本（无命中时原样返回）
     */
    public String maskForDisplay(String text) {
        return sensitiveWordService.replace(text);
    }

    /**
     * 流式转发的敏感词跨分片掩码窗口长度（保留的尾部字符数）。
     *
     * <p>窗口下界由词库决定而非拍脑袋：敏感词若被分片边界切断，前半截一旦转发出去就再也无法掩码，
     * 故窗口必须覆盖「最长词长 − 1」（最坏情况是词被切成 1 个字符 + 剩下的）。词库由 B端增删，
     * 写死常数会在运营加长词后静默漏词。</p>
     *
     * <p>再叠加 {@link #STREAM_CARRY_INTERFERENCE_MARGIN} 干扰余量：归一化会剔除空格/标点/emoji，
     * 故同一敏感词的两个半截之间可能夹任意多个干扰字符，这些字符同样占用窗口。</p>
     *
     * @return 掩码窗口长度（字符数）
     */
    public int streamCarryWindowLength() {
        return Math.max(sensitiveWordService.longestWordLength() - 1, MIN_STREAM_CARRY_WINDOW)
                + STREAM_CARRY_INTERFERENCE_MARGIN;
    }

    /**
     * 防幻觉校验：先移除超出注入资料条数范围的非法 [N] 引用，再检测资料中不存在的数字（仅记日志）。
     *
     * <p>调用方保证传入的 {@code reply} 已是 cleanReply 处理后的文本（剔除 JSON 意图），
     * 数字校验输入即引用校验后的文本。</p>
     *
     * @param userId  当前用户 ID（日志上下文）
     * @param reply   cleanReply 后的回复文本
     * @param sources 本次请求工具命中列表（引用条数范围 = 命中条数；无命中则任何 [N] 都非法）
     * @return 移除非法引用后的文本（数字校验只记日志不改文本）
     */
    public String applyHallucinationGuard(Long userId, String reply, List<KnowledgeHit> sources) {
        if (reply == null) {
            return "";
        }
        String text = reply.trim();
        int hitCount = sources == null ? 0 : sources.size();
        // 1) 来源引用校验：N 超出条数范围 → 移除该非法引用并记日志
        text = stripInvalidCitations(userId, text, hitCount);
        // 2) 关键数字校验：与注入资料对比，资料中不存在的数字记幻觉信号日志（不修改文本）
        checkNumbersAgainstSources(userId, text, sources);
        return text;
    }

    /**
     * 移除回复中超出注入资料条数范围的 [N] 引用（无命中时任何 [N] 都非法）。
     *
     * @param userId   当前用户 ID（日志上下文）
     * @param text     校验前文本
     * @param hitCount 本次注入资料条数
     * @return 移除非法引用后的文本
     */
    private String stripInvalidCitations(Long userId, String text, int hitCount) {
        if (text.isEmpty()) {
            return text;
        }
        Matcher matcher = CITATION_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean modified = false;
        while (matcher.find()) {
            String token = matcher.group();
            int n = Integer.parseInt(token.substring(1, token.length() - 1));
            if (n < 1 || n > hitCount) {
                log.warn("Agent 回复含非法引用已移除: userId={}, 引用编号={}, 注入资料条数={}",
                        userId, n, hitCount);
                modified = true;
                matcher.appendReplacement(sb, "");
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(token));
            }
        }
        matcher.appendTail(sb);
        return modified ? sb.toString() : text;
    }

    /**
     * 提取回复中的资料型数字/电话模式，与本次注入资料文本对比，资料中不存在的数字记幻觉信号日志。
     *
     * <p>保守处理：只记日志、不修改回复文本。相对表达（如「2天后」「3小时后」）不校验。</p>
     *
     * @param userId  当前用户 ID（日志上下文）
     * @param text    引用校验后的文本
     * @param sources 本次注入资料（content/title/source 拼接作为比对语料）
     */
    private void checkNumbersAgainstSources(Long userId, String text, List<KnowledgeHit> sources) {
        StringBuilder corpus = new StringBuilder();
        if (sources != null) {
            for (KnowledgeHit hit : sources) {
                if (hit.content() != null) {
                    corpus.append(hit.content()).append(' ');
                }
                if (hit.title() != null) {
                    corpus.append(hit.title()).append(' ');
                }
                if (hit.source() != null) {
                    corpus.append(hit.source()).append(' ');
                }
            }
        }
        // 与资料语料做同样的归一化（去空白与连字符），保证「400-168-6688」与「4001686688」可比
        String searchableCorpus = corpus.toString().replaceAll("[\\s-]", "");
        Matcher matcher = DATA_NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            // 相对量词排除：数字后紧跟 天/小/时/分/秒/钟 等中文量词（如「2天后」）不校验
            if (matcher.end() < text.length()
                    && RELATIVE_TIME_CHARS.indexOf(text.charAt(matcher.end())) >= 0) {
                continue;
            }
            String digits = matcher.group().replaceAll("[\\s-]", "");
            if (searchableCorpus.indexOf(digits) < 0) {
                log.warn("Agent 回复含资料中不存在的数字（疑似幻觉信号）: userId={}, 数字={}",
                        userId, matcher.group());
            }
        }
    }

    /** 流式对话的结果载体：问候/拦截快捷回复 或 模型内容流 + 元数据 */
    public record AgentChatStream(String greetingReply, String blockReply,
                                  Flux<ChatResponse> contentFlux, Long userId, String userMessage,
                                  String requestId, boolean clearSession) {
        /** 构造问候快速通道结果（空内容流，不订阅；不涉及清空会话） */
        public static AgentChatStream greeting(String reply) {
            return new AgentChatStream(reply, null, Flux.empty(), null, null, null, false);
        }

        /**
         * 构造前置过滤器拦截结果（空内容流，不订阅，不写会话历史）。
         *
         * @param reply        拦截应答文案
         * @param clearSession 是否同时清空会话（/clear、/reset、「清除对话」等控制指令为 true，
         *                     前端收到后同步清空消息列表）
         */
        public static AgentChatStream blocked(String reply, boolean clearSession) {
            return new AgentChatStream(null, reply, Flux.empty(), null, null, null, clearSession);
        }

        /** 是否为问候快速通道（是则直接播 greetingReply） */
        public boolean isGreeting() {
            return greetingReply != null;
        }

        /** 是否为前置过滤器拦截（是则直接播 blockReply） */
        public boolean isBlocked() {
            return blockReply != null;
        }
    }

    /**
     * 注册读工具（可安全自动执行）— userId/requestId 从认证上下文与请求上下文闭包注入，工具签名不含身份参数。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求 ID（工具计数与命中缓存 key）
     * @return ToolCallback 数组
     */
    private ToolCallback[] buildToolCallbacks(Long userId, String requestId) {
        log.info("Agent 读工具注册: userId={}, 模型={}", userId, deepseekChatModel.getClass().getSimpleName());
        // 工具描述统一从提示词仓库读取（prompts/agent/tools.md），改文案不动代码
        Properties toolProps = promptRepository.getProps(PromptRepository.KEY_AGENT_TOOLS);
        return new ToolCallback[]{
                FunctionToolCallback.builder("search_knowledge",
                                (KnowledgeSearchParams p) -> toolDispatcher.searchKnowledge(userId, requestId, p))
                        .description(toolProps.getProperty("search_knowledge"))
                        .inputType(KnowledgeSearchParams.class)
                        .build(),
                FunctionToolCallback.builder("query_date",
                                (DateQueryParams p) -> toolDispatcher.queryDate(userId, requestId, p))
                        .description(toolProps.getProperty("query_date"))
                        .inputType(DateQueryParams.class)
                        .build(),
                FunctionToolCallback.builder("query_notifications",
                                (VoidParams v) -> toolDispatcher.queryNotifications(userId, requestId, v))
                        .description(toolProps.getProperty("query_notifications"))
                        .inputType(VoidParams.class)
                        .build(),
                FunctionToolCallback.builder("query_help_requests",
                                (HelpSearchParams p) -> toolDispatcher.queryHelpRequests(userId, requestId, p))
                        .description(toolProps.getProperty("query_help_requests"))
                        .inputType(HelpSearchParams.class)
                        .build(),
                FunctionToolCallback.builder("my_todos",
                                (VoidParams v) -> toolDispatcher.myTodos(userId, requestId, v))
                        .description(toolProps.getProperty("my_todos"))
                        .inputType(VoidParams.class)
                        .build(),
                FunctionToolCallback.builder("search_items",
                                (SearchParams p) -> toolDispatcher.searchItems(userId, requestId, p))
                        .description(toolProps.getProperty("search_items"))
                        .inputType(SearchParams.class)
                        .build(),
                FunctionToolCallback.builder("my_posts",
                                (MyPostsParams p) -> toolDispatcher.myPosts(userId, requestId, p))
                        .description(toolProps.getProperty("my_posts"))
                        .inputType(MyPostsParams.class)
                        .build(),
                FunctionToolCallback.builder("my_borrows_due",
                                (VoidParams v) -> toolDispatcher.myBorrowsDue(userId, requestId, v))
                        .description(toolProps.getProperty("my_borrows_due"))
                        .inputType(VoidParams.class)
                        .build(),
                FunctionToolCallback.builder("generate_feedback",
                                (FeedbackParams p) -> toolDispatcher.generateFeedback(userId, requestId, p))
                        .description(toolProps.getProperty("generate_feedback"))
                        .inputType(FeedbackParams.class)
                        .build()
        };
    }

    /**
     * 触发归档（热会话消息数达到归档轮次 ×2，即滑动窗口归档最旧 N 条到 PG，保留剩余窗口）。
     *
     * @param userId 住户用户 ID
     */
    private void archiveIfNeeded(Long userId) {
        AgentSession session = sessionService.getSession(userId);
        int messageThreshold = archiveTurnCount * 2;
        if (session != null && session.getMessages().size() >= messageThreshold) {
            archiveService.archiveWindow(userId, messageThreshold);
        }
    }

    /**
     * 序列化为 JSON 字符串（归档 sources/actions 用），失败返回 null。
     *
     * @param data 数据对象（可为空列表）
     * @return JSON 字符串，或 null
     */
    private String writeJsonOrNull(Object data) {
        try {
            if (data == null) {
                return null;
            }
            String json = objectMapper.writeValueAsString(data);
            return "[]".equals(json) ? null : json;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析小区名称（未绑定小区时兜底为"本小区"）。
     *
     * @param tenantId 小区 ID，可能为 null
     * @return 小区名称
     */
    private String resolveTenantName(Long tenantId) {
        if (tenantId == null) {
            return "本小区";
        }
        return tenantRepository.findById(tenantId).map(Tenant::getName).orElse("本小区");
    }

    /**
     * 判定消息是否为纯问候/寒暄，是则返回快速回复文案（否则返回 null）。
     *
     * <p>判定规则：去空白与标点后，<b>以问候关键词开头</b>，且<b>问候语之后只剩语气词/语气符号</b>
     * （如"你好"、"你好呀"、"早上好"）才算纯寒暄。</p>
     *
     * <p>用 startsWith + 语气词后缀而不是 contains/长度阈值：确保「你好，我想知道物业几点下班」这类
     * 带真实问题的消息<b>一定不会</b>被误判进快速通道——它开头是"你好"但后面跟着实质内容，会正常走 LLM。</p>
     *
     * @param message 用户消息
     * @return 问候回复文案，或 null（非问候）
     */
    private String greetingReply(String message) {
        if (message == null) {
            return null;
        }
        String norm = message.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}。，！？!?～~…·]", "");
        if (norm.isEmpty()) {
            return null;
        }
        Properties greetingProps = promptRepository.getProps(PromptRepository.KEY_AGENT_REPLIES);
        for (String keyword : GREETING_KEYWORDS) {
            if (norm.startsWith(keyword)) {
                String rest = norm.substring(keyword.length());
                if (rest.isEmpty() || rest.matches("[呀啊哦啦哈嘛吧喔嗯吗呵]*")) {
                    // 场景化文案优先（greeting.<关键词>），未命中用 greeting.default；纯本地返回零 LLM 成本
                    return greetingProps.getProperty("greeting." + keyword,
                            greetingProps.getProperty("greeting.default"));
                }
            }
        }
        return null;
    }
}
