package com.platform.service;

import com.platform.common.KnowledgeStatus;
import com.platform.common.KnowledgeCategory;
import com.platform.model.entity.KnowledgeItem;
import com.platform.repository.KnowledgeItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库业务逻辑 — 条目创建 + RAG 向量生成。
 *
 * <p>使用 Spring AI {@link OpenAiEmbeddingModel}（智谱 embedding-3，1024 维）生成向量。
 * embedding 生成失败不阻断保存（检索降级关键词 LIKE）。文档切片由
 * {@link KnowledgeImportService} 直接经仓储入库，不经本类管理。</p>
 */
@Slf4j
@Service
public class KnowledgeService {

    private final KnowledgeItemRepository knowledgeItemRepository;
    private final OpenAiEmbeddingModel zhipuEmbedding;

    public KnowledgeService(KnowledgeItemRepository knowledgeItemRepository, OpenAiEmbeddingModel zhipuEmbedding) {
        this.knowledgeItemRepository = knowledgeItemRepository;
        this.zhipuEmbedding = zhipuEmbedding;
    }

    /**
     * 创建知识条目（生成 1024 维 embedding，失败留空不阻断）。
     *
     * @param item 待创建条目（含 tenantId、category、title、content）
     * @return 已保存条目
     */
    @Transactional
    public KnowledgeItem create(KnowledgeItem item) {
        validateCategoryAndStatus(item);
        generateEmbedding(item);
        return knowledgeItemRepository.save(item);
    }

    /**
     * 生成 1024 维语义向量并写入条目（失败仅告警，留空待重试）。
     *
     * @param item 目标条目
     */
    private void generateEmbedding(KnowledgeItem item) {
        try {
            String text = ((item.getTitle() == null ? "" : item.getTitle()) + " "
                    + (item.getContent() == null ? "" : item.getContent())).trim();
            if (text.isEmpty()) {
                log.warn("知识条目 [id={}] 标题与正文均为空，跳过向量生成", item.getId());
                return;
            }
            float[] vector = zhipuEmbedding.embed(text);
            item.setEmbedding(floatArrayToPgvectorString(vector));
        } catch (Exception e) {
            log.warn("知识条目 [id={}] 向量生成失败，留空待 reindex: {}", item.getId(), e.getMessage());
        }
    }

    /**
     * 校验知识条目分类与状态取值（创建/更新统一入口，防非法值落库）。
     *
     * @param item 待校验条目
     */
    private void validateCategoryAndStatus(KnowledgeItem item) {
        boolean validCategory = KnowledgeCategory.RULES.equals(item.getCategory())
                || KnowledgeCategory.SERVICE.equals(item.getCategory())
                || KnowledgeCategory.HELP.equals(item.getCategory())
                || KnowledgeCategory.GUIDE.equals(item.getCategory());
        if (!validCategory) {
            throw new IllegalArgumentException("未知分类: " + item.getCategory());
        }
        if (item.getStatus() != null
                && !KnowledgeStatus.ONLINE.equals(item.getStatus())
                && !KnowledgeStatus.OFFLINE.equals(item.getStatus())) {
            throw new IllegalArgumentException("未知状态: " + item.getStatus());
        }
    }

    /**
     * 将 float[] 转为 pgvector 字面量格式 '[0.1,0.2,...]'。
     *
     * @param vector 浮点向量（必须 1024 维，与 HNSW 表达式索引 vector(1024) 严格一致）
     * @return pgvector 字面量字符串
     * @throws IllegalArgumentException 向量维度非 1024 时抛出（generateEmbedding 捕获后留空）
     */
    static String floatArrayToPgvectorString(float[] vector) {
        if (vector.length != 1024) {
            throw new IllegalArgumentException("知识条目向量维度必须为 1024，实际 " + vector.length);
        }
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
