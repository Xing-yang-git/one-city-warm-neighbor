package com.platform.service;

import com.platform.ai.common.AiApiInvoker;
import com.platform.ai.document.Chunk;
import com.platform.ai.document.DocumentChunker;
import com.platform.ai.document.DocumentParser;
import com.platform.ai.document.DocumentParserRegistry;
import com.platform.ai.document.DocumentProcessGuard;
import com.platform.ai.document.ParsedDocument;
import com.platform.ai.document.TextTitleDeriver;
import com.platform.common.KnowledgeStatus;
import com.platform.common.DocumentStatus;
import com.platform.model.entity.KnowledgeDocument;
import com.platform.model.entity.KnowledgeItem;
import com.platform.repository.KnowledgeDocumentRepository;
import com.platform.repository.KnowledgeItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 知识文档导入编排 — B端上传后异步执行：解析 → 切片 → 批量嵌入 → 入库。
 *
 * <p>远程调用（embedding）绝不包在长事务内；仅持久化段开短事务。重试/替换前按 docId
 * 幂等删除旧切片。解析失败（加密/损坏/空）标记文档 failed，可重试。</p>
 */
@Slf4j
@Service
public class KnowledgeImportService {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeItemRepository itemRepository;
    private final DocumentParserRegistry parserRegistry;
    private final DocumentChunker chunker;
    private final OpenAiEmbeddingModel zhipuEmbedding;
    private final AiApiInvoker aiApiInvoker;
    private final DocumentProcessGuard processGuard;
    private final String knowledgeDir;
    private final int embeddingBatchSize;
    private final int embeddingDelayMs;
    /** 持久化短事务模板（persistChunks 被同类自调用，@Transactional 代理不生效，需显式事务） */
    private final TransactionTemplate transactionTemplate;
    /** 兜底标题长度上限（字符） */
    private static final int TITLE_MAX_LENGTH = 200;

