package com.platform.ai.agent;

import com.platform.common.AppTimeZone;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 对话限流服务 — 防止付费 deepseek/embedding API 被刷爆额度。
 *
 * <p>双维度：每分钟 / 每天，Redis INCR 计数（TTL 自动过期）；Redis 不可用时降级内存窗口计数。</p>
 */
@Slf4j
@Service
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    /** 每分钟最大请求数 */
    private final int perMinute;

    /** 每天最大请求数 */
    private final int perDay;

    /** 降级内存窗口（每用户每分钟时间戳） */
    private final Map<String, Deque<Long>> minuteWindows = new ConcurrentHashMap<>();

    /** 降级内存每日计数（userId → (日期 → 计数)），Redis 不可用时仍保护每日配额 */
    private final Map<String, Map<String, Integer>> dayCounts = new ConcurrentHashMap<>();

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public RateLimitService(StringRedisTemplate redisTemplate,
                            @Value("${ai.agent.rate-limit-per-minute:10}") int perMinute,
                            @Value("${ai.agent.rate-limit-per-day:100}") int perDay) {
        this.redisTemplate = redisTemplate;
        this.perMinute = perMinute;
        this.perDay = perDay;
    }

    /**
     * 尝试获取一个请求配额（Redis INCR 优先，异常降级内存）。
     *
     * @param userId 用户 ID
     * @return true = 允许，false = 超限
     */
    public boolean tryAcquire(String userId) {
        try {
            return redisTryAcquire(userId);
        } catch (Exception e) {
            log.warn("Redis 限流不可用，降级内存计数: {}", e.getMessage());
            return memoryTryAcquire(userId);
        }
    }

    /**
     * Redis 双维度计数限流。
     *
     * @param userId 用户 ID
     * @return true = 允许
     */
    private boolean redisTryAcquire(String userId) {
        // 分钟维度
        String minuteKey = "agent:rl:" + userId + ":m:" + LocalDateTime.now(AppTimeZone.APP_ZONE).format(MINUTE_FMT);
        Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
        redisTemplate.expire(minuteKey, Duration.ofSeconds(65));
        if (minuteCount != null && minuteCount > perMinute) {
            log.warn("Agent 对话分钟限流触发: userId={}, count={}", userId, minuteCount);
            return false;
        }

        // 天维度
        String dayKey = "agent:rl:" + userId + ":d:" + LocalDateTime.now(AppTimeZone.APP_ZONE).format(DAY_FMT);
        Long dayCount = redisTemplate.opsForValue().increment(dayKey);
        redisTemplate.expire(dayKey, Duration.ofDays(1));
        if (dayCount != null && dayCount > perDay) {
            log.warn("Agent 对话天限流触发: userId={}, count={}", userId, dayCount);
            return false;
        }
        return true;
    }

    /**
     * 内存窗口计数限流（Redis 降级兜底，含每日配额保护）。
     *
     * @param userId 用户 ID
     * @return true = 允许
     */
    private boolean memoryTryAcquire(String userId) {
        // 分钟窗口
        long now = System.currentTimeMillis();
        Deque<Long> window = minuteWindows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < now - 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                log.warn("Agent 对话内存限流触发（分钟）: userId={}", userId);
                return false;
            }
            window.addLast(now);
        }
        // 每日配额（跨日重置）
        String today = LocalDateTime.now(AppTimeZone.APP_ZONE).format(DAY_FMT);
        Map<String, Integer> perUserDay = dayCounts.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        synchronized (perUserDay) {
            Integer count = perUserDay.get(today);
            if (count == null) {
                perUserDay.clear();   // 跨日清空旧计数
                perUserDay.put(today, 1);
                return true;
            }
            if (count >= perDay) {
                log.warn("Agent 对话内存限流触发（每日）: userId={}, count={}", userId, count);
                return false;
            }
            perUserDay.put(today, count + 1);
            return true;
        }
    }

}
