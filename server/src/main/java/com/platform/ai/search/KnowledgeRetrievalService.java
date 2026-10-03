package com.platform.ai.search;

import com.platform.common.KnowledgeStatus;
import com.platform.repository.KnowledgeItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库 RAG 检索服务 — AI Agent「小邻」回答前从知识库检索相关资料。
 *
 * <p>检索策略（混合召回 + LLM 重排）：
 * <ol>
 *   <li>查询文本 → 智谱 embedding-3（1024 维）→ pgvector 余弦距离（命中 HNSW 表达式索引），取 recall-top-k 条</li>
 *   <li>关键词 LIKE（标题/正文/标签）同取 recall-top-k 条</li>
 *   <li>向量 ∪ 关键词按 id 去重合并</li>
 *   <li>用 rerank-service 重排取 top-M（失败降级原序），再按知识相关性分数关卡过滤</li>
 * </ol>
 *
 * <p>返回的 {@link KnowledgeHit} 由后端直接生成 sources（非模型输出），防幻觉引用。</p>
 */
@Slf4j
@Service
public class KnowledgeRetrievalService {

    private final EntityManager entityManager;
    private final OpenAiEmbeddingModel zhipuEmbedding;
    private final KnowledgeItemRepository knowledgeItemRepository;
    private final RerankerService rerankerService;

    /** 余弦距离阈值（小于此值才视为语义相关） */
    @Value("${ai.agent.knowledge-threshold:0.45}")
    private double threshold;

    /** 混合召回条数（向量与关键词各自召回上限） */
    @Value("${ai.doc.recall-top-k:8}")
    private int recallTopK;

    /** 重排后注入 prompt 条数 */
    @Value("${ai.doc.rerank-top-m:3}")
    private int rerankTopM;

    /** 重排相关性分数关卡（工具专用检索 searchForAgent 用，低于此分数过滤） */
    @Value("${ai.agent.knowledge-min-score:0.3}")
    private double knowledgeMinScore;

    /**
     * 构造器注入。
     *
     * @param entityManager            JPA EntityManager（原生 SQL 向量检索）
     * @param zhipuEmbedding           智谱 embedding 模型（1024 维）
     * @param knowledgeItemRepository  知识条目仓储
     * @param rerankerService          DeepSeek 重排服务
     */
    public KnowledgeRetrievalService(EntityManager entityManager,
                                     OpenAiEmbeddingModel zhipuEmbedding,
                                     KnowledgeItemRepository knowledgeItemRepository,
                                     RerankerService rerankerService) {
        this.entityManager = entityManager;
        this.zhipuEmbedding = zhipuEmbedding;
        this.knowledgeItemRepository = knowledgeItemRepository;
        this.rerankerService = rerankerService;
    }

    /**
     * 知识检索工具专用检索 — 召回 top-8（向量 ∪ 关键词）→ 必走重排 top-3 → 重排分数关卡过滤。
     *
     * <p>合并结果非空即必须重排——关键词召回的不相关内容若不经过分数把关就会直接注入工具结果，
     * 破坏防幻觉的分数关卡。</p>
     *
     * <p>关卡链路：向量 ∪ 关键词按 id 去重合并 → 合并为空直接返回空（零命中不进重排）；
     * 非空则调 {@code rerankerService.rerank(query, merged, rerankTopM, knowledgeMinScore)}，
     * 内部按相关性分数降序并过滤低于 knowledge-min-score 的条目；重排失败降级原序返回前 topM（接受轻微风险）。</p>
     *
     * @param tenantId  当前用户所在小区 ID（无 tenantId 时调用方已拦截，此处兜底返回空）
     * @param queryText 检索关键词
     * @return 重排+分数过滤后的命中列表（实际条数 ≤ 3），可能为空
     */
    public List<KnowledgeHit> searchForAgent(Long tenantId, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        String emb = embedQuery(queryText);
        List<KnowledgeHit> vectorHits = (emb != null && tenantId != null)
                ? vectorSearch(tenantId, emb, recallTopK)
                : List.of();
        List<KnowledgeHit> keywordHits = (tenantId != null)
                ? keywordSearch(tenantId, queryText, recallTopK)
                : List.of();
        List<KnowledgeHit> merged = unionById(vectorHits, keywordHits);
        if (merged.isEmpty()) {
            // 召回零命中关卡：向量全部被阈值过滤且关键词零命中 → 直接返回空，不进入重排
            return List.of();
        }
        // 合并结果非空 → 必须走重排，用分数关卡过滤不相关内容
        return rerankerService.rerank(queryText, merged, rerankTopM, knowledgeMinScore);
    }

