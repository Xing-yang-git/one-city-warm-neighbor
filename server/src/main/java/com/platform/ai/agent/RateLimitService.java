package com.platform.ai.agent;

import com.platform.common.AppTimeZone;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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

    /** 分钟滑动窗口长度（毫秒）——同时也是降级窗口的陈旧判定阈值 */
    private static final long MINUTE_WINDOW_MS = 60_000L;

    /** 降级记录清扫周期（毫秒） */
    private static final long EVICT_INTERVAL_MS = 30_000L;

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
            /**
             * 修改日志打印，避免误导：
             * `e.getMessage()` 获取的是**Redis 抛出的异常信息**（例如连接拒绝、读取超时），跟 “降级内存计数” 这几个字没有任何关系。
             * SLF4J 规范：**把 exception 对象放到最后参数**，不要用`e.getMessage()`
             * 把异常对象 e 作为最后参数，日志自动带上完整堆栈。
             */
            log.warn("Redis 限流不可用，降级内存计数", e);
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
        // 使用 Redis INCR 计数
        Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
        // TTL 65 秒（防止跨分钟计数丢失）
        redisTemplate.expire(minuteKey, Duration.ofSeconds(65));
        if (minuteCount != null && minuteCount > perMinute) {
            log.warn("Agent 对话分钟限流触发: userId={}, count={}", userId, minuteCount);
            return false;
        }

        // 天维度
        String dayKey = "agent:rl:" + userId + ":d:" + LocalDateTime.now(AppTimeZone.APP_ZONE).format(DAY_FMT);
        // 使用 Redis INCR 计数
        Long dayCount = redisTemplate.opsForValue().increment(dayKey);
        // TTL 1 天（防止跨日计数丢失）
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
     * @return true = 允许；false = 被限流拦截
     */
    private boolean memoryTryAcquire(String userId) {
        // ========== 1、分钟维度：内存滑动窗口限流 ==========
        // 获取当前时间戳（毫秒）
        long now = System.currentTimeMillis();
        // minuteWindows:
        // ConcurrentHashMap<String,Deque<Long>>，获取存该用户请求时间戳队列，没有则创建一个新的队列
        Deque<Long> window = minuteWindows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        // 对该用户的队列加锁，多线程并发操作同一个用户队列防止并发错乱
        synchronized (window) {
            // 清理队列：把队列头部早于【当前时间 - MINUTE_WINDOW_MS（1 分钟）】的过期时间戳全部弹出
            while (!window.isEmpty() && window.peekFirst() < now - MINUTE_WINDOW_MS) {
                window.pollFirst();
            }
            // 当前窗口内请求数达到阈值，分钟限流触发
            if (window.size() >= perMinute) {
                log.warn("Agent 对话内存限流触发（分钟）: userId={}", userId);
                return false;
            }
            // 将本次请求时间戳加入队尾
            window.addLast(now);
        }

        // ========== 2、每日配额限流 内存实现 ==========
        // 获取今天日期字符串，例如 20260916
        String today = LocalDateTime.now(AppTimeZone.APP_ZONE).format(DAY_FMT);
        // dayCounts: ConcurrentHashMap<String, Map<String,Integer>>
        // 外层key：userId；内层map key：日期字符串，value：当日请求次数
        Map<String, Integer> perUserDay = dayCounts.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        synchronized (perUserDay) {
            Integer count = perUserDay.get(today);
            if (count == null) {
                // 拿不到今日计数 → 说明跨到新的一天
                perUserDay.clear(); // 清空昨天及更早所有旧计数
                perUserDay.put(today, 1);
                return true;
            }
            // 判断是否达到每日上限
            if (count >= perDay) {
                log.warn("Agent 对话内存限流触发（每日）: userId={}, count={}", userId, count);
                return false;
            }
            // 计数+1
            perUserDay.put(today, count + 1);
            return true;
        }
    }

    /**
     * 清扫降级内存中的陈旧记录（每 30 秒一轮）。
     *
     * <p>降级记录按 userId 常驻且只增不减：Redis 恢复后不会回收，长时间降级会让内存随
     * 「曾触发过降级的用户数」单调增长。此处按各自可判定的陈旧条件移除：</p>
     * <ul>
     *   <li><b>分钟窗口</b>：最后一次请求已滑出 60 秒窗口 —— 该窗口不再参与任何计数；</li>
     *   <li><b>天计数</b>：内层不含今天的日期 —— 今天尚未产生计数。</li>
     * </ul>
     *
     * <p>内存上界因此收敛为「今天活跃过的用户数」。<b>今天已计数的必须保留</b>——
     * 若按空闲时间无差别清理，用户等过阈值再回来即可重置当日配额，日限流形同虚设。</p>
     *
     * <p><b>残余竞态</b>：判定与移除之间仍有极窄窗口——写入线程可能已从 map 取到实例、
     * 尚未写入，此刻被摘除后其计数落在已脱离 map 的对象上。后果是偶发放宽一次限流（少计一次），
     * 不是数据丢失；窗口在微秒级，且仅 Redis 不可用时才走到这条路径。用两参
     * {@code remove(key, value)} 也消除不了：天计数的内层 map 是原地修改的，值引用始终不变。</p>
     */
    @Scheduled(fixedDelay = EVICT_INTERVAL_MS, initialDelay = EVICT_INTERVAL_MS)
    public void evictStaleEntries() {
        long now = System.currentTimeMillis();
        String today = LocalDateTime.now(AppTimeZone.APP_ZONE).format(DAY_FMT);

        int evictedWindows = 0;
        for (Map.Entry<String, Deque<Long>> entry : minuteWindows.entrySet()) {
            Deque<Long> window = entry.getValue();
            // 锁内判定 + 锁内移除：与写入路径互斥，避免判定完窗口又被写入
            synchronized (window) {
                if (!window.isEmpty() && window.peekLast() >= now - MINUTE_WINDOW_MS) {
                    // 窗口内仍有未过期的时间戳，仍在参与计数
                    continue;
                }
                if (minuteWindows.remove(entry.getKey(), window)) {
                    evictedWindows++;
                }
            }
        }

        int evictedDays = 0;
        for (Map.Entry<String, Map<String, Integer>> entry : dayCounts.entrySet()) {
            Map<String, Integer> perUserDay = entry.getValue();
            synchronized (perUserDay) {
                if (perUserDay.containsKey(today)) {
                    // 今天已有计数，必须保留，否则日配额被重置
                    continue;
                }
                if (dayCounts.remove(entry.getKey(), perUserDay)) {
                    evictedDays++;
                }
            }
        }

        if (evictedWindows > 0 || evictedDays > 0) {
            log.info("Agent 限流降级记录清扫: 移除分钟窗口 {} 个、天计数 {} 个；存量分钟 {} / 天 {}",
                    evictedWindows, evictedDays, minuteWindows.size(), dayCounts.size());
        }
    }

}
