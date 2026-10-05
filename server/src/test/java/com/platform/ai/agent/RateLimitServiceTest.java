package com.platform.ai.agent;

import com.platform.common.AppTimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitService 限流服务单元测试 — 覆盖 Redis 双维度计数与内存降级窗口。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 限流服务单元测试")
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("Redis 限流 - 分钟与天均未超限时放行")
    void should_allow_when_underMinuteAndDayLimits() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        RateLimitService service = new RateLimitService(redisTemplate, 10, 100);

        boolean allowed = service.tryAcquire("user-1");

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("Redis 限流 - 分钟超限时拒绝")
    void should_reject_when_minuteLimitExceeded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 分钟计数 11 > 10，直接拒绝，不再校验天维度
        when(valueOperations.increment(anyString())).thenReturn(11L);
        RateLimitService service = new RateLimitService(redisTemplate, 10, 100);

        boolean allowed = service.tryAcquire("user-1");

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("Redis 限流 - 天超限时拒绝")
    void should_reject_when_dayLimitExceeded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 分钟计数 1 放行，天计数 101 > 100 拒绝
        when(valueOperations.increment(anyString())).thenReturn(1L, 101L);
        RateLimitService service = new RateLimitService(redisTemplate, 10, 100);

        boolean allowed = service.tryAcquire("user-1");

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("Redis 限流 - 计数返回 null 时视为未超限")
    void should_allow_when_incrementReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(null);
        RateLimitService service = new RateLimitService(redisTemplate, 10, 100);

        boolean allowed = service.tryAcquire("user-1");

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("内存降级 - Redis 不可用时降级为内存窗口计数")
    void should_fallbackToMemory_when_redisUnavailable() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        RateLimitService service = new RateLimitService(redisTemplate, 2, 100);

        // 每用户每分钟窗口上限 2：前 2 次放行，第 3 次拒绝
        assertThat(service.tryAcquire("user-1")).isTrue();
        assertThat(service.tryAcquire("user-1")).isTrue();
        assertThat(service.tryAcquire("user-1")).isFalse();

        // 不同用户互不影响
        assertThat(service.tryAcquire("user-2")).isTrue();
    }

    @Test
    @DisplayName("内存降级 - 每日配额超限时拒绝")
    void should_reject_when_memoryDayLimitExceeded() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        // 天配额 2：分钟窗口上限设为 10 避免先触达分钟限制
        RateLimitService service = new RateLimitService(redisTemplate, 10, 2);

        assertThat(service.tryAcquire("user-1")).isTrue();
        assertThat(service.tryAcquire("user-1")).isTrue();
        assertThat(service.tryAcquire("user-1")).isFalse();
    }

    @Test
    @DisplayName("内存降级 - Redis 异常后不再尝试 Redis")
    void should_neverTouchRedis_when_degraded() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        RateLimitService service = new RateLimitService(redisTemplate, 10, 100);

        service.tryAcquire("user-1");

        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("内存降级 - 清扫移除已滑出窗口的分钟记录，保留仍在窗口内的")
    @SuppressWarnings("unchecked")
    void should_evictStaleWindow_when_sweepRuns() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        RateLimitService service = new RateLimitService(redisTemplate, 10, 100);
        assertThat(service.tryAcquire("user-1")).isTrue();
        assertThat(service.tryAcquire("user-2")).isTrue();

        // 把 user-1 的窗口改成两分钟前的时间戳（已滑出 60 秒窗口）；user-2 保持刚写入的新鲜时间戳
        Map<String, Deque<Long>> minuteWindows =
                (Map<String, Deque<Long>>) ReflectionTestUtils.getField(service, "minuteWindows");
        Deque<Long> staleWindow = minuteWindows.get("user-1");
        synchronized (staleWindow) {
            staleWindow.clear();
            staleWindow.addLast(System.currentTimeMillis() - 120_000L);
        }

        service.evictStaleEntries();

        assertThat(minuteWindows).doesNotContainKey("user-1").containsKey("user-2");
    }

    @Test
    @DisplayName("内存降级 - 清扫保留今日计数，日配额不被重置")
    void should_keepTodayCount_when_sweepRuns() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        RateLimitService service = new RateLimitService(redisTemplate, 10, 2);
        assertThat(service.tryAcquire("user-1")).isTrue();
        assertThat(service.tryAcquire("user-1")).isTrue();

        service.evictStaleEntries();

        // 今日计数未被清扫，第 3 次仍被日配额挡下；若被清扫则会重新计数、放行
        assertThat(service.tryAcquire("user-1")).isFalse();
    }

    @Test
    @DisplayName("内存降级 - 清扫移除昨日的天计数")
    @SuppressWarnings("unchecked")
    void should_evictYesterdayCount_when_sweepRuns() {
        RateLimitService service = new RateLimitService(redisTemplate, 10, 100);

        // 直接注入一条只含昨日计数的记录（昨天来过、今天还没来的用户，Redis 恢复后也不会被回收）
        Map<String, Map<String, Integer>> dayCounts =
                (Map<String, Map<String, Integer>>) ReflectionTestUtils.getField(service, "dayCounts");
        String yesterday = LocalDateTime.now(AppTimeZone.APP_ZONE).minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        dayCounts.put("user-9", new ConcurrentHashMap<>(Map.of(yesterday, 5)));

        service.evictStaleEntries();

        assertThat(dayCounts).doesNotContainKey("user-9");
    }
}