    /**
     * 构造器注入。
     *
     * @param documentRepository     文档仓储
     * @param itemRepository         切片仓储
     * @param parserRegistry         解析器注册表
     * @param chunker                切片器
     * @param zhipuEmbedding         智谱 embedding 模型（1024 维）
     * @param aiApiInvoker           外部 API 调用封装（重试/熔断/缓存）
     * @param processGuard           进程内 in-flight 锁
     * @param knowledgeDir           源文档目录（file.knowledge-dir）
     * @param embeddingBatchSize     嵌入批量条数
     * @param embeddingDelayMs       批量间节流延迟
     * @param transactionManager     事务管理器（persistChunks 短事务）
     */
    public KnowledgeImportService(KnowledgeDocumentRepository documentRepository,
                                  KnowledgeItemRepository itemRepository,
                                  DocumentParserRegistry parserRegistry,
                                  DocumentChunker chunker,
                                  OpenAiEmbeddingModel zhipuEmbedding,
                                  AiApiInvoker aiApiInvoker,
                                  DocumentProcessGuard processGuard,
                                  @Value("${file.knowledge-dir:./knowledge-docs}") String knowledgeDir,
                                  @Value("${ai.doc.embedding-batch-size:16}") int embeddingBatchSize,
                                  @Value("${ai.doc.embedding-delay-ms:200}") int embeddingDelayMs,
                                  PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.itemRepository = itemRepository;
        this.parserRegistry = parserRegistry;
        this.chunker = chunker;
        this.zhipuEmbedding = zhipuEmbedding;
        this.aiApiInvoker = aiApiInvoker;
        this.processGuard = processGuard;
        this.knowledgeDir = knowledgeDir;
        this.embeddingBatchSize = embeddingBatchSize;
        this.embeddingDelayMs = embeddingDelayMs;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 处理文档（异步入口，带 in-flight 防重）。
     *
     * @param docId 文档 ID
     */
    public void processDocument(Long docId) {
        if (!processGuard.tryAcquire(docId)) {
            log.info("文档 {} 已在处理中，跳过重复触发", docId);
            return;
        }
        try {
            KnowledgeDocument doc = documentRepository.findById(docId).orElse(null);
            if (doc == null) {
                return;
            }
            doProcess(doc);
        } catch (Exception e) {
            log.error("文档处理异常: docId={}", docId, e);
        } finally {
            processGuard.release(docId);
        }
    }

    /** 核心处理：解析 → 切片 → 嵌入 → 持久化 */
    private void doProcess(KnowledgeDocument doc) {
        try {
            Path filePath = Path.of(knowledgeDir, doc.getStoragePath());
            DocumentParser parser = parserRegistry.get(doc.getFileType());
            ParsedDocument parsed = parser.parse(filePath);
            String fallbackSection = doc.getSource() != null ? doc.getSource() : stripExt(doc.getFileName());
            List<Chunk> chunks = chunker.chunk(parsed, fallbackSection);
            if (chunks.isEmpty()) {
                markFailed(doc, "未提取到可索引文字");
                return;
            }
            List<String> embeddings = embedChunks(chunks);
            int persisted = persistChunks(doc.getId(), chunks, embeddings);
            int embeddedCount = (int) embeddings.stream().filter(Objects::nonNull).count();
            markReady(doc, persisted, buildWarning(parsed, embeddedCount, embeddings.size()));
        } catch (IllegalArgumentException e) {
            // 加密文档 / 未知类型等友好失败
            log.warn("文档解析失败: docId={}, reason={}", doc.getId(), e.getMessage());
            markFailed(doc, e.getMessage());
        } catch (Exception e) {
            log.error("文档解析异常: docId={}", doc.getId(), e);
            markFailed(doc, "解析失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** 批量生成切片向量（按批调用，单批失败留空待 reindex；含节流延迟） */
    private List<String> embedChunks(List<Chunk> chunks) {
        List<String> embeddings = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        List<String> texts = chunks.stream()
                .map(c -> (c.sectionTitle() == null ? "" : c.sectionTitle()) + "\n" + c.content())
                .toList();
        for (int start = 0; start < texts.size(); start += embeddingBatchSize) {
            int end = Math.min(texts.size(), start + embeddingBatchSize);
            List<String> batch = texts.subList(start, end);
            try {
                List<float[]> vectors = aiApiInvoker.invoke("embedding", () -> zhipuEmbedding.embed(batch));
                for (int j = 0; j < vectors.size() && start + j < texts.size(); j++) {
                    float[] vec = vectors.get(j);
                    if (vec != null && vec.length == 1024) {
                        embeddings.set(start + j, KnowledgeService.floatArrayToPgvectorString(vec));
                    }
                }
            } catch (Exception e) {
                log.warn("embedding 批次失败留空待 reindex: 批次起点={}", start, e);
            }
            sleepQuietly(embeddingDelayMs);
        }
        return embeddings;
    }

    /** 持久化切片（短事务）：先删旧切片保证幂等，再批量入库 */
    public int persistChunks(Long docId, List<Chunk> chunks, List<String> embeddings) {
        // 构造器注入的 final 字段理论非空；SonarLint 无法跨构造器推断为可空，本地判空兜底避免 NPE
        TransactionTemplate tt = transactionTemplate;
        if (tt == null) {
            log.error("事务模板未初始化，无法写入切片 docId={}", docId);
            return 0;
        }
        return tt.execute(status -> {
            // 文档已被删除或替换 → 丢弃本次结果（防止在途任务回写已删文档）
            Optional<KnowledgeDocument> fresh = documentRepository.findById(docId);
            if (fresh.isEmpty() || !DocumentStatus.PARSING.equals(fresh.get().getStatus())) {
                log.info("文档 {} 已删除或非解析态，丢弃切片结果", docId);
                return 0;
            }
            KnowledgeDocument doc = fresh.get();
            itemRepository.deleteByDocId(docId);   // 幂等清理旧切片
            List<KnowledgeItem> items = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                KnowledgeItem item = KnowledgeItem.builder()
                        .tenantId(doc.getTenantId())
                        .category(doc.getCategory())
                        .title(c.sectionTitle() != null ? c.sectionTitle() : derivedTitle(c.content()))
                        .content(c.content())
                        .source(doc.getSource() != null ? doc.getSource() : stripExt(doc.getFileName()))
                        .tags(doc.getTags())
                        .embedding(embeddings.get(i))
                        .status(KnowledgeStatus.ONLINE)
                        .docId(docId)
                        .chunkIndex(i)
                        .pageNo(c.pageNo())
                        .sectionTitle(c.sectionTitle())
                        .createdBy(doc.getCreatedBy())
                        .build();
                items.add(item);
            }
            itemRepository.saveAll(items);
            log.info("文档 {} 切片入库完成: {} 条", docId, items.size());
            return items.size();
        });
    }

    /** 组装文档警告（解析警告 + embedding 失败数） */
    private String buildWarning(ParsedDocument parsed, int embeddedCount, int total) {
        StringBuilder warn = new StringBuilder();
        if (parsed.warnings() != null && !parsed.warnings().isEmpty()) {
            warn.append("部分内容未处理: ").append(String.join("; ", parsed.warnings()));
        }
        int failedEmbedding = total - embeddedCount;
        if (failedEmbedding > 0) {
            if (!warn.isEmpty()) {
                warn.append("；");
            }
            warn.append("embedding 失败 ").append(failedEmbedding).append(" 条，可在管理端 reindex 补齐");
        }
        return !warn.isEmpty() ? warn.toString() : null;
    }

    /** 标记文档就绪 */
    private void markReady(KnowledgeDocument doc, int chunkCount, String warning) {
        doc.setStatus(DocumentStatus.READY);
        doc.setChunkCount(chunkCount);
        doc.setErrorMessage(warning);
        documentRepository.save(doc);
        log.info("文档解析完成: docId={}, chunks={}, warning={}", doc.getId(), chunkCount, warning);
    }

    /** 标记文档失败（可重试） */
    private void markFailed(KnowledgeDocument doc, String message) {
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorMessage(message);
        documentRepository.save(doc);
        log.warn("文档解析失败: docId={}, reason={}", doc.getId(), message);
    }

    /** 文件名去扩展名 */
    static String stripExt(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 截断到上限（切片标题兜底用） */
    private String truncate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) : text;
    }

    /** 无章节标题时的兜底标题：按整句切取正文开头（≤ 200 字），切不出时退回盲截 */
    private String derivedTitle(String content) {
        String derived = TextTitleDeriver.derive(content, TITLE_MAX_LENGTH);
        return derived != null ? derived : truncate(content, TITLE_MAX_LENGTH);
    }

    /** 批量间节流休眠（中断即恢复） */
    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
