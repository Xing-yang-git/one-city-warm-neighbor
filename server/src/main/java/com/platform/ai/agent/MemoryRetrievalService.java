package com.platform.ai.agent;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.AgentMemorySegment;
import com.platform.repository.AgentMemorySegmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆检索服务 — 按次实时检索用户长期记忆（压缩段）摘要，直接注入 {@code {历史记忆}} 变量。
 *
 * <p>链路：向量化 queryText → 按用户过滤的向量检索 top-N（阈值过滤在服务层）→ 无命中返回「无」→
 * 命中摘要按序号格式化后直接注入（记忆摘要本就是压缩时 LLM 产物，无需二次整合）。
 * <b>降级铁律</b>：embedding 失败 / 检索异常 / 统计异常 → 返回 null 不阻塞对话（注入侧将 null 渲染为「无」）。</p>
 */
@Slf4j
@Service
public class MemoryRetrievalService {

    private final OpenAiEmbeddingModel zhipuEmbedding;
    private final AgentMemorySegmentRepository memorySegmentRepository;

    /** 记忆召回条数（检索该用户压缩段 top-N） */
    @Value("${ai.agent.memory-recall-top:3}")
    private int memoryRecallTop;

    /** 记忆匹配距离阈值（余弦距离，≤ 此值的摘要才注入；低于阈值的摘要不注入） */
    @Value("${ai.agent.memory-match-threshold:0.55}")
    private double memoryMatchThreshold;

    /** 无命中时的记忆变量文本 */
    private static final String MEMORY_NONE = "无";

    /**
     * 构造器注入。
     *
     * @param zhipuEmbedding          智谱 embedding 模型（与知识库同款 1024 维，写入 vector(1024) 压缩段）
     * @param memorySegmentRepository 压缩段仓储（按用户检索）
     */
    public MemoryRetrievalService(OpenAiEmbeddingModel zhipuEmbedding,
                                  AgentMemorySegmentRepository memorySegmentRepository) {
        this.zhipuEmbedding = zhipuEmbedding;
        this.memorySegmentRepository = memorySegmentRepository;
    }

