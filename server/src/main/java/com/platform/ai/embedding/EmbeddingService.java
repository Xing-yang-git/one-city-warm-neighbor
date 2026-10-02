package com.platform.ai.embedding;

import com.platform.model.entity.IdleItem;
import com.platform.repository.IdleItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Embedding 服务，封装向量生成与物品/求助的向量更新逻辑。
 *
 * <p>将标题和描述拼接后调用 Embedding API 生成语义向量，
 * 以 pgvector 的 {@link PGvector} 类型存储到实体中。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingClient embeddingClient;
    private final IdleItemRepository idleItemRepository;

    /**
     * 根据标题和描述生成语义向量。
     *
     * @return PGvector 实例，文本为空或 API 失败时返回 null
     */
    public String generateEmbedding(String title, String description) {
        String text = (title != null ? title : "") + " " + (description != null ? description : "");
        text = text.trim();

        if (text.isEmpty()) {
            log.warn("标题和描述均为空，无法生成向量");
            return null;
        }

        float[] vector = embeddingClient.embed(text);
        log.debug("向量生成成功，维度: {}", vector.length);
        return floatArrayToPgvectorString(vector);
    }

    /** 将 float[] 转为 pgvector 字面量格式 '[0.1, 0.2, ...]' */
    static String floatArrayToPgvectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 为闲置物品生成并更新语义向量。
     *
     * <p>只写入 embedding 一列，不整行回写实体：向量在后台异步生成，调用方传入的实体是
     * 生成开始时的旧快照，若用它整行覆盖，会把期间用户做的下架/修改等状态变更一并抹掉
     * （例如已下架的帖子被写回 pending_review 后「复活」）。</p>
     *
     * @param item 闲置物品（仅取 ID 与文本内容用于生成，不写回实体状态）
     */
    @Transactional
    public void updateItemEmbedding(IdleItem item) {
        refreshEmbedding(item);
    }

    /**
     * 批量生成所有缺失 embedding 的语义向量。
     *
     * @return 补充生成向量的物品数量
     */
    @Transactional
    public int generateAllMissingEmbeddings() {
        int count = 0;

        List<IdleItem> idleItems = idleItemRepository.findAll();
        for (IdleItem item : idleItems) {
            if (item.getEmbedding() == null || item.getEmbedding().isEmpty()) {
                refreshEmbedding(item);
                count++;
            }
        }

        log.info("批量 Embedding 生成完成: {} 条", count);
        return count;
    }

    /**
     * 生成单个物品的向量并只写回 embedding 列。
     *
     * <p>单独抽出不带事务的私有方法，供两个事务方法共用，避免类内自调用绕过事务代理。</p>
     *
     * @param item 闲置物品
     */
    private void refreshEmbedding(IdleItem item) {
        try {
            String embedding = generateEmbedding(item.getTitle(), item.getDescription());
            if (embedding != null) {
                idleItemRepository.updateEmbeddingById(item.getId(), embedding);
            }
        } catch (Exception e) {
            log.error("为闲置物品 [id={}] 生成向量失败: {}", item.getId(), e.getMessage(), e);
        }
    }
}
