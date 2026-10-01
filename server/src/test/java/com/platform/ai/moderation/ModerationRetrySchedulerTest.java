package com.platform.ai.moderation;

import com.platform.common.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ModerationRetryScheduler 审核重投调度器单元测试 — 覆盖容量预检、逐条重投与单条失败不中断。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationRetryScheduler 审核重投调度器单元测试")
class ModerationRetrySchedulerTest {

    @Mock
    private ModerationRejectionStore rejectionStore;
    @Mock
    private ModerationService moderationService;
    @Mock
    private ThreadPoolTaskExecutor moderationExecutor;

    private ModerationRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ModerationRetryScheduler(rejectionStore, moderationService, moderationExecutor);
        ReflectionTestUtils.setField(scheduler, "retryBatch", 5);
    }

    /** 构造一个队列有剩余容量的底层线程池 */
    private ThreadPoolExecutor poolWithCapacity() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10));
    }

    @Test
    @DisplayName("容量预检 - 池子队列已满时本轮完全不碰重试队列")
    void should_skipRound_when_poolQueueFull() {
        ThreadPoolExecutor full = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        full.getQueue().add(() -> {
        });
        when(moderationExecutor.getThreadPoolExecutor()).thenReturn(full);

        scheduler.retryRejected();

        // 池子没空就去取只会「取出→再拒→放回」空转，且白白消耗任务的重试次数
        verifyNoInteractions(rejectionStore);
    }

    @Test
    @DisplayName("容量预检 - 剩余容量小于批大小时按剩余容量取，避免多取的任务被再拒白耗重试额度")
    void should_clampBatch_when_remainingLessThanBatch() {
        ThreadPoolExecutor oneSlot = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        when(moderationExecutor.getThreadPoolExecutor()).thenReturn(oneSlot);
        when(rejectionStore.pollDue(anyInt(), anyLong())).thenReturn(List.of());

        scheduler.retryRejected();

        // retryBatch=5 但只剩 1 个位置 → 只能取 1 条
        verify(rejectionStore).pollDue(eq(1), anyLong());
    }

    @Test
    @DisplayName("重投 - 逐条重投到期任务（含状态已变而跳过的）")
    void should_dispatchDueTasks() {
        when(moderationExecutor.getThreadPoolExecutor()).thenReturn(poolWithCapacity());
        when(rejectionStore.pollDue(anyInt(), anyLong())).thenReturn(List.of(
                new ModerationRejectionStore.TaskRef(ContentType.IDLE, 1L, 1),
                new ModerationRejectionStore.TaskRef(ContentType.HELP, 2L, 2)));
        when(moderationService.tryDispatchFromRetry(ContentType.IDLE, 1L, 1)).thenReturn(true);
        when(moderationService.tryDispatchFromRetry(ContentType.HELP, 2L, 2)).thenReturn(false);
        when(rejectionStore.size()).thenReturn(1L);

        scheduler.retryRejected();

        verify(moderationService).tryDispatchFromRetry(ContentType.IDLE, 1L, 1);
        verify(moderationService).tryDispatchFromRetry(ContentType.HELP, 2L, 2);
    }

    @Test
    @DisplayName("重投 - 单条抛异常不中断本轮，其余任务照常重投")
    void should_continue_when_oneTaskFails() {
        when(moderationExecutor.getThreadPoolExecutor()).thenReturn(poolWithCapacity());
        when(rejectionStore.pollDue(anyInt(), anyLong())).thenReturn(List.of(
                new ModerationRejectionStore.TaskRef(ContentType.IDLE, 1L, 1),
                new ModerationRejectionStore.TaskRef(ContentType.HELP, 2L, 1)));
        when(moderationService.tryDispatchFromRetry(ContentType.IDLE, 1L, 1))
                .thenThrow(new RuntimeException("数据库连接失败"));
        when(moderationService.tryDispatchFromRetry(ContentType.HELP, 2L, 1)).thenReturn(true);
        when(rejectionStore.size()).thenReturn(0L);

        assertThatCode(() -> scheduler.retryRejected()).doesNotThrowAnyException();

        verify(moderationService).tryDispatchFromRetry(ContentType.HELP, 2L, 1);
    }

    @Test
    @DisplayName("重投 - 无到期任务时静默返回，不查队列存量（避免空转刷日志）")
    void should_doNothing_when_noDueTask() {
        when(moderationExecutor.getThreadPoolExecutor()).thenReturn(poolWithCapacity());
        when(rejectionStore.pollDue(anyInt(), anyLong())).thenReturn(List.of());

        scheduler.retryRejected();

        verify(rejectionStore, never()).size();
        verify(moderationService, never()).tryDispatchFromRetry(anyString(), anyLong(), anyInt());
    }
}