    /**
     * 按次实时检索用户长期记忆摘要并返回注入文本（供 System Prompt 的 {@code {历史记忆}} 注入）。
     *
     * <p>每个用户消息都以其自身作 query 实时检索 top-3（阈值过滤在服务层），命中摘要按
     * 「1. 摘要\n2. 摘要」格式直接返回；无 LLM 整合、无窗口缓存。</p>
     *
     * @param userId    住户用户 ID（检索强制按用户过滤，越权防护）
     * @param queryText 当前用户消息（作检索查询文本）
     * @return 命中摘要列表文本；无命中返回「无」；queryText 空/embedding 失败/检索异常降级返回 null（不阻塞）
     */
    public String retrieveMemory(Long userId, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return null;
        }
        // 无段跳过：该用户从未产生压缩段（无长期记忆）→ 直接返回「无」，省去一次 embedding 调用与向量检索；
        //    统计查询异常按检索异常降级铁律处理——记日志后走原链路（由后续 embedQuery/searchWithThreshold 兜底）
        try {
            if (memorySegmentRepository.countByUserId(userId) == 0L) {
                return MEMORY_NONE;
            }
        } catch (Exception e) {
            log.warn("记忆段数量统计失败（降级走原链路）: userId={}, {}", userId, e.getMessage());
        }
        // 向量化查询文本（失败降级返回 null，不阻塞对话）
        String queryVector = embedQuery(queryText);
        if (queryVector == null) {
            return null;
        }
        // 按用户检索 top-N，阈值过滤在服务层（与知识库检索同款做法）
        List<AgentMemorySegment> hits;
        try {
            hits = searchWithThreshold(userId, queryVector);
        } catch (Exception e) {
            // 检索异常（如 pgvector 维度不匹配导致查询失败）→ 降级视作无记忆，绝不抛到 chat 请求
            log.warn("记忆检索异常（降级不注入记忆）: userId={}, {}", userId, e.getMessage());
            return null;
        }
        // 无命中 → 返回「无」
        if (hits.isEmpty()) {
            return MEMORY_NONE;
        }
        // 命中 → 直接格式化命中摘要注入（无 LLM 整合，记忆摘要即压缩时产物）；
        //    摘要全为空（如 RETRY 段摘要缺失）时 buildSegmentsText 返回「无」，与无命中语义一致
        String text = buildSegmentsText(hits);
        log.info("记忆检索注入: userId={}, 命中 {} 段", userId, hits.size());
        return text;
    }

    /**
     * 向量化查询文本（失败返回 null，跳过记忆检索）。
     *
     * <p>与知识库同款：使用 {@code zhipuEmbedding}（智谱 embedding-3，dimensions=1024），
     * 与 agent_memory_segments.embedding 的 vector(1024) 严格一致。</p>
     *
     * @param text 查询文本
     * @return pgvector 字面量，或 null
     */
    private String embedQuery(String text) {
        try {
            float[] vector = zhipuEmbedding.embed(text);
            if (vector.length != 1024) {
                log.warn("记忆查询向量维度异常: {}，跳过记忆检索", vector.length);
                return null;
            }
            return toPgvectorString(vector);
        } catch (Exception e) {
            log.warn("记忆检索查询向量化失败（降级不注入记忆）: {}", e.getMessage());
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
     * 按用户向量检索压缩段并按距离阈值过滤（保持距离升序）。
     *
     * @param userId      住户用户 ID
     * @param queryVector 查询向量字面量
     * @return 距离 ≤ 阈值的压缩段列表（升序）
     */
    private List<AgentMemorySegment> searchWithThreshold(Long userId, String queryVector) {
        List<Object[]> rows = memorySegmentRepository.findIdsBySimilarity(userId, queryVector, memoryRecallTop);
        // 距离 ≤ 阈值的候选 id（距离越小越相似；超过阈值的不注入）
        List<Long> hitIds = new ArrayList<>();
        for (Object[] row : rows) {
            double distance = ((Number) row[1]).doubleValue();
            if (distance <= memoryMatchThreshold) {
                hitIds.add(((Number) row[0]).longValue());
            }
        }
        if (hitIds.isEmpty()) {
            return List.of();
        }
        // 按距离升序回填实体（findAllById 无序，需按 hitIds 顺序还原）
        Map<Long, AgentMemorySegment> byId = new LinkedHashMap<>();
        for (AgentMemorySegment segment : memorySegmentRepository.findAllById(hitIds)) {
            byId.put(segment.getId(), segment);
        }
        List<AgentMemorySegment> ordered = new ArrayList<>();
        for (Long id : hitIds) {
            AgentMemorySegment segment = byId.get(id);
            if (segment != null) {
                ordered.add(segment);
            }
        }
        return ordered;
    }

    /**
     * 构建命中摘要列表文本（带序号，直接注入用）。
     *
     * @param segments 命中的压缩段（按距离升序）
     * @return 形如「1. 摘要1\n2. 摘要2」的文本；摘要全为空（空白）时返回「无」
     */
    private String buildSegmentsText(List<AgentMemorySegment> segments) {
        // 按时间从旧到新排序（created_at 为空的历史数据排最后），格式化带相对时间标签——
        // 帮助模型判断记忆先后与归属（当轮上下文 vs 历史记忆），回答时间性/顺序性问题时能定位正确层级
        List<AgentMemorySegment> ordered = segments.stream()
                .sorted(Comparator.comparing(AgentMemorySegment::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        int seq = 1;
        for (AgentMemorySegment segment : ordered) {
            String summary = segment.getSummary();
            if (summary != null && !summary.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                // 时间 + 会话归属标签：帮助模型判断该记忆的先后与所属会话，
                // 避免把其他会话的记忆误当作当前会话的事实（跨会话记忆污染）
                sb.append(seq).append(". （").append(relativeTime(segment.getCreatedAt()))
                        .append(" · 会话「").append(sessionLabel(segment)).append("」）")
                        .append(summary.trim());
                seq++;
            }
        }
        return sb.length() == 0 ? MEMORY_NONE : sb.toString();
    }

    /**
     * 记忆段所属会话标签（压缩时生成的会话标题，缺失时用「未命名」）。
     *
     * @param segment 记忆段
     * @return 会话标题标签
     */
    private String sessionLabel(AgentMemorySegment segment) {
        String title = segment.getTitle();
        return title == null || title.isBlank() ? "未命名" : title.trim();
    }

    /**
     * 将记忆段创建时间格式化为相对时间标签（今天/昨天/N天前/跨月日期）。
     *
     * @param created 记忆段创建时间
     * @return 相对时间标签；created 为空返回「时间未知」
     */
    private String relativeTime(LocalDateTime created) {
        if (created == null) {
            return "时间未知";
        }
        LocalDate createdDate = created.toLocalDate();
        long days = ChronoUnit.DAYS.between(createdDate, LocalDate.now(AppTimeZone.APP_ZONE));
        if (days <= 0) {
            return "今天";
        }
        if (days == 1) {
            return "昨天";
        }
        if (days < 30) {
            return days + "天前";
        }
        return createdDate.format(DateTimeFormatter.ofPattern("M月d日"));
    }
}
