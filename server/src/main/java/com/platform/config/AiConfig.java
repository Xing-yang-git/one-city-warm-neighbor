package com.platform.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 相关配置 — 提供三家模型的 Bean 与手写客户端。
 *
 * <p>模型分工（Step 0.5 PoC 已验证兼容性）：
 * <ul>
 *   <li><b>deepseek-v4-flash</b>（OpenAI 兼容）— 文本生成/文本审核/Agent 对话，通用 {@link OpenAiChatModel} 对接</li>
 *   <li><b>智谱 GLM-4V-Flash</b>（OpenAI 兼容）— 图片审核多模态，base-url 已含 /api/paas/v4，需自定义 completionsPath</li>
 *   <li><b>智谱 embedding-3</b>（OpenAI 兼容）— 语义向量，RAG 用 dimensions=1024（与 idle_items 的 2048 区分）</li>
 * </ul>
 *
 * <p><b>铁律：禁止引入 spring-ai-starter-model-deepseek 原生适配器</b>（Function Call 会失效），
 * 统一走 spring-ai-starter-model-openai + base-url 区分厂商。</p>
 *
 * <p>注意：spring.ai.openai.* 自动配置未配置 api-key 时不生效，本类手动构建是唯一模型 Bean。</p>
 */
@Configuration
public class AiConfig {

    /** 关闭 keep-alive 的头：复用陈旧连接会触发上游 Connection reset，见 aiRestClientBuilder 注释 */
    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_VALUE_CLOSE = "close";

    // ==================== 智谱 Embedding（语义向量） ====================

    /** 智谱 Embedding API 密钥 */
    @Value("${ai.embedding.api-key}")
    private String apiKey;

    /** 智谱 API 基础地址（已含 /api/paas/v4） */
    @Value("${ai.embedding.base-url}")
    private String baseUrl;

    /** Embedding 模型名称（智谱 embedding-3） */
    @Value("${ai.embedding.model}")
    private String model;

    /** 语义相似度阈值（余弦距离），小于此值才视为匹配 */
    @Value("${ai.embedding.similarity-threshold:0.5}")
    private double similarityThreshold;

    // ==================== 智谱 Chat（图片审核） ====================

    /** 智谱 Chat API 密钥（复用 Embedding 的密钥环境变量） */
    @Value("${ai.zhipu.chat.api-key}")
    private String chatApiKey;

    /** 智谱 Chat API 基础地址 */
    @Value("${ai.zhipu.chat.base-url}")
    private String chatBaseUrl;

    /** 智谱视觉模型名称（图片审核） */
    @Value("${ai.zhipu.chat.model-vision}")
    private String modelVision;

    // ==================== 独立重排服务（bge-reranker-v2-m3） ====================

    /** 重排服务基础地址（rerank-service Docker 容器，本地 8001） */
    @Value("${ai.rerank.base-url:http://localhost:8001}")
    private String rerankBaseUrl;

    // ==================== DeepSeek 文本 ====================

    /** DeepSeek API 密钥 */
    @Value("${ai.deepseek.api-key}")
    private String deepseekApiKey;

    /** DeepSeek API 基础地址 */
    @Value("${ai.deepseek.base-url}")
    private String deepseekBaseUrl;

    /** DeepSeek 文本模型名称（deepseek-v4-flash，带 reasoning 思维链） */
    @Value("${ai.deepseek.model}")
    private String deepseekModel;

    // ==================== 手写 RestClient（手写 ModerationClient 图片审核用） ====================

