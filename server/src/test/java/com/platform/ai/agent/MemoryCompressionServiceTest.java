package com.platform.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.ai.common.PromptRepository;
import com.platform.common.AgentMessageRole;
import com.platform.common.MemorySegmentStatus;
import com.platform.model.entity.AgentConversation;
import com.platform.model.entity.AgentMemorySegment;
import com.platform.model.entity.AgentMessage;
import com.platform.repository.AgentConversationRepository;
import com.platform.repository.AgentMemorySegmentRepository;
import com.platform.repository.AgentMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryCompressionService 记忆压缩单元测试 — 覆盖压缩成功/失败降级/向量降级/补压/越权隔离。
 *
 * <p>压缩在 {@code documentImportExecutor} 异步执行；测试将其 doAnswer 为同步执行，便于断言段状态。
 * 铁律验证：LLM 失败/解析失败降级 RETRY 且不抛异常；向量化失败段向量留空不阻断插入。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryCompressionService 记忆压缩单元测试")
class MemoryCompressionServiceTest {

    @Mock
    private PromptRepository promptRepository;
    @Mock
    private OpenAiChatModel deepseekChatModel;
    @Mock
    private OpenAiEmbeddingModel zhipuEmbedding;
    @Mock
    private AgentMemorySegmentRepository memorySegmentRepository;
    @Mock
    private AgentMessageRepository messageRepository;
    @Mock
    private AgentConversationRepository conversationRepository;
    @Mock
    private ThreadPoolTaskExecutor documentImportExecutor;

    private MemoryCompressionService service;

    @BeforeEach
    void setUp() {
        service = new MemoryCompressionService(promptRepository, deepseekChatModel, zhipuEmbedding,
                memorySegmentRepository, messageRepository, conversationRepository, documentImportExecutor,
                new ObjectMapper());
        // 异步压缩任务改为同步执行，便于断言段状态与标题回填；补压/序号类用例不触达，故 lenient
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(documentImportExecutor).execute(any(Runnable.class));
        lenient().when(promptRepository.get(PromptRepository.KEY_MEMORY_COMPRESS)).thenReturn("压缩模板 {messages}");
    }

    /**
     * 构造 1024 维向量（前两位可指定，供断言 pgvector 字面量前缀；与压缩段 vector(1024) 一致）。
     *
     * @param first  首维值
     * @param second 次维值
     * @return 1024 维浮点向量
     */
    private float[] vector(float first, float second) {
        float[] v = new float[1024];
        v[0] = first;
        v[1] = second;
        return v;
    }

    private ChatResponse jsonResponse(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    @Test
    @DisplayName("压缩 - LLM 返回合法 JSON 时插入 SUCCESS 段并回填归档行标题")
    void should_compressSuccess_when_llmReturnsValidJson() {
        when(deepseekChatModel.call(any(Prompt.class)))
                .thenReturn(jsonResponse("{\"title\":\"搬家求助\",\"summary\":\"用户需要搬家\"}"));
        when(zhipuEmbedding.embed("用户需要搬家")).thenReturn(vector(0.1f, 0.2f));
        AgentConversation conv = AgentConversation.builder().id(100L).build();
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conv));

        service.compressWindow(1L, 10L, 9L, 100L, "用户：我需要搬家", 1);

