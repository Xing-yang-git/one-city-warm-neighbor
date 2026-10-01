package com.platform.ai.moderation;

/**
 * 审核任务载体 — 携带内容标识与重试次数，供线程池的拒绝处理器读取。
 *
 * <p>为什么不能用裸 {@link Runnable}：拒绝处理器只能拿到「提交时传进去的那个对象」。
 * 若走 {@code executor.submit(runnable)}，JDK 会先把它包成 {@code FutureTask}，
 * 处理器就再也读不到 type/id、无法按内容 id 做幂等重投——所以提交必须用
 * {@code execute(ModerationTask)} 而非 {@code submit(...)}。</p>
 *
 * @param type     内容大类，取值见 {@link com.platform.common.ContentType#IDLE} /
 *                 {@link com.platform.common.ContentType#HELP}——与内容列表用的是同一套
 *                 「闲置 / 互助」划分，故复用该常量类而非另立一份
 * @param id       内容实体 id（重投时据此从库重载并做状态校验）
 * @param attempts 已被拒次数（首次提交为 0，每次被拒由拒绝处理器加一）
 * @param delegate 实际执行体（审核逻辑本身）
 */
record ModerationTask(String type, Long id, int attempts, Runnable delegate) implements Runnable {

    @Override
    public void run() {
        delegate.run();
    }
}
