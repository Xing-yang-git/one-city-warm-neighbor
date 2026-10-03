package com.platform.ai.search;

import com.platform.ai.embedding.EmbeddingService;
import com.platform.common.PostStatus;
import com.platform.config.AiConfig;
import com.platform.model.entity.IdleItem;
import com.platform.repository.IdleItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 语义搜索服务 — 基于 pgvector 向量相似度对闲置物品执行语义搜索。
 *
 * <p>工作流程：
 * <ol>
 *   <li>将用户查询文本通过 EmbeddingService 转换为 768 维向量</li>
 *   <li>使用 pgvector {@code <=>} 运算符计算余弦距离</li>
 *   <li>按距离升序排列，距离越小表示语义越相似</li>
 * </ol>
 */
@Slf4j
@Service
public class SemanticSearchService {

    private final EmbeddingService embeddingService;
    private final IdleItemRepository idleItemRepository;
    private final EntityManager entityManager;
    private final AiConfig aiConfig;

    public SemanticSearchService(EmbeddingService embeddingService,
                                 IdleItemRepository idleItemRepository,
                                 EntityManager entityManager,
                                 AiConfig aiConfig) {
        this.embeddingService = embeddingService;
        this.idleItemRepository = idleItemRepository;
        this.entityManager = entityManager;
        this.aiConfig = aiConfig;
    }

    /**
     * 语义搜索闲置物品 — 通过 pgvector 余弦距离排序。
     */
    public List<IdleItem> semanticSearchIdle(String queryText, Long tenantId, String postType, int limit) {
        String embedding = embeddingService.generateEmbedding(queryText, "");
        if (embedding == null) return Collections.emptyList();

        String sql = "SELECT i.*, CAST(i.embedding AS vector) <=> CAST(:embedding AS vector) AS distance " +
                     "FROM idle_items i " +
                     "WHERE i.tenant_id = :tenantId " +
                     "  AND i.post_type = :postType " +
                     "  AND i.status = :status " +
                     "  AND i.embedding IS NOT NULL " +
                     "  AND CAST(i.embedding AS vector) <=> CAST(:embedding AS vector) < :threshold " +
                     "ORDER BY distance ASC " +
                     "LIMIT :limit";

        Query query = entityManager.createNativeQuery(sql, IdleItem.class);
        query.setParameter("embedding", embedding);
        query.setParameter("tenantId", tenantId);
        query.setParameter("postType", postType);
        query.setParameter("status", PostStatus.ONLINE);
        query.setParameter("threshold", aiConfig.getSimilarityThreshold());
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<IdleItem> results = query.getResultList();
        log.debug("语义搜索返回 {} 条结果", results.size());
        return results;
    }

    /**
     * 混合搜索 — 语义结果 + 关键词结果合并去重。
     */
    public Page<IdleItem> hybridSearch(String queryText, Long tenantId, String postType, Pageable pageable) {
        int fetchLimit = Math.max(pageable.getPageSize() * 3, 30);

        List<IdleItem> semanticResults = semanticSearchIdle(queryText, tenantId, postType, fetchLimit);

        Set<Long> seenIds = new HashSet<>();
        List<IdleItem> merged = new ArrayList<>();

        for (IdleItem item : semanticResults) {
            if (seenIds.add(item.getId())) merged.add(item);
        }

        Page<IdleItem> keywordPage = idleItemRepository.searchByTenant(
                PostStatus.ONLINE, postType, tenantId, queryText, queryText,
                PageRequest.of(0, fetchLimit));

        for (IdleItem item : keywordPage.getContent()) {
            if (seenIds.add(item.getId())) merged.add(item);
        }

        log.debug("混合搜索合并: 语义{}条 + 关键词{}条 → 去重{}条",
                semanticResults.size(), keywordPage.getContent().size(), merged.size());

        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), merged.size());
        List<IdleItem> pageContent = start < merged.size()
                ? merged.subList(start, end) : Collections.emptyList();

        return new PageImpl<>(pageContent, pageable, merged.size());
    }

}
