package com.platform.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ai.common.PromptRepository;
import com.platform.common.AiGenerationException;
import com.platform.common.AgentMessageRole;
import com.platform.common.MemorySegmentStatus;
import com.platform.model.entity.AgentMessage;
import com.platform.model.entity.AgentMemorySegment;
import com.platform.repository.AgentConversationRepository;
import com.platform.repository.AgentMessageRepository;
import com.platform.repository.AgentMemorySegmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 记忆压缩服务 — 把归档窗口消息压缩为长期记忆段（标题+摘要+向量），异步执行不阻塞对话主链路。
 *
 * <p>压缩流程：读 {@code memory.compress} 提示词渲染 {@code {messages}} → LLM 输出 JSON
 * {@code {"title","summary"}} → zhipuEmbedding 向量化摘要（1024 维）→ 插入压缩段（status=SUCCESS）→ 回填归档行标题。
 * 失败降级：LLM 失败/解析失败 → 插入段（标题用首条用户消息兜底、摘要为空、status=RETRY），
 * <b>不抛异常、不阻塞对话、不循环重试</b>；会话结束时由 {@link #compressRetry} 补压。</p>
 *
 * <p>压缩在 {@code documentImportExecutor} 专用线程池异步执行（复用 AiConfig 现有 executor，
 * 与 SSE 交互线程池职责分离），任何异常都被捕获降级，绝不抛到归档同步路径。</p>
 */
@Slf4j
@Service
public class MemoryCompressionService {

    private final PromptRepository promptRepository;
    private final OpenAiChatModel deepseekChatModel;
    private final OpenAiEmbeddingModel zhipuEmbedding;
    private final AgentMemorySegmentRepository memorySegmentRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentConversationRepository conversationRepository;
    private final ThreadPoolTaskExecutor documentImportExecutor;
    private final ObjectMapper objectMapper;

    /** 压缩 LLM 温度（与对话一致的精准档） */
    private static final double COMPRESS_TEMPERATURE = 0.2;

    /** 压缩 LLM 最大输出 token（标题+摘要 JSON） */
    private static final int COMPRESS_MAX_TOKENS = 1024;

    /** 标题最大长度（≤20 字，LLM 超出也强制截断） */
    private static final int TITLE_MAX_LENGTH = 20;

    /** 标题兜底文案 */
    private static final String FALLBACK_TITLE = "小邻对话";

    /** LLM 输出 JSON 的 title 字段名 */
    private static final String JSON_FIELD_TITLE = "title";

    /** LLM 输出 JSON 的 summary 字段名 */
    private static final String JSON_FIELD_SUMMARY = "summary";

    /** transcript 中用户行前缀（用于标题兜底截取首条用户消息） */
    private static final String TRANSCRIPT_USER_PREFIX = "用户：";

    /** transcript 中助手行前缀 */
    private static final String TRANSCRIPT_ASSISTANT_PREFIX = "小邻：";

    /**
     * 构造器注入。
     *
     * @param promptRepository          提示词仓库（key {@code memory.compress}）
     * @param deepseekChatModel         DeepSeek 对话模型（与 AgentService 同一 Bean）
     * @param zhipuEmbedding            智谱 embedding 模型（与知识库同款 1024 维，写入 vector(1024) 压缩段）
     * @param memorySegmentRepository   压缩段仓储
     * @param messageRepository         归档消息仓储（补压回溯原始消息）
     * @param conversationRepository    归档会话仓储（回填归档行标题）
     * @param documentImportExecutor    后台异步执行线程池（压缩与文档导入同性质，不与 SSE 交互抢线程）
     * @param objectMapper              JSON 解析（LLM 输出解析）
     */
    public MemoryCompressionService(PromptRepository promptRepository,
                                    OpenAiChatModel deepseekChatModel,
                                    OpenAiEmbeddingModel zhipuEmbedding,
                                    AgentMemorySegmentRepository memorySegmentRepository,
                                    AgentMessageRepository messageRepository,
                                    AgentConversationRepository conversationRepository,
                                    @Qualifier("documentImportExecutor") ThreadPoolTaskExecutor documentImportExecutor,
                                    ObjectMapper objectMapper) {
        this.promptRepository = promptRepository;
        this.deepseekChatModel = deepseekChatModel;
        this.zhipuEmbedding = zhipuEmbedding;
        this.memorySegmentRepository = memorySegmentRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.documentImportExecutor = documentImportExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步压缩一个归档窗口（归档同步路径只触发、不等待）。
     *
     * <p>立即提交到专用线程池返回；实际压缩在后台执行，任何异常都在线程内捕获降级 RETRY，
     * 绝不抛到归档主链路。segmentNo 由调用方按 {@link #segmentNoOf} 计算（新段 = 已用序号 + 1）。</p>
     *
     * @param userId         住户用户 ID
     * @param tenantId       所属小区 ID
     * @param conversationId 会话级 id（多段共享）
     * @param archiveRowId   归档行 id（消息回溯与标题回填用）
     * @param transcript     该窗口消息按 user/assistant 角色拼接的文本
     * @param segmentNo      新压缩段序号（会话内从 1 递增）
     */
    public void compressWindow(Long userId, Long tenantId, Long conversationId, Long archiveRowId,
                               String transcript, int segmentNo) {
        documentImportExecutor.execute(() -> {
            try {
                compressAndSave(userId, tenantId, conversationId, archiveRowId, transcript, segmentNo);
            } catch (Exception e) {
                // 兜底：任何未预期异常降级 RETRY，绝不阻塞对话主链路
                log.error("记忆压缩异步任务异常，降级 RETRY: userId={}, conversationId={}, archiveRowId={}",
                        userId, conversationId, archiveRowId, e);
                saveSegment(userId, tenantId, conversationId, archiveRowId, segmentNo,
                        fallbackTitle(transcript), null, null, MemorySegmentStatus.RETRY);
            }
        });
    }

    /**
     * 执行一次完整压缩并落库（LLM 失败降级 RETRY，不抛异常）。
     *
     * @param userId         住户用户 ID
     * @param tenantId       所属小区 ID
     * @param conversationId 会话级 id
     * @param archiveRowId   归档行 id
     * @param transcript     拼接后的窗口消息文本
     * @param segmentNo      压缩段序号
     */
    private void compressAndSave(Long userId, Long tenantId, Long conversationId, Long archiveRowId,
                                 String transcript, int segmentNo) {
        TitleSummary ts;
        try {
            String prompt = promptRepository.get(PromptRepository.KEY_MEMORY_COMPRESS)
                    .replace("{messages}", transcript);
            String json = callCompressLlm(prompt);
            ts = parseTitleSummary(json);
        } catch (Exception e) {
            log.warn("记忆压缩 LLM 失败，降级 RETRY: userId={}, conversationId={}, archiveRowId={}, {}",
                    userId, conversationId, archiveRowId, e.getMessage());
            saveSegment(userId, tenantId, conversationId, archiveRowId, segmentNo,
                    fallbackTitle(transcript), null, null, MemorySegmentStatus.RETRY);
            return;
        }
        // 向量化摘要（失败不阻断插入，段向量留空可补压）
        String embedding = embedOrNull(ts.summary());
        saveSegment(userId, tenantId, conversationId, archiveRowId, segmentNo,
                ts.title(), ts.summary(), embedding, MemorySegmentStatus.SUCCESS);
        // 回填归档行标题（异步路径，不影响对话）
        backfillConversationTitle(archiveRowId, ts.title());
        log.info("记忆压缩完成: userId={}, conversationId={}, archiveRowId={}, segmentNo={}",
                userId, conversationId, archiveRowId, segmentNo);
    }

    /**
     * 补压：查该用户所有 RETRY 压缩段，按 archiveRowId 回溯原始消息重新压缩，成功后更新标题/摘要/向量。
     *
     * <p>失败保持 RETRY 不循环；在会话结束归档流程（archiveRemaining 后）调用一次。</p>
     *
     * @param userId 住户用户 ID
     * @return 补压成功条数
     */
    @Transactional
    public int compressRetry(Long userId) {
        List<AgentMemorySegment> retrySegments = memorySegmentRepository
                .findByUserIdAndStatus(userId, MemorySegmentStatus.RETRY);
        int count = 0;
        for (AgentMemorySegment segment : retrySegments) {
            try {
                List<AgentMessage> messages = messageRepository.findByConversationIdOrderByIdAsc(segment.getArchiveRowId());
                String transcript = buildTranscript(messages);
                String prompt = promptRepository.get(PromptRepository.KEY_MEMORY_COMPRESS)
                        .replace("{messages}", transcript);
                String json = callCompressLlm(prompt);
                TitleSummary ts = parseTitleSummary(json);
                segment.setTitle(ts.title());
                segment.setSummary(ts.summary());
                segment.setEmbedding(embedOrNull(ts.summary()));
                segment.setStatus(MemorySegmentStatus.SUCCESS);
                memorySegmentRepository.save(segment);
                backfillConversationTitle(segment.getArchiveRowId(), ts.title());
                count++;
            } catch (Exception e) {
                // 失败保持 RETRY（不循环），留待下次会话结束再补压
                log.warn("记忆补压失败，保持 RETRY: userId={}, segmentId={}, {}", userId, segment.getId(), e.getMessage());
            }
        }
        log.info("记忆补压完成: userId={}, 成功 {} 条", userId, count);
        return count;
    }

    /**
     * 查询某会话已使用的最大压缩段序号（0 表示尚未压缩；新段 = 返回值 + 1）。
     *
     * @param userId         住户用户 ID
     * @param conversationId 会话级 id
     * @return 已用最大序号，无则 0
     */
    public int segmentNoOf(Long userId, Long conversationId) {
        List<AgentMemorySegment> segments = memorySegmentRepository
                .findByUserIdAndConversationId(userId, conversationId);
        return segments.stream()
                .mapToInt(s -> s.getSegmentNo() == null ? 0 : s.getSegmentNo())
                .max()
                .orElse(0);
    }

    /**
     * 调用 DeepSeek 压缩 LLM（temperature 0.2 / maxTokens 1024，与对话同款调用方式）。
     *
     * @param prompt 渲染后的压缩提示词
     * @return LLM 输出文本（应为 JSON）
     */
    private String callCompressLlm(String prompt) {
        Message system = new SystemMessage(prompt);
        Prompt chatPrompt = new Prompt(List.of(system),
                OpenAiChatOptions.builder()
                        .temperature(COMPRESS_TEMPERATURE)
                        .maxTokens(COMPRESS_MAX_TOKENS)
                        .build());
        ChatResponse response = deepseekChatModel.call(chatPrompt);
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new AiGenerationException("记忆压缩 LLM 回复为空");
        }
        return text.trim();
    }

    /**
     * 解析 LLM 输出的 {@code {"title": ..., "summary": ...}} JSON。
     *
     * <p>deepseek 带思维链可能混入额外文本，取首个 {@code {} 与末个 {@code }} 之间的子串解析。</p>
     *
     * @param json LLM 原始输出
     * @return 解析出的标题与摘要（标题截断到 20 字）
     * @throws AiGenerationException 非 JSON / 缺字段 / 字段为空
     */
    private TitleSummary parseTitleSummary(String json) {
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiGenerationException("记忆压缩输出非 JSON 对象");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json.substring(start, end + 1));
        } catch (Exception e) {
            throw new AiGenerationException("记忆压缩输出 JSON 解析失败");
        }
        JsonNode titleNode = node.get(JSON_FIELD_TITLE);
        JsonNode summaryNode = node.get(JSON_FIELD_SUMMARY);
        if (titleNode == null || summaryNode == null) {
            throw new AiGenerationException("记忆压缩 JSON 缺少 title/summary 字段");
        }
        String title = titleNode.asText().trim();
        String summary = summaryNode.asText().trim();
        if (title.isEmpty() || summary.isEmpty()) {
            throw new AiGenerationException("记忆压缩 JSON 字段为空");
        }
        return new TitleSummary(truncate(title, TITLE_MAX_LENGTH), summary);
    }

    /**
     * 向量化摘要（失败返回 null，段向量留空，不阻断插入）。
     *
     * <p>与知识库同款：使用 {@code zhipuEmbedding}（智谱 embedding-3，dimensions=1024），
     * 与 agent_memory_segments.embedding 的 vector(1024) 严格一致。</p>
     *
     * @param summary 摘要文本
     * @return pgvector 字面量，或 null
     */
    private String embedOrNull(String summary) {
        try {
            float[] vector = zhipuEmbedding.embed(summary);
            if (vector.length != 1024) {
                log.warn("记忆摘要向量维度异常: {}，段向量留空", vector.length);
                return null;
            }
            return toPgvectorString(vector);
        } catch (Exception e) {
            log.warn("记忆摘要向量化失败（段向量留空）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 float[] 转为 pgvector 字面量格式 '[0.1,0.2,...]'。
     *
     * @param vector 浮点向量（1024 维，与 agent_memory_segments.embedding 的 vector(1024) 严格一致）
     * @return pgvector 字面量字符串
     */
    private String toPgvectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 回填归档行标题（压缩成功异步回填，失败仅记日志不影响对话）。
     *
     * @param archiveRowId 归档行 id
     * @param title        压缩产出的标题
     */
    private void backfillConversationTitle(Long archiveRowId, String title) {
        try {
            conversationRepository.findById(archiveRowId).ifPresent(c -> {
                c.setTitle(title);
                conversationRepository.save(c);
            });
        } catch (Exception e) {
            log.warn("归档行标题回填失败: archiveRowId={}, {}", archiveRowId, e.getMessage());
        }
    }

    /**
     * 插入压缩段（title/summary/embedding/status）。
     *
     * <p>防御降级：携带向量写入失败（如 pgvector 类型不匹配）时降级为无向量重试插入，
     * 保留标题/摘要与状态，避免压缩段卡在 RETRY 死循环；无向量仍失败则视为 DB 级问题抛出给上层兜底。</p>
     *
     * @param userId         住户用户 ID
     * @param tenantId       所属小区 ID
     * @param conversationId 会话级 id
     * @param archiveRowId   归档行 id
     * @param segmentNo      压缩段序号
     * @param title          标题（可为空）
     * @param summary        摘要（可为空）
     * @param embedding      摘要向量字面量（可为空，向量化失败留空可补压）
     * @param status         状态（SUCCESS/RETRY）
     */
    private void saveSegment(Long userId, Long tenantId, Long conversationId, Long archiveRowId,
                             int segmentNo, String title, String summary, String embedding, String status) {
        try {
            memorySegmentRepository.save(buildSegment(userId, tenantId, conversationId, archiveRowId,
                    segmentNo, title, summary, embedding, status));
        } catch (Exception e) {
            if (embedding != null) {
                log.warn("压缩段向量写入失败，降级为无向量插入: userId={}, conversationId={}, archiveRowId={}, {}",
                        userId, conversationId, archiveRowId, e.getMessage());
                memorySegmentRepository.save(buildSegment(userId, tenantId, conversationId, archiveRowId,
                        segmentNo, title, summary, null, status));
            } else {
                throw e;
            }
        }
    }

    /**
     * 构建压缩段实体。
     *
     * @param userId         住户用户 ID
     * @param tenantId       所属小区 ID
     * @param conversationId 会话级 id
     * @param archiveRowId   归档行 id
     * @param segmentNo      压缩段序号
     * @param title          标题
     * @param summary        摘要
     * @param embedding      向量字面量
     * @param status         状态
     * @return 压缩段实体
     */
    private AgentMemorySegment buildSegment(Long userId, Long tenantId, Long conversationId, Long archiveRowId,
                                            int segmentNo, String title, String summary, String embedding, String status) {
        return AgentMemorySegment.builder()
                .userId(userId)
                .tenantId(tenantId)
                .conversationId(conversationId)
                .archiveRowId(archiveRowId)
                .segmentNo(segmentNo)
                .title(title)
                .summary(summary)
                .embedding(embedding)
                .status(status)
                .build();
    }

    /**
     * 由归档消息列表拼接 transcript（用户/助手角色分行）。
     *
     * @param messages 归档消息（按 id 升序）
     * @return 拼接文本（仅 user/assistant 角色）
     */
    private String buildTranscript(List<AgentMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (AgentMessage message : messages) {
            if (AgentMessageRole.USER.equals(message.getRole())) {
                sb.append(TRANSCRIPT_USER_PREFIX).append(message.getContent()).append('\n');
            } else if (AgentMessageRole.ASSISTANT.equals(message.getRole())
                    && message.getContent() != null && !message.getContent().isBlank()) {
                sb.append(TRANSCRIPT_ASSISTANT_PREFIX).append(message.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 压缩标题兜底：取 transcript 首条「用户：」行截断到 20 字，无则用默认文案。
     *
     * @param transcript 拼接后的窗口消息文本
     * @return 兜底标题
     */
    private String fallbackTitle(String transcript) {
        if (transcript == null) {
            return FALLBACK_TITLE;
        }
        for (String line : transcript.split("\n")) {
            if (line.startsWith(TRANSCRIPT_USER_PREFIX)) {
                String firstUser = line.substring(TRANSCRIPT_USER_PREFIX.length()).trim();
                if (!firstUser.isEmpty()) {
                    return truncate(firstUser, TITLE_MAX_LENGTH);
                }
            }
        }
        return FALLBACK_TITLE;
    }

    /**
     * 截断文本到指定长度。
     *
     * @param text 文本
     * @param max  最大长度
     * @return 截断后文本
     */
    private String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * 压缩结果载体（标题 + 摘要）。
     *
     * @param title   标题（≤20 字）
     * @param summary 摘要
     */
    private record TitleSummary(String title, String summary) {
    }
}
