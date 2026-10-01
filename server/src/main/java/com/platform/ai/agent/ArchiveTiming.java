package com.platform.ai.agent;

/**
 * 归档耗时采集器 — 单次归档调用私有的累加容器（可变、非线程安全，仅在归档调用链内传递）。
 *
 * <p>为什么需要它：归档耗时分散在三个层级——公有入口的加锁等待与 Redis 读、{@code doArchive} 的
 * PG 写入、{@code triggerCompression} 的同步查询与压缩提交。用局部变量无法跨方法累加，故用一个
 * 可变 holder 逐层 {@code add}，最后在**锁外**一次性输出（日志 I/O 不得进用户锁临界区）。</p>
 *
 * <p>同时携带日志所需的上下文（会话级 id / 归档条数 / 归档行 id）——因为日志必须挪到锁外打，
 * 那时 {@link AgentSession} 已不在作用域内。</p>
 *
 * <p>耗时为累计值而非单次值：{@code pgConv} 是两次 {@code conversationRepository.save}
 * （INSERT 建行 + UPDATE 回填 conversation_id）之和。</p>
 */
final class ArchiveTiming {

    /** 归档入口：消息数达阈值的最旧 N 条窗口归档 */
    static final String PATH_WINDOW = "window";

    /** 归档入口：剩余全部归档（exit / clear / resume 切走） */
    static final String PATH_REMAINING = "remaining";

    /** 归档入口：空闲超时兜底（调度器扫描） */
    static final String PATH_IDLE = "idle";

    /** 本次归档走的入口，供日志区分样本来源 */
    final String path;

    /** 会话级 id（doArchive 内填充，锁外打日志用） */
    Long conversationId;

    /** 本次归档的消息条数（doArchive 内填充） */
    int archivedCount;

    /** 归档行 id（公开入口回填；为 null 表示本次没有真正建行，不输出耗时日志） */
    Long archiveRowId;

    /** 加锁等待耗时（纳秒）——被其它线程持锁挡住的时间 */
    long lockWaitNanos;

    /** 持锁期间的总耗时（纳秒）——用于反推 {@link #lockWaitNanos} */
    long lockHoldNanos;

    /** Redis 读热会话 + JSON 反序列化（纳秒） */
    long redisReadNanos;

    /** Redis 写回热会话 + 序列化（纳秒；archiveRemaining 尾部清理会话也计入） */
    long redisWriteNanos;

    /** PG 查用户（取 tenantId）一次 SELECT（纳秒） */
    long pgUserNanos;

    /** PG 写归档行两次 save（INSERT 建行 + UPDATE 回填 conversation_id）之和（纳秒） */
    long pgConvNanos;

    /** PG 写归档消息（纳秒）——条数≈窗口大小，且主键 IDENTITY 无法批处理，预期为大头 */
    long pgMsgsNanos;

    /** 同步查询压缩段序号 segmentNoOf（纳秒） */
    long segQueryNanos;

    /** 提交压缩任务（纳秒）——压缩池满时 CallerRuns 会在此同步执行压缩，此项用于抓该回落 */
    long compressEnqueueNanos;

    /**
     * @param path 归档入口标识（见本类 {@code PATH_*} 常量）
     */
    ArchiveTiming(String path) {
        this.path = path;
    }

    /**
     * 纳秒转毫秒（日志展示用，四舍五入到整数毫秒）。
     *
     * <p>亚毫秒分段会显示为 0——本测量关注的是「归档整体是否慢到影响窗口策略」，
     * 若各分段普遍为 0，本身就说明归档耗时可以忽略。</p>
     *
     * @param nanos 耗时（纳秒）
     * @return 耗时（毫秒）
     */
    static long toMs(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }
}
