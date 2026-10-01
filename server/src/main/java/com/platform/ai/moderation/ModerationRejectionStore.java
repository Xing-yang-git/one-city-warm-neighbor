package com.platform.ai.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审核任务拒绝处理器 — 线程池满时把任务暂存到 Redis，等待定时任务重投。
 *
 * <p><b>为什么暂存而不是丢弃</b>：审核是「要写回结果才算完成」的任务，队列满时直接丢弃会让内容长期停在
 * 待审核态。但这里也<b>不需要</b>新建数据库任务表——内容本身的 {@code moderationStatus} 始终是
 * {@code PENDING}，每日凌晨的 {@link ContentModerationScheduler} 本就会把这类内容重新捡起来。
 * 所以 Redis 队列只是<b>分钟级的快速重试通道</b>，真正的正确性兜底是内容状态本身：
 * <b>Redis 丢了不丢任务</b>，只是退化成次日巡检。</p>
 *
 * <p><b>用 ZSet 而非 List</b>：member 为任务 JSON，score 为「下次最早可重投时间戳」。这样扫描只需一条
 * {@code ZRANGEBYSCORE} 就能取出「已到期的」，退避也只是把 score 往后推；List 没有到期语义，
 * 只能每轮全量取出重试，会造成「取出→再拒→放回」的热循环。</p>
 *
 * <p><b>异常绝不外抛</b>：本类被线程池在提交线程上同步调用，外抛会把 {@code RejectedExecutionException}
 * 带回发布接口的调用点。任何异常（含 Redis 不可用）都在此处自吞并计入 {@link #redisFailTotal()}。</p>
 */
@Slf4j
@Component
public class ModerationRejectionStore implements RejectedExecutionHandler {

    /** 重试队列 key（ZSet：member = 任务 JSON，score = 下次可重投的 epoch 毫秒） */
    static final String RETRY_KEY = "moderation:retry";

    /** 队列整体 TTL：与每日巡检周期对齐，避免长期空转的 key 永久驻留 */
    private static final Duration RETRY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最大重试次数，超过则不再入队、交回每日巡检兜底 */
    @Value("${ai.moderation.retry-max-attempts:3}")
    private int maxAttempts;

    /** 退避基数（毫秒）：第 n 次重试等待 base × 2^(n-1) */
    @Value("${ai.moderation.retry-backoff-ms:30000}")
    private long baseBackoffMs;

    /** 累计被拒次数 */
    private final AtomicLong rejectedTotal = new AtomicLong();

    /** 累计因超过重试上限而交回每日巡检的次数 */
    private final AtomicLong exhaustedTotal = new AtomicLong();

    /** 累计因 Redis 不可用而未能暂存的次数 */
    private final AtomicLong redisFailTotal = new AtomicLong();

    /**
     * 构造器注入。
     *
     * @param redisTemplate Redis 字符串模板（项目统一用它存 JSON）
     */
    public ModerationRejectionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 线程池拒绝回调：把任务暂存进 Redis 重试队列，超过上限则放弃并交回每日巡检。
     *
     * @param task     被拒绝的任务（期望是 {@link ModerationTask}）
     * @param executor 触发拒绝的线程池（仅用于日志记录剩余容量）
     */
    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        rejectedTotal.incrementAndGet();
        // 非本模块提交的任务：不写 Redis（避免污染重试队列载荷），仅告警
        if (!(task instanceof ModerationTask moderationTask)) {
            log.warn("审核任务被拒但无法识别内容标识，直接丢弃: taskType={}, 队列剩余容量={}",
                    task.getClass().getSimpleName(), executor.getQueue().remainingCapacity());
            return;
        }
        int attempts = moderationTask.attempts() + 1;
        if (attempts > maxAttempts) {
            exhaustedTotal.incrementAndGet();
            log.warn("审核任务重试已达上限，交回每日巡检兜底: type={}, id={}, 已试 {} 次",
                    moderationTask.type(), moderationTask.id(), maxAttempts);
            return;
        }
        long backoff = backoffMillis(attempts);
        try {
            redisTemplate.opsForZSet().add(RETRY_KEY,
                    toJson(moderationTask.type(), moderationTask.id(), attempts),
                    System.currentTimeMillis() + backoff);
            // 每次写入刷新 TTL：保证队列不会永久驻留
            redisTemplate.expire(RETRY_KEY, RETRY_TTL);
            log.warn("审核任务被拒，已入重试队列: type={}, id={}, 第 {} 次, {}ms 后重试",
                    moderationTask.type(), moderationTask.id(), attempts, backoff);
        } catch (Exception e) {
            // 绝不外抛（见类注释）。任务丢失不影响正确性：内容仍为 PENDING，由每日巡检兜底
            redisFailTotal.incrementAndGet();
            log.error("审核任务暂存 Redis 失败（内容仍为待审核，将由每日巡检兜底）: type={}, id={}",
                    moderationTask.type(), moderationTask.id(), e);
        }
    }

    /**
     * 取出已到期的重试任务，并从队列中移除。
     *
     * <p>先移除再返回：若重投时池子又满，拒绝处理器会以「attempts 已加一的新 member」重新入队，
     * 不会与本条重复。</p>
     *
     * @param max   单次最多取出条数
     * @param nowMs 当前时刻（epoch 毫秒）
     * @return 到期任务列表；Redis 异常或无到期任务时返回空列表
     */
    List<TaskRef> pollDue(int max, long nowMs) {
        List<TaskRef> due = new ArrayList<>();
        try {
            Set<String> members = redisTemplate.opsForZSet().rangeByScore(RETRY_KEY, 0, nowMs, 0, max);
            if (members == null || members.isEmpty()) {
                return due;
            }
            for (String member : members) {
                redisTemplate.opsForZSet().remove(RETRY_KEY, member);
                TaskRef task = parse(member);
                if (task == null) {
                    log.warn("审核重试队列存在无法解析的成员，已丢弃: {}", member);
                    continue;
                }
                due.add(task);
            }
        } catch (Exception e) {
            log.warn("审核重试队列读取失败（跳过本轮）: {}", e.getMessage());
        }
        return due;
    }

    /**
     * 当前队列存量（供调度器汇总日志展示）。
     *
     * @return 队列中的任务条数；Redis 异常时返回 0
     */
    long size() {
        try {
            Long count = redisTemplate.opsForZSet().zCard(RETRY_KEY);
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("审核重试队列长度读取失败: {}", e.getMessage());
            return 0L;
        }
    }

    /** @return 累计被拒次数 */
    long rejectedTotal() {
        return rejectedTotal.get();
    }

    /** @return 累计因超过重试上限而交回每日巡检的次数 */
    long exhaustedTotal() {
        return exhaustedTotal.get();
    }

    /** @return 累计因 Redis 不可用而未能暂存的次数 */
    long redisFailTotal() {
        return redisFailTotal.get();
    }

    /**
     * 计算第 n 次重试的退避时长：base × 2^(n-1)，即 30s → 60s → 120s。
     *
     * @param attempts 已重试次数（从 1 开始）
     * @return 退避毫秒数
     */
    private long backoffMillis(int attempts) {
        return baseBackoffMs * (1L << (attempts - 1));
    }

    /**
     * 序列化任务为队列成员 JSON。
     *
     * @param type     内容类型
     * @param id       内容 id
     * @param attempts 已重试次数
     * @return JSON 字符串（字段取短名以压缩队列体积）
     */
    private String toJson(String type, Long id, int attempts) {
        try {
            return objectMapper.writeValueAsString(Map.of("t", type, "id", id, "a", attempts));
        } catch (Exception e) {
            // Map 只含基本类型，序列化理论不可达；兜底走字符串拼接保证仍能入队
            log.warn("审核重试队列成员序列化失败，改用简化格式: {}", e.getMessage());
            return "{\"t\":\"" + type + "\",\"id\":" + id + ",\"a\":" + attempts + "}";
        }
    }

    /**
     * 解析队列成员。
     *
     * @param member 队列成员 JSON
     * @return 任务引用；格式非法时返回 null（调用方负责告警）
     */
    private TaskRef parse(String member) {
        try {
            JsonNode node = objectMapper.readTree(member);
            String type = node.path("t").asText("");
            if (type.isEmpty() || !node.hasNonNull("id")) {
                return null;
            }
            return new TaskRef(type, node.get("id").asLong(), node.path("a").asInt(1));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 重试队列中取出的任务引用。
     *
     * @param type     内容大类，取值见 {@link com.platform.common.ContentType#IDLE} /
     *                 {@link com.platform.common.ContentType#HELP}
     * @param id       内容实体 id
     * @param attempts 已重试次数
     */
    record TaskRef(String type, Long id, int attempts) {
    }
}
