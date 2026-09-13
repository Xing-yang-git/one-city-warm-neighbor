package com.platform.ai.embedding;

import com.platform.common.AiGenerationException;
import com.platform.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 智谱 Embedding API 客户端，负责将文本转换为语义向量。
 *
 * <p>调用智谱的 /embeddings 端点，将输入文本映射为浮点向量，
 * 用于后续的语义搜索和供需匹配。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final RestClient embeddingRestClient;
    private final AiConfig aiConfig;

    /**
     * 将文本转换为语义向量。
     *
     * @param text 待编码的文本
     * @return 768 维浮点向量
     * @throws RuntimeException 当 API 调用失败或响应格式异常时抛出
     */
    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        log.debug("调用 Embedding API，文本长度: {}", text != null ? text.length() : 0);

        try {
            Map<String, Object> requestBody = Map.of(
                    "input", text != null ? text : "",
                    "model", aiConfig.getModel()
            );

            Map<String, Object> response = embeddingRestClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + aiConfig.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !response.containsKey("data")) {
                throw new AiGenerationException("Embedding API 响应缺少 data 字段");
            }

            List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.get("data");
            if (dataList == null || dataList.isEmpty()) {
                throw new AiGenerationException("Embedding API 返回的 data 数组为空");
            }

            List<Double> embeddingList = (List<Double>) dataList.get(0).get("embedding");
            if (embeddingList == null || embeddingList.isEmpty()) {
                throw new AiGenerationException("Embedding API 返回的 embedding 向量为空");
            }

            // 将 List<Double> 转为 float[]
            float[] result = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                result[i] = embeddingList.get(i).floatValue();
            }

            log.debug("Embedding API 调用成功，向量维度: {}", result.length);
            return result;

        } catch (RuntimeException e) {
            log.error("Embedding API 调用失败: {}", e.getMessage(), e);
            throw e;
        }
    }
}