        ArgumentCaptor<AgentMemorySegment> segCaptor = ArgumentCaptor.forClass(AgentMemorySegment.class);
        verify(memorySegmentRepository).save(segCaptor.capture());
        AgentMemorySegment seg = segCaptor.getValue();
        assertThat(seg.getStatus()).isEqualTo(MemorySegmentStatus.SUCCESS);
        assertThat(seg.getTitle()).isEqualTo("搬家求助");
        assertThat(seg.getSummary()).isEqualTo("用户需要搬家");
        assertThat(seg.getEmbedding()).startsWith("[0.1,0.2,");
        assertThat(seg.getUserId()).isEqualTo(1L);
        assertThat(seg.getTenantId()).isEqualTo(10L);
        assertThat(seg.getConversationId()).isEqualTo(9L);
        assertThat(seg.getArchiveRowId()).isEqualTo(100L);
        assertThat(seg.getSegmentNo()).isEqualTo(1);
        // 归档行标题异步回填
        verify(conversationRepository).save(conv);
        assertThat(conv.getTitle()).isEqualTo("搬家求助");
    }

    @Test
    @DisplayName("压缩 - LLM 调用失败降级 RETRY 段，标题用首条用户消息兜底，不抛异常")
    void should_degradeRetry_when_llmFails() {
        when(deepseekChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 超时"));

        assertThatCode(() -> service.compressWindow(1L, 10L, 9L, 100L, "用户：我需要搬家", 1))
                .doesNotThrowAnyException();

        ArgumentCaptor<AgentMemorySegment> segCaptor = ArgumentCaptor.forClass(AgentMemorySegment.class);
        verify(memorySegmentRepository).save(segCaptor.capture());
        AgentMemorySegment seg = segCaptor.getValue();
        assertThat(seg.getStatus()).isEqualTo(MemorySegmentStatus.RETRY);
        assertThat(seg.getTitle()).isEqualTo("我需要搬家");
        assertThat(seg.getSummary()).isNull();
        assertThat(seg.getEmbedding()).isNull();
    }

    @Test
    @DisplayName("压缩 - LLM 输出非 JSON 降级 RETRY，不抛异常")
    void should_degradeRetry_when_jsonInvalid() {
        when(deepseekChatModel.call(any(Prompt.class)))
                .thenReturn(jsonResponse("这不是合法 JSON"));

        assertThatCode(() -> service.compressWindow(1L, 10L, 9L, 100L, "用户：你好", 1))
                .doesNotThrowAnyException();

        ArgumentCaptor<AgentMemorySegment> segCaptor = ArgumentCaptor.forClass(AgentMemorySegment.class);
        verify(memorySegmentRepository).save(segCaptor.capture());
        assertThat(segCaptor.getValue().getStatus()).isEqualTo(MemorySegmentStatus.RETRY);
        assertThat(segCaptor.getValue().getTitle()).isEqualTo("你好");
    }

    @Test
    @DisplayName("压缩 - 向量化失败不阻断插入：SUCCESS 段向量留空可补压")
    void should_insertSegment_withoutEmbedding_when_embeddingFails() {
        when(deepseekChatModel.call(any(Prompt.class)))
                .thenReturn(jsonResponse("{\"title\":\"标题\",\"summary\":\"摘要\"}"));
        when(zhipuEmbedding.embed("摘要")).thenThrow(new RuntimeException("向量失败"));

        service.compressWindow(1L, 10L, 9L, 100L, "用户：你好", 1);

        ArgumentCaptor<AgentMemorySegment> segCaptor = ArgumentCaptor.forClass(AgentMemorySegment.class);
        verify(memorySegmentRepository).save(segCaptor.capture());
        AgentMemorySegment seg = segCaptor.getValue();
        assertThat(seg.getStatus()).isEqualTo(MemorySegmentStatus.SUCCESS);
        assertThat(seg.getSummary()).isEqualTo("摘要");
        assertThat(seg.getEmbedding()).isNull();
    }

    @Test
    @DisplayName("压缩 - 带向量写入失败降级为无向量重试插入，不抛异常")
    void should_degradeToNoVector_when_vectorWriteFails() {
        when(deepseekChatModel.call(any(Prompt.class)))
                .thenReturn(jsonResponse("{\"title\":\"标题\",\"summary\":\"摘要\"}"));
        when(zhipuEmbedding.embed("摘要")).thenReturn(vector(0.9f, 0f));
        // 第一次 save（带向量）抛异常 → 降级无向量再 save 一次
        when(memorySegmentRepository.save(any(AgentMemorySegment.class)))
                .thenThrow(new RuntimeException("pgvector 类型不匹配"))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.compressWindow(1L, 10L, 9L, 100L, "用户：你好", 1))
                .doesNotThrowAnyException();

        ArgumentCaptor<AgentMemorySegment> segCaptor = ArgumentCaptor.forClass(AgentMemorySegment.class);
        verify(memorySegmentRepository, times(2)).save(segCaptor.capture());
        assertThat(segCaptor.getAllValues().get(1).getEmbedding()).isNull();
        assertThat(segCaptor.getAllValues().get(1).getStatus()).isEqualTo(MemorySegmentStatus.SUCCESS);
    }

    @Test
    @DisplayName("补压 - RETRY 段补压成功更新为 SUCCESS 并回填标题")
    void should_compressRetry_when_repressSucceeds() {
        AgentMemorySegment retrySeg = AgentMemorySegment.builder()
                .id(1L).userId(1L).tenantId(10L).conversationId(9L).archiveRowId(100L)
                .segmentNo(1).title("兜底标题").status(MemorySegmentStatus.RETRY).build();
        when(memorySegmentRepository.findByUserIdAndStatus(1L, MemorySegmentStatus.RETRY))
                .thenReturn(List.of(retrySeg));
        when(messageRepository.findByConversationIdOrderByIdAsc(100L))
                .thenReturn(List.of(AgentMessage.builder().id(1L).conversationId(100L)
                        .role(AgentMessageRole.USER).content("用户消息").build()));
        when(deepseekChatModel.call(any(Prompt.class)))
                .thenReturn(jsonResponse("{\"title\":\"新标题\",\"summary\":\"新摘要\"}"));
        when(zhipuEmbedding.embed("新摘要")).thenReturn(vector(0.8f, 0f));
        AgentConversation conv = AgentConversation.builder().id(100L).build();
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conv));

        int count = service.compressRetry(1L);

        assertThat(count).isEqualTo(1);
        assertThat(retrySeg.getStatus()).isEqualTo(MemorySegmentStatus.SUCCESS);
        assertThat(retrySeg.getTitle()).isEqualTo("新标题");
        assertThat(retrySeg.getSummary()).isEqualTo("新摘要");
        assertThat(retrySeg.getEmbedding()).startsWith("[0.8,");
        verify(memorySegmentRepository).save(retrySeg);
        verify(conversationRepository).save(conv);
        assertThat(conv.getTitle()).isEqualTo("新标题");
    }

    @Test
    @DisplayName("补压 - 补压失败保持 RETRY，不循环、不计数")
    void should_keepRetry_when_repressFails() {
        AgentMemorySegment retrySeg = AgentMemorySegment.builder()
                .id(1L).userId(1L).tenantId(10L).conversationId(9L).archiveRowId(100L)
                .segmentNo(1).title("兜底标题").status(MemorySegmentStatus.RETRY).build();
        when(memorySegmentRepository.findByUserIdAndStatus(1L, MemorySegmentStatus.RETRY))
                .thenReturn(List.of(retrySeg));
        when(messageRepository.findByConversationIdOrderByIdAsc(100L)).thenReturn(List.of());
        when(deepseekChatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 超时"));

        int count = service.compressRetry(1L);

        assertThat(count).isZero();
        assertThat(retrySeg.getStatus()).isEqualTo(MemorySegmentStatus.RETRY);
        verify(memorySegmentRepository, never()).save(any(AgentMemorySegment.class));
    }

    @Test
    @DisplayName("越权 - 补压按 userId 隔离：他人 RETRY 段不被处理")
    void should_notRepress_otherUsersRetrySegments() {
        when(memorySegmentRepository.findByUserIdAndStatus(2L, MemorySegmentStatus.RETRY)).thenReturn(List.of());

        int count = service.compressRetry(2L);

        assertThat(count).isZero();
        verify(messageRepository, never()).findByConversationIdOrderByIdAsc(any());
    }

    @Test
    @DisplayName("越权 - 压缩序号计算按 userId+conversationId 隔离")
    void should_segmentNoOf_isolateByUser() {
        AgentMemorySegment seg = AgentMemorySegment.builder().id(1L).userId(1L).conversationId(9L).segmentNo(2).build();
        when(memorySegmentRepository.findByUserIdAndConversationId(1L, 9L)).thenReturn(List.of(seg));
        when(memorySegmentRepository.findByUserIdAndConversationId(2L, 9L)).thenReturn(List.of());

        assertThat(service.segmentNoOf(1L, 9L)).isEqualTo(2);
        assertThat(service.segmentNoOf(2L, 9L)).isZero();
    }
}
