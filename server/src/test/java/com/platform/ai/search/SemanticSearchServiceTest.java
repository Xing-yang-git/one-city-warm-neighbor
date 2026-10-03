package com.platform.ai.search;

import com.platform.ai.embedding.EmbeddingService;
import com.platform.common.PostStatus;
import com.platform.common.PostType;
import com.platform.config.AiConfig;
import com.platform.model.entity.IdleItem;
import com.platform.repository.IdleItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SemanticSearchService 语义搜索单元测试 — 覆盖 pgvector 语义搜索与混合搜索合并去重。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticSearchService 语义搜索单元测试")
class SemanticSearchServiceTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private IdleItemRepository idleItemRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private AiConfig aiConfig;
    @Mock
    private Query query;

    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        service = new SemanticSearchService(embeddingService, idleItemRepository, entityManager, aiConfig);
        lenient().when(aiConfig.getSimilarityThreshold()).thenReturn(0.5);
    }

    private IdleItem item(long id, String title) {
        return IdleItem.builder().id(id).title(title).build();
    }

    @Test
    @DisplayName("语义搜索 - 向量生成失败时返回空列表")
    void should_returnEmpty_when_embeddingNull() {
        when(embeddingService.generateEmbedding("电钻", "")).thenReturn(null);

        List<IdleItem> results = service.semanticSearchIdle("电钻", 10L, PostType.LEND, 5);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("语义搜索 - 向量命中时返回相似物品")
    void should_returnSimilarItems_when_embeddingValid() {
        when(embeddingService.generateEmbedding("电钻", "")).thenReturn("[0.1,0.2]");
        when(entityManager.createNativeQuery(anyString(), eq(IdleItem.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(item(1L, "博世冲击钻"), item(2L, "电动螺丝刀")));

        List<IdleItem> results = service.semanticSearchIdle("电钻", 10L, PostType.LEND, 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("混合搜索 - 语义结果与关键词结果合并去重")
    void should_hybridSearch_mergeAndDedup() {
        when(embeddingService.generateEmbedding("电钻", "")).thenReturn("[0.1,0.2]");
        when(entityManager.createNativeQuery(anyString(), eq(IdleItem.class))).thenReturn(query);
        // 语义结果 2 条
        when(query.getResultList()).thenReturn(List.of(item(1L, "博世冲击钻"), item(2L, "电动螺丝刀")));
        // 关键词结果与语义重叠 1 条 + 新增 1 条
        when(idleItemRepository.searchByTenant(eq(PostStatus.ONLINE), eq(PostType.LEND), eq(10L),
                anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(item(2L, "电动螺丝刀"), item(3L, "手电钻"))));

        Page<IdleItem> result = service.hybridSearch("电钻", 10L, PostType.LEND, PageRequest.of(0, 10));

        // 去重后 3 条
        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("混合搜索 - 无语义结果时仅返回关键词结果")
    void should_hybridSearch_fallbackToKeyword_when_noSemantic() {
        when(embeddingService.generateEmbedding("梯子", "")).thenReturn("[0.1]");
        when(entityManager.createNativeQuery(anyString(), eq(IdleItem.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        when(idleItemRepository.searchByTenant(eq(PostStatus.ONLINE), eq(PostType.LEND), eq(10L),
                anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(item(1L, "铝合金梯子"), item(2L, "折叠梯"))));

        Page<IdleItem> result = service.hybridSearch("梯子", 10L, PostType.LEND, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("混合搜索 - 分页截取超过页大小的内容")
    void should_hybridSearch_paginate() {
        when(embeddingService.generateEmbedding("伞", "")).thenReturn("[0.1]");
        when(entityManager.createNativeQuery(anyString(), eq(IdleItem.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(item(1L, "黑伞"), item(2L, "折叠伞")));
        when(idleItemRepository.searchByTenant(eq(PostStatus.ONLINE), eq(PostType.LEND), eq(10L),
                anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(item(3L, "雨伞"))));

        // 页大小 1，从第 1 条开始
        Page<IdleItem> result = service.hybridSearch("伞", 10L, PostType.LEND, PageRequest.of(1, 1));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(2L);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }
}
