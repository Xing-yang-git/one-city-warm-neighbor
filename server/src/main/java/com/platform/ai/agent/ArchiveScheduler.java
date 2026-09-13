package com.platform.ai.agent;

import com.platform.common.AppTimeZone;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Agent 会话归档调度器 — 每 5 分钟扫描 Redis 热会话，空闲超时（archive-idle-minutes）即归档。
 *
 * <p>用 SCAN 游标遍历（避免 KEYS 阻塞 Redis）；Redis 不可用时跳过本轮（热会话仍保留到 TTL）。</p>
 */
@Slf4j
@Component
public class ArchiveScheduler {

    private final StringRedisTemplate redisTemplate;
    private final ArchiveService archiveService;
    private final ObjectMapper objectMapper;

    /** 空闲归档阈值（分钟） */
    @Value("${ai.agent.archive-idle-minutes:15}")
    private int idleMinutes;

    private static final String SESSION_PREFIX = "agent:session:";

    public ArchiveScheduler(StringRedisTemplate redisTemplate,
                            ArchiveService archiveService,
                            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.archiveService = archiveService;
        this.objectMapper = objectMapper;
    }

    /**
     * 定时扫描归档：遍历 agent:session:*，空闲超时的会话按会话结束语义归档剩余全部消息到 PG。
     * 归档后热会话保留到 TTL 自然过期（支持"继续上次"冷启动）。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scanAndArchive() {
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(SESSION_PREFIX + "*").count(100).build())) {
            int archived = 0;
            while (cursor.hasNext()) {
                // 逐条捕获：单用户归档异常（如未绑定小区）不中断整轮扫描
                try {
                    String key = cursor.next();
                    Long userId = parseUserId(key);
                    if (userId == null) {
                        continue;
                    }
                    if (isIdle(userId)) {
                        archiveService.archiveRemaining(userId);
                        archived++;
                    }
                } catch (Exception e) {
                    log.warn("单用户归档失败，继续本轮: {}", e.getMessage(), e);
                }
            }
            if (archived > 0) {
                log.info("归档调度完成: 归档 {} 个空闲会话", archived);
            }
        } catch (Exception e) {
            log.warn("归档调度扫描失败（跳过本轮）: {}", e.getMessage(), e);
        }
    }

    /**
     * 判断热会话是否空闲超时。
     *
     * @param userId 住户用户 ID
     * @return true = 空闲超时需归档
     */
    private boolean isIdle(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(SESSION_PREFIX + userId);
            if (json == null) {
                return false;
            }
            AgentSession session = objectMapper.readValue(json, AgentSession.class);
            return session.getLastActive() != null
                    && session.getLastActive().isBefore(LocalDateTime.now(AppTimeZone.APP_ZONE).minusMinutes(idleMinutes));
        } catch (Exception e) {
            log.warn("读取热会话失败: userId={}, {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 从 Redis key（agent:session:{userId}）解析 userId。
     *
     * @param key Redis key
     * @return userId，解析失败返回 null
     */
    private Long parseUserId(String key) {
        try {
            return Long.valueOf(key.substring(SESSION_PREFIX.length()));
        } catch (Exception e) {
            return null;
        }
    }
}
