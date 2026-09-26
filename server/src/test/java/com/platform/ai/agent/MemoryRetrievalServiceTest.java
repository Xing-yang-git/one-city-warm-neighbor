package com.platform.ai.agent;

import com.platform.common.AppTimeZone;
import com.platform.model.entity.AgentMemorySegment;
import com.platform.repository.AgentMemorySegmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryRetrievalService 记忆检索单元测试 — 覆盖向量化/无命中/阈值过滤/降级与越权隔离。
 *
 * <p>方案 1（记忆按次实时检索）：无 LLM 整合步骤（摘要是压缩时产物，命中摘要按「1. 摘要\n2. 摘要」
 * 格式化后直接注入 {@code {历史记忆}}）。降级铁律验证：embedding 失败 / 检索异常（如 pgvector 维度
 * 不匹配）→ 返回 null 不阻塞对话；检索 SQL 强制按 userId 过滤（越权防护）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryRetrievalService 记忆检索单元测试")
class MemoryRetrievalServiceTest {

    @Mock
    private OpenAiEmbeddingModel zhipuEmbedding;
    @Mock
    private AgentMemorySegmentRepository memorySegmentRepository;

    private MemoryRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRetrievalService(zhipuEmbedding, memorySegmentRepository);
        ReflectionTestUtils.setField(service, "memoryRecallTop", 3);
        ReflectionTestUtils.setField(service, "memoryMatchThreshold", 0.55);
        // 默认存在压缩段（走完整检索链路）；「无段跳过」用例单独覆盖 0
        lenient().when(memorySegmentRepository.countByUserId(any())).thenReturn(1L);
    }

    /** 1024 维零向量（与压缩段 vector(1024) 一致，满足生产维度校验） */
    private float[] vector() {
        return new float[1024];
    }

    /**
     * 构造一条检索命中投影（压缩段 id + 余弦距离）。
     *
     * <p>{@code MemorySimilarityHit} 是 interface 投影（原生查询不支持 class-based 投影），
     * 无法直接 new，故测试内用最简匿名实现充当夹具。</p>
     *
     * @param id       压缩段 ID
     * @param distance 余弦距离
     * @return 命中投影实例
     */
    private static AgentMemorySegmentRepository.MemorySimilarityHit hit(long id, double distance) {
        return new AgentMemorySegmentRepository.MemorySimilarityHit() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public double getDistance() {
                return distance;
            }
        };
    }

    @Test
    @DisplayName("无段跳过 - 用户无任何压缩段时直接返回「无」，不触发向量化与检索")
    void should_skipEmbedding_when_noSegments() {
        when(memorySegmentRepository.countByUserId(1L)).thenReturn(0L);

        assertThat(service.retrieveMemory(1L, "你好")).isEqualTo("无");

        verify(zhipuEmbedding, never()).embed(anyString());
        verify(memorySegmentRepository, never()).findIdsBySimilarity(any(), any(), anyInt());
    }

    @Test
    @DisplayName("无段跳过 - 数量统计抛异常时降级走原链路（不阻塞、不抛到 chat）")
    void should_fallThrough_when_countThrows() {
        when(memorySegmentRepository.countByUserId(1L)).thenThrow(new RuntimeException("统计失败"));
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3))).thenReturn(List.of());

        assertThat(service.retrieveMemory(1L, "你好")).isEqualTo("无");

        // 走原链路：统计失败不影响后续向量化与检索
        verify(zhipuEmbedding).embed("你好");
        verify(memorySegmentRepository).findIdsBySimilarity(eq(1L), anyString(), eq(3));
    }

    @Test
    @DisplayName("检索 - 查询文本为空或空白时返回 null，不触发向量化与检索")
    void should_returnNull_when_queryBlank() {
        assertThat(service.retrieveMemory(1L, null)).isNull();
        assertThat(service.retrieveMemory(1L, "   ")).isNull();
        verify(memorySegmentRepository, never()).findIdsBySimilarity(any(), any(), anyInt());
    }

    @Test
    @DisplayName("检索 - 向量化失败降级返回 null（不阻塞对话）")
    void should_returnNull_when_embeddingFails() {
        when(zhipuEmbedding.embed("你好")).thenThrow(new RuntimeException("向量失败"));

        assertThat(service.retrieveMemory(1L, "你好")).isNull();

        verify(memorySegmentRepository, never()).findIdsBySimilarity(any(), any(), anyInt());
    }

    @Test
    @DisplayName("检索 - 无命中返回「无」")
    void should_returnNone_when_noHits() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3))).thenReturn(List.of());

        assertThat(service.retrieveMemory(1L, "你好")).isEqualTo("无");
    }

    @Test
    @DisplayName("检索 - 检索 SQL 强制按 userId 过滤（越权防护）")
    void should_query_byUserId() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3))).thenReturn(List.of());

        service.retrieveMemory(1L, "你好");

        verify(memorySegmentRepository).findIdsBySimilarity(eq(1L), anyString(), eq(3));
    }

    @Test
    @DisplayName("检索 - 命中按距离阈值过滤（>0.55 不注入），仅距离 ≤ 阈值的摘要格式化注入")
    void should_filterByThreshold_when_hitsReturned() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        // 距离 0.9 超过阈值 0.55 → 过滤；距离 0.3 命中（保持距离升序）
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3)))
                .thenReturn(List.of(hit(2L, 0.9), hit(1L, 0.3)));
        AgentMemorySegment hit = AgentMemorySegment.builder().id(1L).summary("用户喜欢园艺").build();
        when(memorySegmentRepository.findAllById(List.of(1L))).thenReturn(List.of(hit));

        String result = service.retrieveMemory(1L, "你好");

        // 只注入距离 ≤ 阈值的那条摘要（findAllById 只拉取过滤后的 id），带序号 + 时间 + 会话归属标签
        assertThat(result).isEqualTo("1. （时间未知 · 会话「未命名」）用户喜欢园艺");
        verify(memorySegmentRepository).findAllById(List.of(1L));
    }

    @Test
    @DisplayName("检索 - 命中多条时返回带序号的摘要列表文本「1. 摘要\\n2. 摘要」（无 LLM 整合）")
    void should_returnNumberedSegmentsText_when_hits() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        // 两条均 ≤ 阈值 0.55（按距离升序），hitIds=[2L,1L]，摘要按此顺序带序号格式化
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3)))
                .thenReturn(List.of(hit(2L, 0.2), hit(1L, 0.3)));
        when(memorySegmentRepository.findAllById(List.of(2L, 1L))).thenReturn(List.of(
                AgentMemorySegment.builder().id(2L).summary("常发起搬家求助").build(),
                AgentMemorySegment.builder().id(1L).summary("用户喜欢园艺").build()));

        String result = service.retrieveMemory(1L, "你好");

        assertThat(result).isEqualTo("1. （时间未知 · 会话「未命名」）常发起搬家求助\n2. （时间未知 · 会话「未命名」）用户喜欢园艺");
    }

    @Test
    @DisplayName("检索 - 命中段按时间从旧到新排序，带相对时间与所属会话标签（供模型判断先后/归属）")
    void should_sortByCreatedAt_withTimeLabels() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        // 检索距离升序返回 [1,2]，但 created_at 上旧的（3天前）应先注入
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3)))
                .thenReturn(List.of(hit(1L, 0.2), hit(2L, 0.3)));
        when(memorySegmentRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                AgentMemorySegment.builder().id(1L).summary("新记忆").title("周末手工")
                        .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE)).build(),
                AgentMemorySegment.builder().id(2L).summary("旧记忆").title("旧物处理")
                        .createdAt(LocalDateTime.now(AppTimeZone.APP_ZONE).minusDays(3)).build()));

        String result = service.retrieveMemory(1L, "你好");

        // 按时间从旧到新 + 相对时间 + 所属会话标题标签（旧记忆在前）
        assertThat(result).isEqualTo("1. （3天前 · 会话「旧物处理」）旧记忆\n2. （今天 · 会话「周末手工」）新记忆");
    }

    @Test
    @DisplayName("检索 - 命中段摘要为空/空白时跳过不占序号")
    void should_skipBlankSummary_when_buildText() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3)))
                .thenReturn(List.of(hit(1L, 0.3), hit(2L, 0.2)));
        when(memorySegmentRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                AgentMemorySegment.builder().id(1L).summary("   ").build(),
                AgentMemorySegment.builder().id(2L).summary("用户喜欢园艺").build()));

        String result = service.retrieveMemory(1L, "你好");

        assertThat(result).isEqualTo("1. （时间未知 · 会话「未命名」）用户喜欢园艺");
    }

    @Test
    @DisplayName("检索 - 向量检索抛异常（如 pgvector 维度不匹配）降级返回 null，不抛到 chat")
    void should_returnNull_when_findIdsBySimilarityThrows() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3)))
                .thenThrow(new RuntimeException("pgvector 维度不匹配"));

        assertThat(service.retrieveMemory(1L, "你好")).isNull();
    }

    @Test
    @DisplayName("检索 - 实体回填查询抛异常降级返回 null，不抛到 chat")
    void should_returnNull_when_findAllByIdThrows() {
        when(zhipuEmbedding.embed("你好")).thenReturn(vector());
        when(memorySegmentRepository.findIdsBySimilarity(eq(1L), anyString(), eq(3)))
                .thenReturn(List.of(hit(1L, 0.3)));
        when(memorySegmentRepository.findAllById(List.of(1L)))
                .thenThrow(new RuntimeException("pgvector 维度不匹配"));

        assertThat(service.retrieveMemory(1L, "你好")).isNull();
    }
}
