package com.platform.ai.common;

import com.platform.common.AiGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 外部 AI API 调用统一封装 — embedding / OCR / 重排共用。
 *
 * <p>提供三类能力：
 * <ul>
 *   <li><b>重试</b>：默认 3 次 + 指数退避（500ms/1s/2s）</li>
 *   <li><b>熔断</b>：同一维度连续失败 5 次后熔断 5 分钟（快速失败，不再消耗 API 额度）</li>
 *   <li><b>本地缓存</b>：内存 TTL 去重（相同文本 embedding / 相同页 OCR），默认 24h</li>
 * </ul>
 *
 * <p>单实例部署的进程内实现；多实例时熔断/缓存需改造为 Redis 共享（v2）。</p>
 */
@Component
public class AiApiInvoker {

    private static final Logger log = LoggerFactory.getLogger(AiApiInvoker.class);

    /** 熔断阈值：连续失败次数 */
    private static final int FAILURE_THRESHOLD = 5;
    /** 熔断时长（毫秒） */
    private static final long OPEN_DURATION_MS = 5 * 60 * 1000L;
    /** 默认最大重试次数 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 重试基础退避（毫秒），指数增长 */
    private static final long BASE_BACKOFF_MS = 500L;
    /** 缓存默认有效期（毫秒，24h） */
    private static final long DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L;
    /** 缓存条数上限，超限清空防内存膨胀 */
    private static final int CACHE_CAPACITY = 5000;

    /** 熔断状态：连续失败计数 + 熔断截止时间 */
    private static final class CircuitState {
        int consecutiveFailures;
        long openUntilEpochMs;
    }

    /** 缓存条目 */
    private static final class CacheEntry {
        final Object value;
        final long expiresAtEpochMs;

        CacheEntry(Object value, long expiresAtEpochMs) {
            this.value = value;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    /** 各调用维度的熔断状态 */
    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();
    /** 本地内存缓存 */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 带重试 + 熔断的外部调用（默认最大重试次数）。
     *
     * @param circuitKey 熔断维度（如 "embedding" / "ocr" / "rerank"）
     * @param action     实际调用（抛出异常表示失败）
     * @param <T>        返回类型
     * @return 调用结果
     * @throws AiGenerationException 熔断打开或全部重试失败时抛出，由调用方决定降级策略
     */
    public <T> T invoke(String circuitKey, Supplier<T> action) {
        return invoke(circuitKey, DEFAULT_MAX_ATTEMPTS, action);
    }

    /**
     * 带重试 + 熔断的外部调用（自定义最大重试次数）。
     *
     * <p>非关键链路（如重排，失败可降级原序）应传入较小次数，避免上游抖动时
     * 多次重试 + 退避把单请求拖到十几秒。</p>
     *
     * @param circuitKey  熔断维度（如 "embedding" / "ocr" / "rerank"）
     * @param maxAttempts 最大尝试次数（含首次）
     * @param action      实际调用（抛出异常表示失败）
     * @param <T>         返回类型
     * @return 调用结果
     * @throws AiGenerationException 熔断打开或全部重试失败时抛出，由调用方决定降级策略
     */
    public <T> T invoke(String circuitKey, int maxAttempts, Supplier<T> action) {
        CircuitState state = circuits.computeIfAbsent(circuitKey, k -> new CircuitState());
        long now = System.currentTimeMillis();
        // 熔断打开：快速失败，不消耗 API
        if (state.openUntilEpochMs > now) {
            throw new AiGenerationException("AI 接口 [" + circuitKey + "] 熔断中，请稍后重试");
        }
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = action.get();
                state.consecutiveFailures = 0; // 成功重置计数
                return result;
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt == maxAttempts) {
                    state.consecutiveFailures++;
                    if (state.consecutiveFailures >= FAILURE_THRESHOLD) {
                        state.openUntilEpochMs = System.currentTimeMillis() + OPEN_DURATION_MS;
                        state.consecutiveFailures = 0;
                        log.warn("AI 接口 [{}] 连续失败 {} 次，熔断 {} 分钟", circuitKey, FAILURE_THRESHOLD, OPEN_DURATION_MS / 60000);
                    }
                    throw e;
                }
                log.warn("AI 接口 [{}] 第 {} 次调用失败，{}ms 后重试: {}", circuitKey, attempt, BASE_BACKOFF_MS * (1L << (attempt - 1)), e.getMessage());
                try {
                    Thread.sleep(BASE_BACKOFF_MS * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IllegalStateException("unreachable, last error: " + lastError);
    }

    /**
     * 本地内存缓存调用：命中且未过期直接返回，否则执行 loader 并缓存。
     *
     * <p>缓存键需按命名空间前缀避免类型串扰（如 {@code "emb:" + text}、{@code "ocr:" + imageMd5}）。</p>
     *
     * @param cacheKey 缓存键（建议带命名空间前缀）
     * @param loader   未命中时的加载器
     * @param ttlMs    有效期（毫秒；<=0 用默认 24h）
     * @param <T>      返回类型
     * @return 结果
     */
    @SuppressWarnings("unchecked")
    public <T> T cached(String cacheKey, Supplier<T> loader, long ttlMs) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && entry.expiresAtEpochMs > now) {
            return (T) entry.value;
        }
        T value = loader.get();
        cache.put(cacheKey, new CacheEntry(value, now + (ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS)));
        if (cache.size() > CACHE_CAPACITY) {
            cache.clear();
        }
        return value;
    }
}
