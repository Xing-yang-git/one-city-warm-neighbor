package com.platform.service;

import com.platform.common.KnowledgeStatus;
import com.platform.common.KnowledgeCategory;
import com.platform.model.entity.KnowledgeItem;
import com.platform.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeService 知识库业务单元测试 — 覆盖创建、向量生成与分类/状态校验。
 *
 * <p>list/get/update/setStatus/reindex 因管理端改文档驱动已移除，相应用例同步删除。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeService 知识库业务单元测试")
class KnowledgeServiceTest {

    @Mock
    private KnowledgeItemRepository knowledgeItemRepository;
    @Mock
    private OpenAiEmbeddingModel zhipuEmbedding;

    private KnowledgeService service;

    private float[] vector1024() {
        return new float[1024];
    }

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(knowledgeItemRepository, zhipuEmbedding);
    }

    private KnowledgeItem item(Long id, String category, String status) {
        return KnowledgeItem.builder()
                .id(id).tenantId(1L).category(category)
                .title("装修时间规定").content("工作日 8:00-12:00").status(status).build();
    }

    @Test
    @DisplayName("创建 - 合法分类生成向量并保存")
    void should_create_when_validCategory() {
        when(zhipuEmbedding.embed(anyString())).thenReturn(vector1024());
        when(knowledgeItemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItem created = service.create(item(null, KnowledgeCategory.RULES, KnowledgeStatus.ONLINE));

        assertThat(created.getEmbedding()).isNotNull().startsWith("[");
        assertThat(created.getEmbedding()).endsWith("]");
        verify(knowledgeItemRepository).save(created);
    }

    @Test
    @DisplayName("创建 - 未知分类抛异常且不落库")
    void should_throw_when_invalidCategory() {
        assertThatThrownBy(() -> service.create(item(null, "bogus", KnowledgeStatus.ONLINE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知分类");
        verify(knowledgeItemRepository, never()).save(any(KnowledgeItem.class));
        verify(zhipuEmbedding, never()).embed(anyString());
    }

    @Test
    @DisplayName("创建 - 未知状态抛异常")
    void should_throw_when_invalidStatus() {
        assertThatThrownBy(() -> service.create(item(null, KnowledgeCategory.RULES, "deleted")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知状态");
    }

    @Test
    @DisplayName("创建 - 向量生成失败不阻断保存")
    void should_create_when_embeddingFails() {
        when(zhipuEmbedding.embed(anyString())).thenThrow(new RuntimeException("API down"));
        when(knowledgeItemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItem created = service.create(item(null, KnowledgeCategory.RULES, KnowledgeStatus.ONLINE));

        assertThat(created.getEmbedding()).isNull();
        verify(knowledgeItemRepository).save(created);
    }

    @Test
    @DisplayName("创建 - 标题正文均为空时跳过向量生成")
    void should_create_skipEmbedding_when_textEmpty() {
        when(knowledgeItemRepository.save(any(KnowledgeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        KnowledgeItem blank = KnowledgeItem.builder()
                .tenantId(1L).category(KnowledgeCategory.RULES).title(null).content(" ").status(KnowledgeStatus.ONLINE).build();

        KnowledgeItem created = service.create(blank);

        assertThat(created.getEmbedding()).isNull();
        verify(zhipuEmbedding, never()).embed(anyString());
    }
}
