package com.platform.ai.moderation;

import com.platform.common.ContentType;
import com.platform.common.BizStatus;
import com.platform.common.ModerationStatus;
import com.platform.common.NotificationType;
import com.platform.model.entity.HelpRequest;
import com.platform.model.entity.IdleItem;
import com.platform.repository.HelpRequestRepository;
import com.platform.repository.IdleItemRepository;
import com.platform.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModerationService 内容审核单元测试 — 覆盖闲置/求助审核的三级分流（green/yellow/red）。
 *
 * <p>直接调用包内可见的 {@code moderateIdleItem}/{@code moderateHelpRequest}，
 * 绕过异步线程池以保持确定性（scheduleModeration 的异步提交不做断言）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationService 内容审核单元测试")
class ModerationServiceTest {

    @Mock
    private ModerationClient moderationClient;
    @Mock
    private IdleItemRepository idleItemRepository;
    @Mock
    private HelpRequestRepository helpRequestRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ThreadPoolTaskExecutor moderationExecutor;

    @InjectMocks
    private ModerationService moderationService;

    private IdleItem idleItem() {
        return IdleItem.builder()
                .id(1L).userId(10L).tenantId(1L).title("电钻").description("全新博世").status(BizStatus.PENDING_REVIEW).build();
    }

    private HelpRequest helpRequest() {
        return HelpRequest.builder()
                .id(11L).userId(20L).tenantId(1L).title("帮忙搬家具").description("周末搬家").status(BizStatus.PENDING_REVIEW).build();
    }