    /**
     * 将查询文本编码为 1024 维向量字符串（失败返回 null，跳过向量召回）。
     *
     * @param text 查询文本
     * @return pgvector 字面量，或 null
     */
    private String embedQuery(String text) {
        try {
            float[] vector = zhipuEmbedding.embed(text);
            if (vector.length != 1024) {
                log.warn("查询向量维度异常: {}，跳过向量召回", vector.length);
                return null;
            }
            return toPgvectorString(vector);
        } catch (Exception e) {
            log.warn("查询向量生成失败，跳过向量召回: {}", e.getMessage());
            return null;
        }
    }

    /**
     * pgvector 余弦距离检索（命中 HNSW 表达式索引 vector(1024)）。
     *
     * @param tenantId 小区 ID
     * @param emb      查询向量字面量
     * @param limit    召回条数
     * @return 语义相关命中列表
     */
    @SuppressWarnings("unchecked")
    private List<KnowledgeHit> vectorSearch(Long tenantId, String emb, int limit) {
        String sql = "SELECT k.id, k.title, k.content, k.category, k.source, " +
                "CAST(k.embedding AS vector(1024)) <=> CAST(:emb AS vector(1024)) AS distance, " +
                "k.section_title, k.page_no " +
                "FROM knowledge_items k " +
                "WHERE k.tenant_id = :tenantId AND k.status = :status AND k.embedding IS NOT NULL " +
                "AND CAST(k.embedding AS vector(1024)) <=> CAST(:emb AS vector(1024)) < :threshold " +
                "ORDER BY distance ASC LIMIT :limit";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("status", KnowledgeStatus.ONLINE);
        query.setParameter("emb", emb);
        query.setParameter("threshold", threshold);
        query.setParameter("limit", limit);
        List<Object[]> rows = query.getResultList();
        List<KnowledgeHit> hits = rows.stream()
                .map(r -> new KnowledgeHit(
                        ((Number) r[0]).longValue(),
                        (String) r[1],
                        (String) r[2],
                        (String) r[3],
                        (String) r[4],
                        ((Number) r[5]).doubleValue(),
                        (String) r[6],
                        r[7] == null ? null : ((Number) r[7]).intValue()))
                .collect(Collectors.toList());
        log.debug("知识库向量检索: query 命中 {} 条", hits.size());
        return hits;
    }

    /**
     * 关键词 LIKE 检索（标题/正文/标签）。
     *
     * @param tenantId 小区 ID
     * @param keyword  关键词
     * @param limit    召回条数
     * @return 关键词命中列表（distance 置为 -1 表示非语义排序）
     */
    private List<KnowledgeHit> keywordSearch(Long tenantId, String keyword, int limit) {
        if (tenantId == null) {
            return List.of();
        }
        return knowledgeItemRepository
                .findWithFilter(tenantId, null, KnowledgeStatus.ONLINE, keyword, PageRequest.of(0, limit))
                .getContent()
                .stream()
                .map(k -> new KnowledgeHit(k.getId(), k.getTitle(), k.getContent(),
                        k.getCategory(), k.getSource(), -1.0, k.getSectionTitle(), k.getPageNo()))
                .collect(Collectors.toList());
    }

    /**
     * 向量 ∪ 关键词按 id 去重合并（向量在前按距离升序，关键词补尾）。
     *
     * @param vectorHits  向量命中
     * @param keywordHits 关键词命中
     * @return 合并去重列表
     */
    private List<KnowledgeHit> unionById(List<KnowledgeHit> vectorHits, List<KnowledgeHit> keywordHits) {
        Map<Long, KnowledgeHit> merged = new LinkedHashMap<>();
        for (KnowledgeHit hit : vectorHits) {
            merged.put(hit.id(), hit);
        }
        for (KnowledgeHit hit : keywordHits) {
            merged.putIfAbsent(hit.id(), hit);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 将 1024 维 float[] 转为 pgvector 字面量 '[0.1,0.2,...]'。
     *
     * @param vector 查询向量
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
}
