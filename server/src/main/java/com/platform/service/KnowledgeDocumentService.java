package com.platform.service;

import com.platform.ai.document.FileTypeDetector;
import com.platform.common.BizException;
import com.platform.common.DocumentStatus;
import com.platform.common.KnowledgeCategory;
import com.platform.common.KnowledgeFileType;
import com.platform.model.dto.KnowledgeDocumentDTO;
import com.platform.model.entity.KnowledgeDocument;
import com.platform.repository.KnowledgeDocumentRepository;
import com.platform.repository.KnowledgeItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库源文档管理 — 上传（魔数校验/大小限制/重复拦截）、列表、删除（清库全量）、重试。
 *
 * <p>源文件落盘 {@code file.knowledge-dir}（非 /uploads 公开静态区）；上传端点受
 * {@code /api/admin/**} 的 ROLE_ADMIN 保护。</p>
 */
@Slf4j
@Service
public class KnowledgeDocumentService {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeImportService importService;
    private final ThreadPoolTaskExecutor documentImportExecutor;
    private final String knowledgeDir;
    /** 删除切片/文档的短事务模板（upload 替换路径非事务调用方，需显式事务执行 @Modifying DELETE） */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造器注入。
     *
     * @param documentRepository     文档仓储
     * @param itemRepository         切片仓储
     * @param importService          导入编排服务
     * @param documentImportExecutor 文档导入线程池
     * @param knowledgeDir           源文档目录
     * @param transactionManager     事务管理器（删除切片短事务）
     */
    public KnowledgeDocumentService(KnowledgeDocumentRepository documentRepository,
                                    KnowledgeItemRepository itemRepository,
                                    KnowledgeImportService importService,
                                    @Qualifier("documentImportExecutor") ThreadPoolTaskExecutor documentImportExecutor,
                                    @Value("${file.knowledge-dir:./knowledge-docs}") String knowledgeDir,
                                    PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.itemRepository = itemRepository;
        this.importService = importService;
        this.documentImportExecutor = documentImportExecutor;
        this.knowledgeDir = knowledgeDir;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 上传文档：校验 → 落盘 → 建记录(parsing) → 异步解析。
     *
     * @param file     上传文件
     * @param category 分类（rules/service/help/guide）
     * @param tags     逗号分隔标签（可空）
     * @param source   展示来源名（可空，默认去扩展名文件名）
     * @param replace  同名文档是否替换（替换先删旧文档及其切片）
     * @param tenantId 目标小区 ID
     * @param adminId  上传管理员 ID
     * @return 新建文档 DTO
     */
    public KnowledgeDocumentDTO upload(MultipartFile file, String category, String tags, String source,
                                       boolean replace, Long tenantId, Long adminId) {
        // 平台帮助（help）为系统内置内容（DataInitializer 播种，Agent 平台功能问题以内置为准），
        // 禁止文档上传覆盖/补充——避免上传文档与内置说明冲突影响回答正确性
        if (KnowledgeCategory.HELP.equals(category)) {
            throw new IllegalArgumentException("平台帮助已系统内置，无需上传，请选择物业资料分类（规章制度/服务手册/办事指南）");
        }
        // 分类白名单校验（与 KnowledgeService.create 的 validateCategoryAndStatus 一致，防任意字符串破坏前端分类过滤与检索语义）
        boolean validCategory = KnowledgeCategory.RULES.equals(category)
                || KnowledgeCategory.SERVICE.equals(category)
                || KnowledgeCategory.GUIDE.equals(category);
        if (!validCategory) {
            throw new IllegalArgumentException("未知分类: " + category);
        }
        String fileName = file.getOriginalFilename();
        String fileType = KnowledgeFileType.fromFileName(fileName);
        if (fileType == null) {
            throw new IllegalArgumentException("不支持的文件类型，仅支持 md/txt/pdf/docx/xlsx/csv");
        }
        // 魔数校验真实类型（防伪装扩展名）
        byte[] head = new byte[64];
        try {
            int len = file.getInputStream().read(head);
            byte[] headBytes = len > 0 ? java.util.Arrays.copyOf(head, len) : head;
            FileTypeDetector.resolveAndValidate(fileName, headBytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败");
        }
        // 差异化大小限制
        long maxBytes = KnowledgeFileType.MAX_SIZE_BYTES.get(fileType);
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("文件超过该类型大小上限（" + (maxBytes / (1024 * 1024)) + "MB）");
        }
        // 同名替换 / 重复拦截
        if (replace) {
            documentRepository.findByTenantIdAndFileName(tenantId, fileName)
                    .ifPresent(old -> deleteDocumentInternal(old));
        } else {
            if (documentRepository.findByTenantIdAndFileName(tenantId, fileName).isPresent()) {
                throw new IllegalArgumentException("同名文档已存在，请确认是否替换");
            }
        }
        // MD5 重复拦截（非替换场景；替换时旧文件将删除）
        String md5;
        try {
            md5 = DigestUtils.md5DigestAsHex(file.getInputStream().readAllBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("计算文件 MD5 失败");
        }
        if (!replace && documentRepository.findByTenantIdAndFileMd5(tenantId, md5).isPresent()) {
            throw new IllegalArgumentException("该文件已上传过，请勿重复上传");
        }
        // 落盘：knowledge-dir/<tenantId>/<uuid>.<ext>
        String storagePath = tenantId + "/" + UUID.randomUUID() + "." + fileType;
        Path target = Path.of(knowledgeDir, storagePath);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new IllegalArgumentException("文件保存失败，请重试");
        }
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .tenantId(tenantId)
                .category(category)
                .fileName(fileName)
                .fileType(fileType)
                .fileMd5(md5)
                .source(source != null && !source.isBlank() ? source : stripExt(fileName))
                .tags(tags)
                .storagePath(storagePath)
                .status(DocumentStatus.PARSING)
                .createdBy(adminId)
                .build();
        doc = documentRepository.save(doc);
        // 异步解析，不阻塞上传响应
        Long docId = doc.getId();
        documentImportExecutor.submit(() -> importService.processDocument(docId));
        log.info("文档上传受理: docId={}, fileName={}, tenantId={}", docId, fileName, tenantId);
        return toDTO(doc);
    }

    /**
     * 文档列表（tenantId/status 可空过滤，一次性返回全部）。
     *
     * @param tenantId 小区 ID（super_admin 为 null 查全部）
     * @param status   状态过滤（可空）
     * @return 全部文档 DTO 列表
     */
    public List<KnowledgeDocumentDTO> list(Long tenantId, String status) {
        return documentRepository.searchAll(tenantId, status).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 删除文档（清库全量：切片 + 文档行 + 落盘文件）。
     *
     * @param docId    文档 ID
     * @param tenantId 当前管理员小区（super_admin 为 null 可删任意）
     */
    @Transactional
    public void delete(Long docId, Long tenantId) {
        KnowledgeDocument doc = requireOwned(docId, tenantId);
        deleteDocumentInternal(doc);
    }

    /**
     * 重试解析失败文档（幂等）。
     *
     * @param docId    文档 ID
     * @param tenantId 当前管理员小区
     */
    @Transactional
    public void retry(Long docId, Long tenantId) {
        KnowledgeDocument doc = requireOwned(docId, tenantId);
        doc.setStatus(DocumentStatus.PARSING);
        doc.setErrorMessage(null);
        doc.setChunkCount(0);
        documentRepository.save(doc);
        Long id = doc.getId();
        documentImportExecutor.submit(() -> importService.processDocument(id));
        log.info("文档重试受理: docId={}", docId);
    }

    /** 删除内部实现：短事务内删切片 + 文档行（DB CASCADE 兜底），事务外删落盘文件 */
    private void deleteDocumentInternal(KnowledgeDocument doc) {
        transactionTemplate.execute(status -> {
            itemRepository.deleteByDocId(doc.getId());        // 代码层删切片（双保险）
            documentRepository.delete(doc);                    // 删文档行（外键 CASCADE 兜底）
            return null;
        });
        try {
            Files.deleteIfExists(Path.of(knowledgeDir, doc.getStoragePath()));
        } catch (IOException e) {
            log.warn("删除文档源文件失败: docId={}, path={}", doc.getId(), doc.getStoragePath());
        }
        log.info("文档已删除: docId={}, fileName={}", doc.getId(), doc.getFileName());
    }

    /** 校验文档归属（super_admin tenantId=null 时不限小区） */
    private KnowledgeDocument requireOwned(Long docId, Long tenantId) {
        KnowledgeDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
        if (tenantId != null && !tenantId.equals(doc.getTenantId())) {
            throw new BizException("无权操作其他小区的文档");
        }
        return doc;
    }

    /** 文件名去扩展名 */
    private String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 实体 → DTO（只映射前端实际使用的字段） */
    private KnowledgeDocumentDTO toDTO(KnowledgeDocument doc) {
        return KnowledgeDocumentDTO.builder()
                .id(doc.getId())
                .category(doc.getCategory())
                .fileName(doc.getFileName())
                .fileType(doc.getFileType())
                .source(doc.getSource())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
