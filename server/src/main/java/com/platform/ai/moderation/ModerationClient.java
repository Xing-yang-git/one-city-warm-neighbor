package com.platform.ai.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.AiGenerationException;
import com.platform.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * AI 内容审核客户端，调用智谱 GLM-4V-Flash（图片审核）和 deepseek-v4-flash（文本审核）。
 *
 * <p>通过 OpenAI 兼容的 /chat/completions 端点发送审核请求，
 * 解析模型返回的 JSON 得到 {@link ModerationResult} 审核等级与原因。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationClient {

    private final RestClient chatRestClient;
    private final RestClient deepseekRestClient;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 图片审核 System Prompt */
    private static final String IMAGE_PROMPT =
            "你是社区闲置物品平台的审核助手。平台是邻里互助闲置物品分享社区，不是电商/二手交易平台。" +
            "请检查图片，按以下标准判定等级：\n\n" +

            "【red — 直接拒绝】\n" +
            "- 纯广告图：大面积促销文字、价格标签、\"扫码领/加群/关注\"、店铺招牌、商品海报\n" +
            "- 二维码推广：以二维码为主体或明显引导扫码\n" +
            "- 违禁品实物：香烟/电子烟/烟弹、酒类、药品/保健品、刀具/仿真枪、活体动物\n" +
            "- 色情/擦边：暴露身体、性暗示姿势、情趣用品\n" +
            "- 与物品无关：自拍/大头照、风景照、网图/表情包、聊天截图、收款码\n\n" +

            "【yellow — 待人工复核】\n" +
            "- 图片中有水印/logo但不是明显广告（如品牌水印、小红书/抖音水印）\n" +
            "- 图片中有微信号/手机号文字但占比不大\n" +
            "- 图片中物品疑似批量/商业库存（如多箱同款商品）\n" +
            "- 保健品/化妆品/护肤品类的实物图（难以判断是否商业行为）\n\n" +

            "【green — 直接通过（宽松）】\n" +
            "- 正常的闲置物品实拍，无论拍照质量好坏\n" +
            "- 图片模糊、抖动、光线暗、背景杂乱、对焦不准 —— 全部算green\n" +
            "- 物品有使用痕迹、包装破损、摆放随意 —— 这正是邻里闲置的特征\n" +
            "- 图片中没有物品只有文字说明（用户可能不会传图）\n\n" +

            "仅返回 JSON：{\"level\":\"green|yellow|red\",\"reason\":\"简短原因，green时为空\"}";

    /** 文本审核 System Prompt 模板 */
    private static final String TEXT_PROMPT_TEMPLATE =
            "你是社区闲置物品平台的审核助手。平台是邻里互助闲置物品分享社区，不是电商平台。" +
            "请检查以下发布内容，按标准判定等级：\n\n" +

            "【red — 直接拒绝】\n" +
            "- 违禁品交易：烟(含电子烟/烟弹/中华/玉溪/利群)、酒(含白酒/红酒/啤酒/洋酒/自酿)、" +
            "药品(含处方药/安眠药/减肥药/壮阳药/保健品夸大宣传)、武器(含刀具/电击器/仿真枪)、活体动物/宠物\n" +
            "- 色情/低俗：约炮/援交/色情服务/性暗示用语\n" +
            "- 欺诈/骗局：扫码领红包/免费送/刷单兼职/打字兼职/\"稳赚不赔\"/高回报投资/传销拉人头\n" +
            "- 违法内容：赌博/赌球/彩票预测/网贷/高利贷/办假证/个人隐私买卖\n" +
            "- 纯广告：非物品的推广链接/课程广告/理财广告/保险推销/房产中介\n\n" +

            "【yellow — 待人工复核】\n" +
            "- 商业导流：加微信详聊/加好友私聊/扫码进群/关注公众号/引导至其他平台交易\n" +
            "- 商业行为：代理/招代理/团购/代购/批发/直销/一件代发/\"长期有效\"/\"大量现货\"\n" +
            "- 疑似经营：装修公司/家政公司/补习班/私房菜接单/烘焙定制/摄影约拍等商业服务\n" +
            "- 价格异常：明显低于市场价的贵重物品（可能假货/赃物）\n" +
            "- 描述模糊但标题敏感：只有几个字但涉及上述关键词\n\n" +

            "【green — 直接通过（宽松）】\n" +
            "- 正常邻里互助：出借/求借闲置物品、求助帮忙(代取快递/搬家/维修/陪伴/遛宠)\n" +
            "- 描述简短/没有描述 —— 全部算green\n" +
            "- 语句不通/错别字/口语化表达 —— 这就是真实的邻里对话，不算违规\n" +
            "- 正常的价格协商语气：\"价格好商量\"/\"可以议价\"不算违规\n\n" +

            "标题：%s  描述：%s\n\n" +
            "仅返回 JSON：{\"level\":\"green|yellow|red\",\"reason\":\"简短原因，green时为空\"}";

    /**
     * 审核图片内容 — 读取本地图片文件并发送至 GLM-4V-Flash 进行视觉审核。
     *
     * @param imageUrl  图片 URL（从中提取文件名）
     * @param uploadDir 文件上传目录（用于构建本地文件路径）
     * @return 审核结果（等级 + 原因）
     */
    public ModerationResult moderateImage(String imageUrl, String uploadDir) {
        try {
            // 从 URL 中提取文件名（形如 /uploads/xxx.jpg）
            String fileName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
            Path filePath = Path.of(uploadDir, fileName);

            if (!Files.exists(filePath)) {
                log.warn("审核图片文件不存在: {}", filePath);
                return new ModerationResult(com.platform.common.ModerationStatus.GREEN, "");
            }

            byte[] imageBytes = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            // 根据文件扩展名推断 MIME 类型
            String mimeType = inferMimeType(fileName);
            String dataUrl = "data:" + mimeType + ";base64," + base64;

            // 构建 OpenAI 兼容的多模态消息
            Map<String, Object> imageContent = Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUrl)
            );
            Map<String, Object> textContent = Map.of(
                    "type", "text",
                    "text", IMAGE_PROMPT
            );

            Map<String, Object> message = Map.of(
                    "role", "user",
                    "content", List.of(textContent, imageContent)
            );

            Map<String, Object> requestBody = Map.of(
                    "model", aiConfig.getModelVision(),
                    "messages", List.of(message),
                    "temperature", 0.1,
                    "max_tokens", 200
            );

            return callApi(requestBody, chatRestClient, aiConfig.getChatApiKey());
        } catch (Exception e) {
            log.error("图片审核失败: imageUrl={}", imageUrl, e);
            throw new AiGenerationException("图片审核失败", e);
        }
    }

    /**
     * 审核文本内容 — 将标题和描述组合后发送至 deepseek-v4-flash 进行文本审核。
     *
     * @param title       内容标题
     * @param description 内容描述
     * @return 审核结果（等级 + 原因）
     */
    public ModerationResult moderateText(String title, String description) {
        try {
            String prompt = String.format(TEXT_PROMPT_TEMPLATE,
                    title != null ? title : "",
                    description != null ? description : "");

            Map<String, Object> message = Map.of(
                    "role", "user",
                    "content", prompt
            );

            Map<String, Object> requestBody = Map.of(
                    "model", aiConfig.getDeepseekModel(),
                    "messages", List.of(message),
                    "temperature", 0.1,
                    "max_tokens", 200
            );

            return callApi(requestBody, deepseekRestClient, aiConfig.getDeepseekApiKey());
        } catch (Exception e) {
            log.error("文本审核失败: title={}", title, e);
            throw new AiGenerationException("文本审核失败", e);
        }
    }

    /**
     * 调用智谱 Chat API 并解析审核结果。
     *
     * @param requestBody 请求体（已包含 model、messages 等字段）
     * @return 解析后的审核结果
     */
    @SuppressWarnings("unchecked")
    private ModerationResult callApi(Map<String, Object> requestBody, RestClient restClient, String apiKey) {
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("choices")) {
            throw new AiGenerationException("Chat API 响应缺少 choices 字段");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new AiGenerationException("Chat API 返回的 choices 数组为空");
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> msg = (Map<String, Object>) choice.get("message");
        if (msg == null) {
            throw new AiGenerationException("Chat API 响应缺少 message 字段");
        }

        String content = (String) msg.get("content");
        if (content == null || content.isBlank()) {
            throw new AiGenerationException("Chat API 响应 content 为空");
        }

        // 解析模型返回的 JSON（可能包含 markdown 代码块包裹）
        String jsonStr = extractJson(content);
        try {
            return objectMapper.readValue(jsonStr, ModerationResult.class);
        } catch (Exception e) {
            log.error("解析审核结果 JSON 失败: content={}", content, e);
            throw new AiGenerationException("审核结果 JSON 解析失败", e);
        }
    }

    /**
     * 从 LLM 响应文本中提取 JSON 部分（兼容 markdown 代码块包裹）。
     *
     * @param content LLM 返回的完整文本
     * @return 提取出的纯 JSON 字符串
     */
    private String extractJson(String content) {
        String trimmed = content.trim();
        // 去除 markdown 代码块 ```json ... ```
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                trimmed = trimmed.substring(start, end).trim();
            }
        }
        // 定位第一个 { 到最后一个 }
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }
        return trimmed;
    }

    /**
     * 根据文件名推断 MIME 类型。
     *
     * @param fileName 文件名（含扩展名）
     * @return MIME 类型字符串
     */
    private String inferMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        // 默认 JPEG（jpg / jpeg / 其他均按 JPEG 处理）
        return "image/jpeg";
    }
}