    /**
     * 创建 RestClient Bean，用于调用智谱 Embedding API（现有手写链路）。
     *
     * @return 配置了基础地址和超时的 RestClient 实例
     */
    @Bean
    public RestClient embeddingRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10000);
        requestFactory.setReadTimeout(10000);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HEADER_CONNECTION, HEADER_VALUE_CLOSE)
                .build();
    }

    /**
     * 创建 Chat RestClient Bean，用于手写 {@code ModerationClient} 图片审核（GLM-4V-Flash）。
     *
     * <p>保留手写而非迁移 Spring AI，避免图片审核回归风险。读取超时 30 秒（视觉审核较慢）。</p>
     *
     * @return 配置了基础地址和超时的 RestClient 实例
     */
    @Bean
    public RestClient chatRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10000);
        requestFactory.setReadTimeout(30000);

        return RestClient.builder()
                .baseUrl(chatBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HEADER_CONNECTION, HEADER_VALUE_CLOSE)
                .build();
    }

    /**
     * 创建 DeepSeek Chat RestClient Bean，用于手写 {@code ModerationClient} 文本审核（deepseek-v4-flash）。
     *
     * <p>文本审核保留手写框架（不迁移 Spring AI），仅模型切 deepseek（minimal regression）。</p>
     *
     * @return 指向 deepseek base-url 的 RestClient 实例
     */
    @Bean
    public RestClient deepseekRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10000);
        requestFactory.setReadTimeout(30000);

        return RestClient.builder()
                .baseUrl(deepseekBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    // ==================== Spring AI 模型 Bean ====================

    /**
     * 构建带显式超时的 RestClient.Builder（Spring AI 模型 Bean 共用）。
     *
     * <p>Spring AI 1.0 的 OpenAiApi 默认用 JDK HttpClient，未显式配置时无读超时，
     * 外部 AI API stall 会无限阻塞调用线程。统一在此注入超时，保证故障有界收敛。</p>
     *
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param readTimeoutMs    读超时（毫秒）
     * @return 配置好超时的 RestClient.Builder
     */
    private RestClient.Builder aiRestClientBuilder(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        // Connection: close 关闭 keep-alive 连接复用：外部 AI API 的 keep-alive 连接空闲后会被服务端关闭，
        // Java HttpURLConnection 复用陈旧连接会间歇性 Connection reset，触发 Spring AI 重试（默认 10 次指数退避）
        // 放大到 8~24s。每次新建连接（~50ms 握手）换取稳定，对 LLM 调用时延可忽略。
        // 注意：此头仅适用于 RestClient（JDK HttpURLConnection）；WebClient（Reactor Netty）的流式
        // 调用禁止设置 Connection 头（restricted header），连接池复用问题另由 AgentService 流式重试兜底。
        return RestClient.builder().requestFactory(requestFactory).defaultHeader(HEADER_CONNECTION, HEADER_VALUE_CLOSE);
    }

    /**
     * DeepSeek 文本模型 — 文本生成/文本审核/Agent 对话。
     *
     * <p>OpenAI 兼容端点，base-url 指向 deepseek，使用默认 /v1 路径。
     * 温度由各调用方在 Prompt options 中按场景覆盖。</p>
     *
     * @return 配置 deepseek-v4-flash 的 {@link OpenAiChatModel}
     */
    @Bean
    public OpenAiChatModel deepseekChatModel() {
        // 显式配置连接/读超时：外部 API 一旦 stall，无超时会无限挂起 agent-sse 线程，
        // 前端 45s 静默兜底后只能报"回复超时"。25s 读超时对正常生成（1~8s）足够宽松，
        // 又能把故障收敛为有界错误，线程随即释放。
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(deepseekApiKey)
                .restClientBuilder(aiRestClientBuilder(10_000, 25_000))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(deepseekModel)
                        .maxTokens(512)
                        .build())
                .build();
    }

    /**
     * 智谱视觉模型 — 图片审核（GLM-4V-Flash）。
     *
     * <p>智谱 base-url 已含 /api/paas/v4，Spring AI 默认拼 /v1 会 404，需自定义 completionsPath。</p>
     *
     * @return 配置 glm-4v-flash 的 {@link OpenAiChatModel}
     */
    @Bean
    public OpenAiChatModel zhipuVisionModel() {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(chatBaseUrl)
                .completionsPath("/chat/completions")
                .apiKey(chatApiKey)
                .restClientBuilder(aiRestClientBuilder(10_000, 30_000))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelVision)
                        .maxTokens(200)
                        .build())
                .build();
    }

    /**
     * 重排服务 RestClient — 独立 FastAPI 服务里的 bge-reranker-v2-m3。
     *
     * <p>专用 cross-encoder 重排器（非 LLM）：毫秒级、确定性、无思维链/空 content 问题。
     * 本地服务，超时给短（连接 2s / 读 5s），服务不可用时由 RerankerService 降级原序。</p>
     *
     * @return 指向 rerank-service 的 RestClient
     */
    @Bean
    public RestClient rerankRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        // 读超时放宽到 20s：候选 documents 为长文本，bge-reranker 逐条推理实测约 6s，
        // 5s 会频繁超时降级（大 body 推理必超 5s）。
        requestFactory.setReadTimeout(20000);
        // 与其他 AI RestClient 一致：Connection: close 禁用 keep-alive 复用，
        // Docker Desktop 端口转发对陈旧 keep-alive 连接会返回异常响应，每次新建连接换取稳定。
        return RestClient.builder()
                .baseUrl(rerankBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HEADER_CONNECTION, HEADER_VALUE_CLOSE)
                .build();
    }

    /**
     * Agent 对话 SSE 异步推送线程池（Spring 托管，有界队列 + CallerRuns 拒绝策略）。
     *
     * <p>相比裸 Executors.newFixedThreadPool：有界队列避免无限排队占内存，
     * CallerRuns 拒绝策略在满时由调用线程兜底执行，线程池随容器生命周期管理。</p>
     *
     * @return Agent SSE 推送线程池
     */
    @Bean
    public ThreadPoolTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 阻塞式 LLM 调用单次可能占线程数秒~数十秒（有超时兜底），核心/上限调到 4/8，
        // 避免 2 个慢请求就排满队列、后续请求被 CallerRuns 卡住 Tomcat 线程
        executor.setCorePoolSize(4);// 核心线程数 4，保证并发 SSE 推送
        executor.setMaxPoolSize(8);// 最大线程数 8，避免大批量请求打满线程池
        executor.setQueueCapacity(50);// 有界队列 50，避免无限排队占内存
        executor.setThreadNamePrefix("agent-sse-");// 线程名前缀，便于排查
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());// CallerRuns 拒绝策略，满时由调用线程兜底执行
        executor.initialize();// 初始化线程池
        return executor;
    }

    /**
     * 文档导入解析异步线程池（B端上传文档 → 解析/切片/嵌入，不阻塞上传响应）。
     *
     * <p>与 agentExecutor 职责分离：文档解析耗时较长（大 PDF / 扫描 OCR），
     * 固定 2 线程 + 有界队列，防止大批量上传打满内存。</p>
     *
     * @return 文档导入专用线程池
     */
    @Bean
    public ThreadPoolTaskExecutor documentImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("doc-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 智谱 Embedding 模型 — RAG 知识库向量（1024 维）。
     *
     * <p>智谱 base-url 已含 /api/paas/v4，需自定义 embeddingsPath。
     * dimensions=1024 与 idle_items 的 2048 维区分（Step 0.5 PoC 已验证）。</p>
     *
     * @return 配置 embedding-3 dimensions=1024 的 {@link OpenAiEmbeddingModel}
     */
    @Bean
    public OpenAiEmbeddingModel zhipuEmbedding() {
        // 与 chat 模型同理：补显式超时（embedding 非关键，故障降级为关键词检索，读超时给短些）
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .embeddingsPath("/embeddings")
                .apiKey(apiKey)
                .restClientBuilder(aiRestClientBuilder(10_000, 10_000))
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .dimensions(1024)
                        .build());
    }

    // ==================== Getter ====================

    /**
     * 获取智谱 Embedding API 密钥。
     *
     * @return 智谱 API 密钥
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 获取智谱 Embedding 模型名称。
     *
     * @return 模型名称，如 embedding-3
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取语义相似度阈值。
     *
     * @return 余弦距离阈值
     */
    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * 获取智谱 Chat API 密钥（图片审核）。
     *
     * @return Chat API 密钥
     */
    public String getChatApiKey() {
        return chatApiKey;
    }

    /**
     * 获取智谱视觉模型名称（图片审核用）。
     *
     * @return 视觉模型名称，如 glm-4v-flash
     */
    public String getModelVision() {
        return modelVision;
    }

    /**
     * 获取 DeepSeek API 密钥。
     *
     * @return DeepSeek 密钥
     */
    public String getDeepseekApiKey() {
        return deepseekApiKey;
    }

    /**
     * 获取 DeepSeek API 基础地址。
     *
     * @return DeepSeek base-url
     */
    public String getDeepseekBaseUrl() {
        return deepseekBaseUrl;
    }

    /**
     * 获取 DeepSeek 文本模型名称。
     *
     * @return 模型名称，如 deepseek-v4-flash
     */
    public String getDeepseekModel() {
        return deepseekModel;
    }
}
