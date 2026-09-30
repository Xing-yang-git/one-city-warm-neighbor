package com.platform.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ai.search.KnowledgeHit;
import com.platform.common.AgentEventType;
import com.platform.common.AiGenerationException;
import com.platform.common.BizException;
import com.platform.common.Result;
import com.platform.model.dto.AgentChatRequest;
import com.platform.model.dto.AgentStreamEvent;
import com.platform.security.LoginUser;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI Agent「小邻」对话 REST API（C端，仅登录即可访问）。
 *
 * <p>提供：
 * <ul>
 *   <li>POST /chat — 流式对话（SSE 伪流式：决策轮非流式生成全文，后端分块播放）</li>
 *   <li>GET /suggestions — 快捷问题（常量，后续可 B端配置）</li>
 *   <li>GET /history — 历史会话列表（分页，排除软删）</li>
 *   <li>POST /history/{id}/resume — 恢复归档会话（按会话级 id，回填最近 N 轮，先归档当前热会话防丢失）</li>
 *   <li>DELETE /history — 批量软删历史会话（按会话级 id，保留审计，联动清理压缩段）</li>
 *   <li>POST /exit — 退出会话（前端通知：归档剩余全部 + 补压 RETRY 压缩段）</li>
 * </ul>
 *
 * <p>安全：内存限流防刷 API 额度；SSE error 事件只透出白名单业务文案，内部细节仅记日志。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final ArchiveService archiveService;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final IntentRouter intentRouter;
    private final AgentToolDispatcher toolDispatcher;
    private final MemoryCompressionService memoryCompressionService;
    private final MessagePreFilter preFilter;

    public AgentController(AgentService agentService,
                           ArchiveService archiveService,
                           ObjectMapper objectMapper,
                           RateLimitService rateLimitService,
                           @Qualifier("agentExecutor")
                           ThreadPoolTaskExecutor agentExecutor,
                           IntentRouter intentRouter,
                           AgentToolDispatcher toolDispatcher,
                           MemoryCompressionService memoryCompressionService,
                           MessagePreFilter preFilter) {
        this.agentService = agentService;
        this.archiveService = archiveService;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.agentExecutor = agentExecutor;
        this.intentRouter = intentRouter;
        this.toolDispatcher = toolDispatcher;
        this.memoryCompressionService = memoryCompressionService;
        this.preFilter = preFilter;
    }

    /**
     * 流式对话 — SSE 事件流（限流超限时返回 SSE error 事件，HTTP 状态仍为 200）。
     *
     * @param req  用户消息
     * @param auth 当前认证用户
     * @return SSE 流（start → answer 分块 → action/replace → sources → end / error；sources 在流结束后取回发出）
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AgentChatRequest req, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        // 记录请求开始时刻，用于统计接口耗时
        long requestStartMs = System.currentTimeMillis();
        // 敏感词跨分片掩码窗口：由词库最长词长推导（B端增删敏感词后自动跟随），见 AgentService
        int carryWindowLength = agentService.streamCarryWindowLength();

        // 限流：每分钟每用户配额，超限返回 SSE error 事件（友好文案提示，不开启对话流）
        if (!rateLimitService.tryAcquire(String.valueOf(userId))) {
            SseEmitter reject = new SseEmitter();
            try {
                reject.send(SseEmitter.event().name(AgentEventType.ERROR.getValue())
                        .data(toJson(AgentEventType.ERROR, "发送太频繁，请稍后再试")));
            } catch (IOException e) {
                log.debug("限流响应发送失败: userId={}", userId);
            }
            // 立即完成 SSE 流，避免客户端长时间挂起
            reject.complete();
            return reject;
        }

        // SSE 长连接（真流式，逐分块推送）：300 秒为兜底上限，正常路径由 doOnComplete 发 end 后主动 complete
        SseEmitter emitter = new SseEmitter(300_000L);
        // 客户端断开 / 超时：确保 emitter 完成。否则超时后仍在跑的内容流每次 send 都会抛异常，
        // 从 doOnNext 逃逸到 Reactor 的 onErrorDropped，污染日志
        emitter.onTimeout(emitter::complete);
        // 连接完成时，记录日志（可用于统计 SSE 连接时长）
        emitter.onCompletion(() -> log.debug("Agent SSE 流结束: userId={}", userId));

        // 提交任务 → 线程池挑选线程执行
        agentExecutor.execute(() -> {
            try {
                // 发送 SSE 事件
                safeSend(emitter, AgentEventType.START, Map.of("message", req.getMessage()));

                // 流式编排：问候秒回；普通对话走 Spring AI 真流式（工具在流内自动执行）
                AgentService.AgentChatStream stream = agentService.chatStream(userId, req.getMessage());

                if (stream.isGreeting()) {
                    // 问候快速通道：直接播固定文案（不订阅内容流）
                    safeSend(emitter, AgentEventType.ANSWER, stream.greetingReply());
                    safeSend(emitter, AgentEventType.END, Map.of("done", true));
                    emitter.complete();
                    return;
                }

                if (stream.isBlocked()) {
                    // 前置过滤器拦截：直接播本地文案（不订阅内容流，不写会话历史）；
                    // 清空会话指令（/clear、/reset、「清除对话」）额外发 clear 事件，让前端同步清空消息列表
                    safeSend(emitter, AgentEventType.ANSWER, stream.blockReply());
                    if (stream.clearSession()) {
                        safeSend(emitter, AgentEventType.CLEAR, Map.of("done", true));
                    }
                    safeSend(emitter, AgentEventType.END, Map.of("done", true));
                    emitter.complete();
                    return;
                }

                // 真流式：模型内容分块直发（引用来源在流结束时取回发出，见 doOnComplete——
                // 工具调用在流内完成，来源仅在流结束可知）。
                // 写操作意图 JSON（模型按 System Prompt 用 <intent> 包裹）不进内容流——
                // 由 IntentTagStreamFilter 跨分片扣留丢弃，前端全程看不到裸 JSON。
                StringBuilder full = new StringBuilder();
                // 敏感词跨 chunk 掩码：carry 保留上一分块尾部原文，供下个分块判全（整词命中）
                StringBuilder carry = new StringBuilder();
                // 意图标记过滤器：实例为本次请求私有（onNext 串行调用，无需同步）
                IntentTagStreamFilter intentFilter = new IntentTagStreamFilter();
                // 流式 LLM 耗时：首字延迟（首个非空分块到达）与总耗时（流结束）
                long streamStartMs = System.currentTimeMillis();
                AtomicLong firstTokenMs = new AtomicLong(-1L);

                // `contentFlux()`返回`Flux<DataBuffer>`，代表分段的流式数据
                stream.contentFlux()//
                        // 每收到一段数据（每个元素）就触发一次
                        .doOnNext(cr -> {
                            String delta = extractText(cr);
                            if (delta == null || delta.isEmpty()) {
                                return;
                            }
                            // 首字延迟：首个非空文本分块到达即记录（含网络与模型首字生成耗时）
                            firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - streamStartMs);
                            // full 保留原文全部内容：意图解析、动作卡片与会话落库都以它为准，
                            // 故过滤器扣留（丢弃）分片不影响任何下游，只是不让它显示给用户
                            full.append(delta);
                            String forwardable = intentFilter.accept(delta);
                            if (!forwardable.isEmpty()) {
                                forwardMasked(emitter, carry, forwardable, carryWindowLength);
                            }
                        })
                        // 数据流正常全部接收完毕触发
                        .doOnComplete(() -> {
                            String fullText = full.toString();
                            AgentAction action = intentRouter.parse(fullText);
                            // 扣留了意图标记块却解析不出意图 = 模型输出异常（内容已丢弃，事后无从回看）。
                            // 只记可判定的特征、不记原文：含花括号说明不是 JSON，含 intent 键说明 JSON 格式有误，
                            // 长度可区分「整块被截断」与「模型只写了标记没写内容」
                            if (action == null && intentFilter.suppressedAny()) {
                                log.warn("意图标记块解析失败（按普通回答兜底）: userId={}, 输出长度={}, 含花括号={}, 含intent键={}",
                                        userId, fullText.length(), fullText.indexOf('{') >= 0,
                                        fullText.contains("\"intent\""));
                            }
                            String cleanReply = agentService.cleanReply(fullText, action);
                            // 兜底：parse 成功但 intent 非法（parse 返回 null）时也要剥掉 JSON 不泄漏给用户。
                            // stripIntentJson 先按 <intent> 标记剥离（未闭合标记连同其后内容一并丢弃），
                            // 再按括号配对兜底无标记的 JSON；两者都定位不到时才按原文展示
                            String displayBase = intentRouter.stripIntentJson(cleanReply);
                            if (displayBase.isBlank()) {
                                displayBase = "已为您整理，请在发布页完善信息后发布。";
                            }
                            // 动作卡片：意图 JSON 已由 IntentTagStreamFilter 全程扣留，此处只补发卡片
                            if (action != null) {
                                safeSend(emitter, AgentEventType.ACTION, List.of(action));
                            }
                            // 运行时取回引用来源（requestId 缓存的工具命中；工具调用在流内完成，流结束才可知）
                            List<KnowledgeHit> sources = toolDispatcher.takeHits(stream.requestId());
                            // 防幻觉：移除非法 [N] 引用、检测资料外数字（仅记日志）→ 敏感词掩码（只影响展示文本）
                            String guarded = agentService.applyHallucinationGuard(userId, displayBase, sources);
                            String display = agentService.maskForDisplay(guarded);
                            // 始终 replace 为最终展示文本：这是气泡内容的唯一权威来源——既覆盖流式分块未发送的
                            // 尾部（敏感词 carry-over 保留段、被扣留的意图标记块），也覆盖防幻觉修正
                            // （前端 replace 整体刷新，幂等）
                            safeSend(emitter, AgentEventType.REPLACE, display);
                            // LLM 耗时日志（首字延迟 + 总耗时，流式）
                            log.info("Agent 流式 LLM 耗时: userId={}, 首字延迟={}ms, 总耗时={}ms",
                                    userId, Math.max(firstTokenMs.get(), 0L), System.currentTimeMillis() - streamStartMs);
                            // sources 事件在 answer 分块之后发出（无命中发空列表）
                            safeSend(emitter, AgentEventType.SOURCES, sources);
                            // 回填会话：历史保留展示前的原文 guarded（敏感词未替换，避免掩码污染后续上下文判断）
                            agentService.completeStream(userId, req.getMessage(), guarded, sources, action, stream.requestId());
                            // 端到端总耗时：含前置准备（拦截/意图/记忆检索）+ 流式生成 + 后处理 + 历史回填
                            log.info("Agent 对话端到端耗时: userId={}, 总耗时={}ms", userId, System.currentTimeMillis() - requestStartMs);
                            safeSend(emitter, AgentEventType.END, Map.of("done", true));
                            emitter.complete();
                        })
                        // 流发生异常时触发
                        .doOnError(e -> {
                            log.error("Agent 流式对话失败: userId={}", userId, e);
                            // 流失败同样清理请求级工具状态（计数 + 命中缓存），避免泄漏
                            toolDispatcher.reset(stream.requestId());
                            String safeMessage = toSafeMessage(e);
                            safeSend(emitter, AgentEventType.ERROR, safeMessage);
                            emitter.completeWithError(e);
                        })
                        .subscribe();
            } catch (Exception e) {
                // 安全映射：只透出白名单业务文案，内部细节（SQL/上游错误）仅记日志
                log.error("Agent 对话失败: userId={}", userId, e);
                String safeMessage = toSafeMessage(e);
                safeSend(emitter, AgentEventType.ERROR, safeMessage);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 将异常映射为对用户安全的文案（内部细节仅记日志）。
     *
     * @param e 异常（含响应式流的 Throwable）
     * @return 可透出的业务文案
     */
    private String toSafeMessage(Throwable e) {
        if (e instanceof AiGenerationException || e instanceof BizException) {
            return e.getMessage();
        }
        return "服务开小差了，请稍后重试";
    }

    /**
     * 从流式 ChatResponse 中提取文本增量。
     *
     * @param cr Spring AI 流式响应
     * @return 文本增量，或 null
     */
    private String extractText(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return null;
        }
        String text = cr.getResult().getOutput().getText();
        return text == null ? null : text;
    }

    /**
     * 敏感词跨分片掩码转发：combined = carry 尾部 + 本次可转发文本，掩码后只发送可确定的前缀，
     * 尾部留在窗口里等下个分片判全。
     *
     * <p>必须保留尾部而不能整段直发：敏感词可能正好被分片边界切断，只看到前半截时匹配不出来，
     * 一旦发出去就再也收不回。流结束时由 {@code REPLACE} 用完整文本兜底，故保留段不会丢字。</p>
     *
     * @param emitter           SSE 发射器
     * @param carry             跨分片保留窗口（原地更新为本次掩码后的尾部）
     * @param text              本次可转发文本（已剔除意图标记块）
     * @param carryWindowLength 保留窗口长度（由词库最长词长推导）
     */
    private void forwardMasked(SseEmitter emitter, StringBuilder carry, String text, int carryWindowLength) {
        String combined = carry.toString() + text;
        String masked = agentService.maskForDisplay(combined);
        int hold = Math.min(carryWindowLength, masked.length());
        String sendable = masked.substring(0, masked.length() - hold);
        carry.setLength(0);
        carry.append(masked, masked.length() - hold, masked.length());
        if (!sendable.isEmpty()) {
            safeSend(emitter, AgentEventType.ANSWER, sendable);
        }
    }

    /**
     * 发送 SSE 事件（连接已断开或 emitter 已完成时静默忽略，避免污染日志）。
     *
     * <p>必须捕获 {@link Exception} 而非仅 IOException：客户端断开走 IOException，但 emitter
     * 超时被 {@code onTimeout} 置为完成后，spring-webmvc 的 {@code ResponseBodyEmitter.send()}
     * 抛的是 IllegalStateException（Assert.state 的「已 completed」校验）。漏接会从 doOnNext 逃逸，
     * 触发 Reactor 的 onErrorDropped 噪音日志。</p>
     *
     * @param emitter SSE 发射器
     * @param type    事件类型（同一取值同时用作 SSE 事件名与 JSON 体的 type 字段）
     * @param data    事件数据（序列化为 AgentStreamEvent JSON）
     */
    private void safeSend(SseEmitter emitter, AgentEventType type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type.getValue()).data(toJson(type, data)));
        } catch (Exception e) {
            log.debug("SSE 发送失败（连接可能已断开或 emitter 已结束）: type={}, {}", type, e.toString());
        }
    }

    /**
     * 分块能力探测（C端页面加载时调用一次）：返回单个 SSE 事件，供客户端判断 enableChunked 是否可用。
     *
     * <p>无任何业务副作用（不读会话、不调 LLM、不改状态）。客户端默认按非分块发消息（单次送达，
     * 绝不因降级重试而双发触发重复误判），仅当探测确认分块通道可用后才启用分块流式。</p>
     *
     * @return 单个 SSE start 事件后立即结束
     */
    @GetMapping("/probe")
    public SseEmitter probe() {
        SseEmitter emitter = new SseEmitter(10_000L);
        safeSend(emitter, AgentEventType.START, Map.of("ok", true));
        emitter.complete();
        return emitter;
    }

    /**
     * 历史会话列表（排除软删，分页）。
     *
     * @param page 页码（从 0 开始）
     * @param size 每页条数
     * @param auth 当前认证用户
     * @return 历史会话分页（id/title/messageCount/status/updatedAt）
     */
    @GetMapping("/history")
    public Result<?> history(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        // size 钳制上限，防超大分页拖垮查询
        size = Math.min(size, 50);
        size = Math.max(size, 1);
        Page<Map<String, Object>> result = archiveService.list(userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return Result.ok(Map.of(
                "content", result.getContent(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "currentPage", result.getNumber(),
                "size", result.getSize()));
    }

    /**
     * 恢复归档会话到热会话（回填最近 N 轮，供继续对话）。
     *
     * @param id   归档会话 ID
     * @param auth 当前认证用户
     * @return 恢复结果
     */
    @PostMapping("/history/{id}/resume")
    public Result<?> resume(@PathVariable Long id, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        // 返回回填的最近消息，前端据此渲染切换后的对话内容
        List<AgentSession.AgentMessageItem> messages = archiveService.resume(userId, id);
        // 恢复历史是新会话上下文：重置上一条消息记录，避免继续对话第一条被误判为重复
        preFilter.resetUser(userId);
        return Result.ok(Map.of("conversationId", id, "resumed", true, "messages", messages));
    }

    /**
     * 批量软删历史会话（保留审计）。
     *
     * @param body 请求体：{"ids":[1,2,3]}
     * @param auth 当前认证用户
     * @return 实际删除的会话数
     */
    @DeleteMapping("/history")
    public Result<?> deleteHistory(@RequestBody(required = false) Map<String, Object> body, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        // 安全解析 ids：兼容 Number / 数字字符串，非数组/空 body 不抛异常
        List<Long> ids = new ArrayList<>();
        if (body == null) {
            return Result.error(400, "ids 不能为空");
        }
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                } else if (o instanceof String s) {
                    try {
                        ids.add(Long.valueOf(s));
                    } catch (NumberFormatException ignored) {
                        // 非法 id 跳过
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            return Result.error(400, "ids 不能为空");
        }
        int deleted = archiveService.softDelete(userId, ids);
        return Result.ok(Map.of("deleted", deleted));
    }

    /**
     * 退出会话（前端离开对话页时通知后端）— 会话结束语义：归档剩余全部消息 + 补压 RETRY 压缩段。
     *
     * <p>轻量接口，无业务参数：先 archiveRemaining（纯 DB 搬运，同步），再 compressRetry（补压失败段），
     * 不返回额外数据。</p>
     *
     * @param auth 当前认证用户
     * @return 处理结果
     */
    @PostMapping("/exit")
    public Result<?> exit(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        archiveService.archiveRemaining(userId);
        memoryCompressionService.compressRetry(userId);
        // 退出会话即会话边界：重置上一条消息记录，避免下次新会话第一条被误判为重复
        preFilter.resetUser(userId);
        return Result.ok();
    }

    /**
     * 快捷问题列表（对话页输入框上方 chips）。
     *
     * @return 快捷问题数组
     */
    @GetMapping("/suggestions")
    public Result<?> suggestions() {
        return Result.ok(List.of(
                "物业几点下班？",
                "帮我搜电钻",
                "我借了啥该还了",
                "怎么发布求助",
                "帮我写发布文案"
        ));
    }

    /**
     * 将事件序列化为 SSE data JSON。
     *
     * @param type 事件类型
     * @param data 事件数据
     * @return JSON 字符串
     * @throws JsonProcessingException 序列化失败
     */
    private String toJson(AgentEventType type, Object data) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new AgentStreamEvent(type, data));
    }
}
