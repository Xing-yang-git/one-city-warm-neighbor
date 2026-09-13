package com.platform.ai.document.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ai.common.AiApiInvoker;
import com.platform.common.AiGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 图片文字抽取客户端（OCR）— 调用智谱 GLM-4V-Flash 视觉模型逐图识别文字。
 *
 * <p>仿 {@code ModerationClient} 的手写 OpenAI 兼容调用（base-url 已含 /api/paas/v4），
 * max_tokens 放宽到 1024 以容纳长文输出。经 {@link AiApiInvoker} 统一重试/熔断/缓存：
 * 相同页图片（MD5 相同）复用结果，防止重复扣费。</p>
 */
@Slf4j
@Component
public class VisionOcrClient {

    private final RestClient chatRestClient;
    private final String apiKey;
    private final String model;
    private final AiApiInvoker aiApiInvoker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** OCR 抽取指令：逐字提取、保留段落、仅输出文字 */
    private static final String OCR_PROMPT =
            "请将图片中的文字完整逐字提取，保留段落顺序和换行，仅输出文字内容，不要任何解释、不要输出 JSON。";

    /**
     * 构造器注入。
     *
     * @param chatRestClient 智谱 Chat RestClient（AiConfig 已建）
     * @param apiKey         智谱 API 密钥
     * @param model          视觉模型名（glm-4v-flash）
     * @param aiApiInvoker   外部 API 统一调用封装
     */
    public VisionOcrClient(@Qualifier("chatRestClient") RestClient chatRestClient,
                           @Value("${ai.zhipu.chat.api-key}") String apiKey,
                           @Value("${ai.zhipu.chat.model-vision}") String model,
                           AiApiInvoker aiApiInvoker) {
        this.chatRestClient = chatRestClient;
        this.apiKey = apiKey;
        this.model = model;
        this.aiApiInvoker = aiApiInvoker;
    }

    /**
     * 识别图片中的文字（带缓存 + 重试 + 熔断）。
     *
     * @param imageBytes 图片字节（JPEG/PNG）
     * @param mimeType   MIME 类型（image/jpeg / image/png）
     * @return 识别出的文字；失败抛 RuntimeException（由调用方降级留空，不阻断整文档）
     */
    public String ocrImage(byte[] imageBytes, String mimeType) {
        String md5 = DigestUtils.md5DigestAsHex(imageBytes);
        return aiApiInvoker.cached("ocr:" + md5, () -> aiApiInvoker.invoke("ocr", () -> doOcr(imageBytes, mimeType)), 0);
    }

    /** 实际调用 GLM-4V 并解析文字（不做重试/缓存，由上层 invoker 统一处理） */
    private String doOcr(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;
        Map<String, Object> imageContent = Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUrl)
        );
        Map<String, Object> textContent = Map.of("type", "text", "text", OCR_PROMPT);
        Map<String, Object> message = Map.of("role", "user", "content", List.of(textContent, imageContent));
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(message),
                "temperature", 0.1,
                "max_tokens", 1024
        );
        String response = chatRestClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            throw new AiGenerationException("OCR 响应解析失败", e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String text = extractContentText(content);
        if (text.isEmpty()) {
            throw new AiGenerationException("OCR 响应未包含文字内容");
        }
        return text;
    }

    /** GLM-4V content 可能是字符串或 [{type,text}] 数组，统一提取文字 */
    private String extractContentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText().trim();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    sb.append(item.path("text").asText());
                }
            }
            return sb.toString().trim();
        }
        return "";
    }
}
