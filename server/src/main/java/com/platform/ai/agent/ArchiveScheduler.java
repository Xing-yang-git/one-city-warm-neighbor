package com.platform.ai.agent;

import com.platform.common.AppTimeZone;
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
 *
 * <p>互斥由 {@link AgentSessionGuard} 提供：本类不直接持锁，判空闲（{@link #isIdle}）只是<b>无锁预过滤</b>，
 * 用于跳过绝大多数无关 key、避免无谓加锁；权威判定与归档在 {@link ArchiveService#archiveIfIdle} 的锁内完成。
 * 遍历多用户时逐个进出循环体，从不同时持有两把用户锁（{@link AgentSessionGuard} 类注释中的硬约束）。</p>
 */
@Slf4j
@Component
public class ArchiveScheduler {

    private final StringRedisTemplate redisTemplate;
    private final ArchiveService archiveService;
    private final SessionService sessionService;

    /** 空闲归档阈值（分钟） */
    @Value("${ai.agent.archive-idle-minutes:15}")
    private int idleMinutes;

    /** 热会话 key 前缀（与 SessionService.SESSION_PREFIX 约定一致，供 SCAN 匹配与反解析 userId） */
    private static final String SESSION_PREFIX = "agent:session:";

    public ArchiveScheduler(StringRedisTemplate redisTemplate,
                            ArchiveService archiveService,
                            SessionService sessionService) {
        this.redisTemplate = redisTemplate;
        this.archiveService = archiveService;
        this.sessionService = sessionService;
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
                    // 计数以锁内权威判定为准：无锁预过滤通过但锁内已非空闲时不计数，避免统计虚高
                    if (isIdle(userId) && archiveService.archiveIfIdle(userId, idleMinutes)) {
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
     * 空闲预过滤（无锁，仅供跳过无关 key）。
     *
     * <p>复用 {@link SessionService#getSession} 读取，避免在本类重复一份 AgentSession 反序列化逻辑
     * 随实体演进漂移；判定结果不作为归档依据——真正的判定在 {@link ArchiveService#archiveIfIdle} 锁内重做。</p>
     *
     * @param userId 住户用户 ID
     * @return true = 疑似空闲超时，值得尝试归档
     */
    private boolean isIdle(Long userId) {
        AgentSession session = sessionService.getSession(userId);
        return session != null
                && session.getMessages() != null
                && !session.getMessages().isEmpty()
                && session.getLastActive() != null
                && session.getLastActive().isBefore(LocalDateTime.now(AppTimeZone.APP_ZONE).minusMinutes(idleMinutes));
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
