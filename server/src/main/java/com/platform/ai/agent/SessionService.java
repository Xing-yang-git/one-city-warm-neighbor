package com.platform.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.platform.common.AppTimeZone;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 热会话服务 — Redis 存储多轮对话上下文（单设备单会话）。
 *
 * <p>Key：{@code agent:session:{userId}}，TTL 续期，写时截断（超轮数丢最旧 + 超字符裁剪）。
 * Redis 不可用时降级为无会话（单轮对话），不阻断主链路。</p>
 */
@Slf4j
@Service
public class SessionService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentSessionGuard guard;

    /** 会话 TTL（小时） */
    @Value("${ai.agent.session-ttl-hours:24}")
    private int sessionTtlHours;

    /** 保留轮数（消息数上限 = 轮数×2，含 user+assistant） */
    @Value("${ai.agent.max-turns:10}")
    private int maxTurns;

    /** 历史序列化最大字符数 */
    @Value("${ai.agent.max-history-chars:6000}")
    private int maxHistoryChars;

    private static final String SESSION_PREFIX = "agent:session:";

    public SessionService(StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          AgentSessionGuard guard) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.guard = guard;
    }

    /**
     * 读取热会话（无则返回 null）。
     *
     * @param userId 住户用户 ID
     * @return 热会话，或 null
     */
    public AgentSession getSession(Long userId) {
        try {
            String json = redisTemplate.opsForValue().get(SESSION_PREFIX + userId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, AgentSession.class);
        } catch (JsonProcessingException e) {
            log.warn("AgentSession会话JSON反序列化异常 userId={}", userId, e);
            return null;
        } catch (Exception e) {
            // 必须兜底 Exception，不能只 catch io.lettuce.core.RedisException：Spring Data Redis 会将
            // Lettuce 的 RedisConnectionException 包装成 RedisConnectionFailureException 抛出，
            // 原始 RedisException 只作为 cause 存在——只 catch RedisException 接不住真实断连，
            // 异常会抛穿整条对话链路，与「Redis 不可用降级为无会话、不阻断主链路」的约定相悖
            log.warn("Redis 会话读取失败（降级无会话）: userId={}, {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入热会话并续期 TTL。
     *
     * @param userId  住户用户 ID
     * @param session 热会话
     */
    public void saveSession(Long userId, AgentSession session) {
        try {
            redisTemplate.opsForValue().set(
                    SESSION_PREFIX + userId,
                    objectMapper.writeValueAsString(session),
                    Duration.ofHours(sessionTtlHours));
        } catch (Exception e) {
            log.warn("Redis 会话写入失败（降级无会话）: userId={}, {}", userId, e.getMessage());
        }
    }

    /**
     * 获取会话历史消息（供 AgentService 拼进 LLM 上下文）。
     *
     * @param userId 住户用户 ID
     * @return 历史消息列表（可能为空）
     */
    public List<AgentSession.AgentMessageItem> getHistory(Long userId) {
        AgentSession session = getSession(userId);
        return session != null && session.getMessages() != null ? session.getMessages() : List.of();
    }

    /**
     * 清空用户热会话（供消息前置过滤器处理 /clear、/reset、清除对话 等控制指令时调用）。
     *
     * <p>重置为空对象并保存（saveSession 自带 TTL 续期），新会话从空上下文开始。</p>
     *
     * @param userId 住户用户 ID
     */
    public void clearSession(Long userId) {
        // 全量覆盖写：必须与 append/归档互斥，否则并发 append 用旧快照写回会把已清空的消息「复活」
        guard.executeExclusive(userId, () -> saveSession(userId, new AgentSession()));
    }

    /**
     * 追加一条消息并写回（含截断）。
     *
     * @param userId  住户用户 ID
     * @param role    角色：user/assistant/tool
     * @param content 消息内容
     * @param sources 引用来源 JSON（assistant 消息用，可为空）
     * @param actions 动作卡片 JSON（assistant 消息用，可为空）
     * @return 追加后的会话（Redis 写入失败时内部降级，恒返回非 null）
     */
    public AgentSession append(Long userId, String role, String content, String sources, String actions) {
        return guard.runExclusive(userId, () -> {
            AgentSession session = getSession(userId);
            if (session == null) {
                session = new AgentSession();
            }
            LocalDateTime now = LocalDateTime.now(AppTimeZone.APP_ZONE);
            session.getMessages().add(new AgentSession.AgentMessageItem(role, content, sources, actions, now));
            session.setLastActive(now);
            // 截断依赖本次读出的同一份快照并原地改写 archivedPrefixCount，必须在锁内计算
            // （严禁把 truncate 提前到 runExclusive 之外：陈旧快照会把前缀计数算小，导致已归档回填消息被重复归档）
            AgentSession truncated = truncate(session);
            saveSession(userId, truncated);
            warnIfConsecutiveRole(userId, role, truncated);
            return truncated;
        });
    }

    /**
     * 连续同角色自检：新增消息与紧邻的前一条角色相同时记 WARN。
     *
     * <p>user 连续 = 热会话出现脏数据（正常一问一答应严格交替）；assistant 连续 = 同一问题被回答两次。
     * 注意存在合法误报源：LLM 调用失败时 assistant 未写入，下一条用户消息会正常构成 user-user，
     * 故日志文案带「疑似」，仅供定位线索，不作判定依据。</p>
     *
     * @param userId    住户用户 ID
     * @param role      本次新增消息的角色
     * @param session   写入后的会话快照
     */
    private void warnIfConsecutiveRole(Long userId, String role, AgentSession session) {
        List<AgentSession.AgentMessageItem> messages = session.getMessages();
        int size = messages.size();
        if (size < 2) {
            return;
        }
        String previousRole = messages.get(size - 2).role();
        if (role != null && role.equals(previousRole)) {
            log.warn("Agent 热会话出现连续同角色消息（疑似并发丢更新）: userId={}, role={}, 总条数={}",
                    userId, role, size);
        }
    }

    /**
     * 会话截断：超 max-turns×2 条丢最旧；超 max-history-chars 字符从头部裁剪（至少保留 6 条）。
     *
     * <p>丢弃最旧消息时，若丢掉的属于已归档回填前缀（resume 回填），同步减小
     * {@code archivedPrefixCount}，避免前缀计数超过消息条数导致归档判断错乱。</p>
     *
     * @param session 会话
     * @return 截断后的会话
     */
    private AgentSession truncate(AgentSession session) {
        List<AgentSession.AgentMessageItem> messages = session.getMessages();
        int maxCount = maxTurns * 2;
        int dropped = 0;
        if (messages.size() > maxCount) {
            dropped = messages.size() - maxCount;
            // 丢掉前面 `dropped` 条消息，保留从下标 `dropped` 到末尾的全部消息
            messages = new ArrayList<>(messages.subList(dropped, messages.size()));
        }
        // 字符裁剪（至少保留 keepMin 条，避免丢失最近上下文）,统计整个消息列表所有消息内容的字符总长度
        int totalChars = messages.stream().mapToInt(m -> len(m.content())).sum();
        int keepMin = 6;
        while (totalChars > maxHistoryChars && messages.size() > keepMin) {
            totalChars -= len(messages.get(0).content());
            messages = new ArrayList<>(messages.subList(1, messages.size()));
            dropped++;
        }
        session.setMessages(messages);
        // 丢弃的总是最旧消息，最旧的正是已归档回填前缀，故前缀计数减去丢弃条数（下限 0）
        session.setArchivedPrefixCount(Math.max(0, session.getArchivedPrefixCount() - dropped));
        return session;
    }

    private int len(String s) {
        return s == null ? 0 : s.length();
    }
}