    @Test
    @DisplayName("闲置审核 - 全部 green 时自动上线并通知通过")
    void should_passIdle_when_green() {
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.GREEN, ""));
        IdleItem item = idleItem();
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        moderationService.moderateIdleItem(item);

        assertThat(item.getModerationStatus()).isEqualTo(ModerationStatus.GREEN);
        assertThat(item.getStatus()).isEqualTo(BizStatus.ONLINE);
        assertThat(item.getDelistReason()).isNull();
        verify(idleItemRepository).save(item);
        verify(notificationService).create(eq(10L), eq(NotificationType.CONTENT_APPROVED), eq("内容审核通过"), anyString(), eq(1L));
    }

    @Test
    @DisplayName("闲置审核 - red 时下架并通知驳回")
    void should_rejectIdle_when_red() {
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.RED, "疑似违禁品"));
        IdleItem item = idleItem();
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        moderationService.moderateIdleItem(item);

        assertThat(item.getStatus()).isEqualTo(BizStatus.OFFLINE);
        assertThat(item.getDelistReason()).isEqualTo("疑似违禁品");
        verify(notificationService).create(eq(10L), eq(NotificationType.CONTENT_REJECTED), eq("内容审核未通过"), anyString(), eq(1L));
    }

    @Test
    @DisplayName("闲置审核 - yellow 时保持待复核，不自动上线")
    void should_keepPending_when_yellow() {
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.YELLOW, "疑似广告导流"));
        IdleItem item = idleItem();
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        moderationService.moderateIdleItem(item);

        assertThat(item.getModerationStatus()).isEqualTo(ModerationStatus.YELLOW);
        assertThat(item.getStatus()).isEqualTo(BizStatus.PENDING_REVIEW);
        assertThat(item.getDelistReason()).isEqualTo("疑似广告导流");
        verify(notificationService, never()).create(anyLong(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("闲置审核 - 图片 red 优先于文本 green")
    void should_prioritizeRedImage_when_textGreen() {
        IdleItem item = idleItem();
        item.setImages("[\"/uploads/1.jpg\"]");
        when(moderationClient.moderateImage(anyString(), any()))
                .thenReturn(new ModerationResult(ModerationStatus.RED, "图片含违禁品"));
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.GREEN, ""));
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        moderationService.moderateIdleItem(item);

        assertThat(item.getStatus()).isEqualTo(BizStatus.OFFLINE);
        assertThat(item.getDelistReason()).isEqualTo("图片含违禁品");
    }

    @Test
    @DisplayName("闲置审核 - 首次全部失败重试仍失败时按 green 放行")
    void should_passIdle_when_allFailAndRetryFails() {
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenThrow(new RuntimeException("API down"));
        IdleItem item = idleItem();
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        moderationService.moderateIdleItem(item);

        assertThat(item.getModerationStatus()).isEqualTo(ModerationStatus.GREEN);
        assertThat(item.getStatus()).isEqualTo(BizStatus.ONLINE);
    }

    @Test
    @DisplayName("闲置审核 - 文本审核失败跳过，图片 green 仍放行")
    void should_skipTextFailure_when_imageGreen() {
        when(moderationClient.moderateImage(anyString(), any()))
                .thenReturn(new ModerationResult(ModerationStatus.GREEN, ""));
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenThrow(new RuntimeException("API down"));
        IdleItem item = idleItem();
        item.setImages("[\"/uploads/1.jpg\"]");
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        moderationService.moderateIdleItem(item);

        // 图片 green + 文本失败（重试也失败）→ 汇总 green
        assertThat(item.getModerationStatus()).isEqualTo(ModerationStatus.GREEN);
        assertThat(item.getStatus()).isEqualTo(BizStatus.ONLINE);
    }

    @Test
    @DisplayName("求助审核 - green 时自动上线并通知通过")
    void should_passHelp_when_green() {
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.GREEN, ""));
        HelpRequest hr = helpRequest();
        when(helpRequestRepository.findById(11L)).thenReturn(Optional.of(hr));

        moderationService.moderateHelpRequest(hr);

        assertThat(hr.getModerationStatus()).isEqualTo(ModerationStatus.GREEN);
        assertThat(hr.getStatus()).isEqualTo(BizStatus.ONLINE);
        verify(helpRequestRepository).save(hr);
        verify(notificationService).create(eq(20L), eq(NotificationType.CONTENT_APPROVED), eq("内容审核通过"), anyString(), eq(11L));
    }

    @Test
    @DisplayName("求助审核 - red 时下架并通知驳回")
    void should_rejectHelp_when_red() {
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.RED, "违规内容"));
        HelpRequest hr = helpRequest();
        when(helpRequestRepository.findById(11L)).thenReturn(Optional.of(hr));

        moderationService.moderateHelpRequest(hr);

        assertThat(hr.getStatus()).isEqualTo(BizStatus.OFFLINE);
        assertThat(hr.getDelistReason()).isEqualTo("违规内容");
        verify(notificationService).create(eq(20L), eq(NotificationType.CONTENT_REJECTED), eq("内容审核未通过"), anyString(), eq(11L));
    }

    @Test
    @DisplayName("求助审核 - 图片审核失败被跳过，文本 yellow 触发待复核")
    void should_pendHelp_when_textYellowAndImageFails() {
        HelpRequest hr = helpRequest();
        hr.setImages("[\"/uploads/1.jpg\"]");
        when(moderationClient.moderateImage(anyString(), any()))
                .thenThrow(new RuntimeException("图片读取失败"));
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.YELLOW, "疑似商业信息"));
        when(helpRequestRepository.findById(11L)).thenReturn(Optional.of(hr));

        moderationService.moderateHelpRequest(hr);

        assertThat(hr.getModerationStatus()).isEqualTo(ModerationStatus.YELLOW);
        assertThat(hr.getStatus()).isEqualTo(BizStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("闲置审核 - 实体不存在时仅记日志不抛异常")
    void should_notThrow_when_idleItemMissing() {
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.GREEN, ""));
        when(idleItemRepository.findById(1L)).thenReturn(Optional.empty());

        moderationService.moderateIdleItem(idleItem());

        verify(notificationService, never()).create(anyLong(), any(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("审核 - 多原因拼接以分号分隔")
    void should_concatenateReasons_when_multiple() {
        IdleItem item = idleItem();
        item.setImages("[\"/uploads/1.jpg\",\"/uploads/2.jpg\"]");
        when(moderationClient.moderateImage(any(), any())).thenReturn(
                new ModerationResult(ModerationStatus.YELLOW, "图片一有水印"),
                new ModerationResult(ModerationStatus.YELLOW, "图片二疑似商业库存"));
        when(moderationClient.moderateText(anyString(), anyString()))
                .thenReturn(new ModerationResult(ModerationStatus.GREEN, ""));
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        moderationService.moderateIdleItem(item);

        assertThat(item.getDelistReason()).isEqualTo("图片一有水印；图片二疑似商业库存");
    }

    // ==================== 重投（拒绝补偿链路） ====================

    @Test
    @DisplayName("重投 - 内容仍待审核时重新提交到池子")
    void should_dispatchAgain_when_stillPending() {
        IdleItem item = idleItem();
        item.setModerationStatus(ModerationStatus.PENDING);
        when(idleItemRepository.findById(1L)).thenReturn(Optional.of(item));

        boolean dispatched = moderationService.tryDispatchFromRetry(ContentType.IDLE, 1L, 1);

        assertThat(dispatched).isTrue();
        verify(moderationExecutor).execute(any(ModerationTask.class));
    }

    @Test
    @DisplayName("重投 - 求助信息仍待审核时同样重新提交（与闲置对称）")
    void should_dispatchHelpAgain_when_stillPending() {
        HelpRequest hr = helpRequest();
        hr.setModerationStatus(ModerationStatus.PENDING);
        when(helpRequestRepository.findById(11L)).thenReturn(Optional.of(hr));

        boolean dispatched = moderationService.tryDispatchFromRetry(ContentType.HELP, 11L, 1);

        assertThat(dispatched).isTrue();
        verify(moderationExecutor).execute(any(ModerationTask.class));
    }

    @Test
    @DisplayName("重投 - 内容实体已不存在时跳过且不提交")
    void should_skip_when_entityMissing() {
        when(idleItemRepository.findById(anyLong())).thenReturn(Optional.empty());

        boolean dispatched = moderationService.tryDispatchFromRetry(ContentType.IDLE, 99L, 1);

        assertThat(dispatched).isFalse();
        verify(moderationExecutor, never()).execute(any(ModerationTask.class));
    }

    @Test
    @DisplayName("重投 - 内容已被处理（非待审核）时跳过，避免重复发通知")
    void should_skip_when_alreadyProcessed() {
        // 已驳回：status 变为 offline，moderationStatus 变为 red
        HelpRequest processed = helpRequest();
        processed.setStatus(BizStatus.OFFLINE);
        processed.setModerationStatus(ModerationStatus.RED);
        when(helpRequestRepository.findById(11L)).thenReturn(Optional.of(processed));

        boolean dispatched = moderationService.tryDispatchFromRetry(ContentType.HELP, 11L, 1);

        assertThat(dispatched).isFalse();
        verify(moderationExecutor, never()).execute(any(ModerationTask.class));
    }

    @Test
    @DisplayName("重投 - 未知内容类型时跳过且不提交")
    void should_skip_when_unknownType() {
        boolean dispatched = moderationService.tryDispatchFromRetry("unknown", 1L, 1);

        assertThat(dispatched).isFalse();
        verify(moderationExecutor, never()).execute(any(ModerationTask.class));
    }
}
