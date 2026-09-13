package com.platform.controller;

import com.platform.common.BizException;
import com.platform.common.KnowledgeFileType;
import com.platform.common.Result;
import com.platform.common.UserType;
import com.platform.model.dto.IdResponseDTO;
import com.platform.model.dto.KnowledgeDocumentDTO;
import com.platform.model.dto.ListDTO;
import com.platform.model.entity.User;
import com.platform.repository.UserRepository;
import com.platform.security.LoginUser;
import com.platform.service.KnowledgeDocumentService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 知识库源文档管理 REST API（B端管理员）— 上传/列表/删除/重试。
 *
 * <p>权限由 {@code /api/admin/**} 的 ROLE_ADMIN/ROLE_SUPER_ADMIN 校验；
 * super_admin 为平台级视角（tenantId 为 null），上传须显式指定目标小区。</p>
 */
@RestController
@RequestMapping("/api/admin/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;
    private final UserRepository userRepository;
    /** 暂时禁用的上传类型（TXT/Excel/CSV，解析器暂不启用） */
    private static final Set<String> DISABLED_UPLOAD_TYPES = Set.of(
            KnowledgeFileType.TXT, KnowledgeFileType.XLSX, KnowledgeFileType.CSV);

    /**
     * 构造器注入。
     *
     * @param documentService 文档管理服务
     * @param userRepository  用户仓储（解析管理员小区）
     */
    public KnowledgeDocumentController(KnowledgeDocumentService documentService,
                                       UserRepository userRepository) {
        this.documentService = documentService;
        this.userRepository = userRepository;
    }

    /**
     * 上传知识文档（multipart），异步解析切片入库。
     *
     * @param file     上传文件（md/txt/pdf/docx/xlsx/csv）
     * @param category 分类（rules/service/help/guide）
     * @param tags     逗号分隔标签（可空）
     * @param source   展示来源名（可空，默认去扩展名文件名）
     * @param replace  同名文档是否替换（替换先删旧文档及其切片）
     * @param tenantId super_admin 上传时必须指定的目标小区（普通 admin 忽略）
     * @param auth     当前认证管理员
     * @return 新建文档摘要
     */
    @PostMapping("/import")
    public Result<KnowledgeDocumentDTO> importDocument(@RequestParam("file") MultipartFile file,
                                    @RequestParam String category,
                                    @RequestParam(required = false) String tags,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(defaultValue = "false") boolean replace,
                                    @RequestParam(required = false) Long tenantId,
                                    Authentication auth) {
        // 暂时禁用的类型（TXT/Excel/CSV）在上传入口直接拦截
        String fileType = KnowledgeFileType.fromFileName(file.getOriginalFilename());
        if (DISABLED_UPLOAD_TYPES.contains(fileType)) {
            return Result.error(400, "该文件类型暂不支持，请上传 md / pdf / docx 文件");
        }
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        Long resolvedTenant = resolveTenantId(adminId);
        if (resolvedTenant == null) {
            // super_admin 平台级：须在请求中指定目标小区
            if (tenantId == null) {
                return Result.error(400, "超级管理员上传文档必须指定小区");
            }
            resolvedTenant = tenantId;
        }
        try {
            KnowledgeDocumentDTO dto = documentService.upload(file, category, tags, source, replace, resolvedTenant, adminId);
            return Result.ok(dto);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 文档列表（tenantId/status 可空过滤，一次性返回全部）。
     *
     * @param status 状态过滤（parsing/ready/failed，可空）
     * @param auth   当前认证管理员
     * @return 全部文档 DTO 列表
     */
    @GetMapping
    public Result<ListDTO<KnowledgeDocumentDTO>> list(@RequestParam(required = false) String status,
                                                      Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        Long tenantId = resolveTenantId(adminId);
        return Result.ok(new ListDTO<>(documentService.list(tenantId, status)));
    }

    /**
     * 删除文档（清库全量：切片 + 文档行 + 落盘文件）。
     *
     * @param id   文档 ID
     * @param auth 当前认证管理员
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public Result<IdResponseDTO> delete(@PathVariable Long id, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        try {
            documentService.delete(id, resolveTenantId(adminId));
            return Result.ok(IdResponseDTO.builder().id(id).build());
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 重试解析失败文档。
     *
     * @param id   文档 ID
     * @param auth 当前认证管理员
     * @return 操作结果
     */
    @PostMapping("/{id}/retry")
    public Result<IdResponseDTO> retry(@PathVariable Long id, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        try {
            documentService.retry(id, resolveTenantId(adminId));
            return Result.ok(IdResponseDTO.builder().id(id).build());
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 解析当前管理员的可管理小区 ID。
     *
     * @param adminId 管理员用户 ID
     * @return super_admin 返回 null（平台级视角），普通 admin 返回自身 tenantId
     */
    private Long resolveTenantId(Long adminId) {
        User admin = userRepository.findById(adminId).orElseThrow(() -> new BizException("管理员不存在"));
        if (UserType.SUPER_ADMIN.equals(admin.getUserType())) {
            return null;
        }
        Long tenantId = admin.getTenantId();
        if (tenantId == null) {
            throw new BizException("管理员未关联小区");
        }
        return tenantId;
    }
}
