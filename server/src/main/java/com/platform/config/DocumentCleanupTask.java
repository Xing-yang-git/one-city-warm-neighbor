package com.platform.config;

import com.platform.common.AppTimeZone;
import com.platform.common.DocumentStatus;
import com.platform.model.entity.KnowledgeDocument;
import com.platform.repository.KnowledgeDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文档定时巡检 — 卡死解析重置 + 过期失败文件清理。
 *
 * <p>后端重启/崩溃时卡在 parsing 的文档会被轮询永远挂起，需定期重置为 failed 可重试；
 * 失败/中断超过保留时长的源文件由每日任务清理，防止磁盘堆积。</p>
 */
@Slf4j
@Component
public class DocumentCleanupTask {

    private final KnowledgeDocumentRepository documentRepository;
    private final String knowledgeDir;
    /** 卡死 parsing 判定阈值（小时） */
    private static final long STALE_PARSING_HOURS = 2L;
    /** 失败文档源文件保留时长（ai.doc.cleanup-retention-hours） */
    private final long retentionHours;

    /**
     * 构造器注入。
     *
     * @param documentRepository 文档仓储
     * @param knowledgeDir       源文档目录（file.knowledge-dir）
     * @param retentionHours     失败文件保留时长
     */
    public DocumentCleanupTask(KnowledgeDocumentRepository documentRepository,
                               @Value("${file.knowledge-dir:./knowledge-docs}") String knowledgeDir,
                               @Value("${ai.doc.cleanup-retention-hours:24}") long retentionHours) {
        this.documentRepository = documentRepository;
        this.knowledgeDir = knowledgeDir;
        this.retentionHours = retentionHours;
    }

    /**
     * 每 30 分钟：把卡死 parsing（超 2 小时未更新）重置为 failed，避免轮询永远挂起。
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000L)
    public void resetStaleParsing() {
        List<KnowledgeDocument> stale = documentRepository.findByStatusAndUpdatedAtBefore(
                DocumentStatus.PARSING, LocalDateTime.now(AppTimeZone.APP_ZONE).minusHours(STALE_PARSING_HOURS));
        for (KnowledgeDocument doc : stale) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage("解析中断，请重试");
            documentRepository.save(doc);
            log.warn("重置卡死解析文档: docId={}, fileName={}", doc.getId(), doc.getFileName());
        }
        if (!stale.isEmpty()) {
            log.info("巡检重置卡死 parsing 文档 {} 条", stale.size());
        }
    }

    /**
     * 每日 03:30：清理失败超过保留时长的源文件与记录（文档切片应为空，若残留由外键 CASCADE 兜底）。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupStaleFiles() {
        List<KnowledgeDocument> stale = documentRepository.findByStatusAndUpdatedAtBefore(
                DocumentStatus.FAILED, LocalDateTime.now(AppTimeZone.APP_ZONE).minusHours(retentionHours));
        for (KnowledgeDocument doc : stale) {
            try {
                Files.deleteIfExists(Path.of(knowledgeDir, doc.getStoragePath()));
            } catch (IOException e) {
                log.warn("清理文档源文件失败: docId={}", doc.getId());
            }
            documentRepository.delete(doc);
            log.info("清理过期失败文档: docId={}, fileName={}", doc.getId(), doc.getFileName());
        }
        if (!stale.isEmpty()) {
            log.info("巡检清理过期失败文档 {} 条", stale.size());
        }
    }
}
