package com.platform.controller;

import com.platform.common.Result;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.SensitiveWordDTO;
import com.platform.security.LoginUser;
import com.platform.service.AdminService;
import com.platform.service.SensitiveWordService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感词管理 REST API（仅 super_admin）— 对话输入前置过滤词库的增删改查。
 *
 * <p>权限：安全层 {@code /api/admin/**} 已限定管理员角色，本模块在此基础上进一步
 * 限定为超级管理员——每个端点首行调用 {@link AdminService#requireSuperAdmin(Long)}，
 * 非 super_admin 一律拒绝（业务异常）。增删改成功后由服务层主动刷新内存缓存，改动即时生效。</p>
 */
@RestController
@RequestMapping("/api/admin/sensitive-words")
public class SensitiveWordController {

    private final SensitiveWordService sensitiveWordService;
    private final AdminService adminService;

    /**
     * 构造器注入。
     *
     * @param sensitiveWordService 敏感词匹配与 CRUD 服务
     * @param adminService         管理员服务（super_admin 权限校验）
     */
    public SensitiveWordController(SensitiveWordService sensitiveWordService, AdminService adminService) {
        this.sensitiveWordService = sensitiveWordService;
        this.adminService = adminService;
    }

    /**
     * 敏感词列表（可按状态过滤，一次性返回全部）。
     *
     * @param status 状态过滤（ENABLED/DISABLED，可空表示全部）
     * @param auth   当前认证管理员
     * @return 全部敏感词 DTO 列表
     */
    @GetMapping
    public Result<ListDTO<SensitiveWordDTO>> list(@RequestParam(required = false) String status,
                                                  Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.requireSuperAdmin(adminId);
        return Result.ok(new ListDTO<>(sensitiveWordService.list(status)));
    }

    /**
     * 新增敏感词。
     *
     * @param req  请求体（word 必填，status 缺省 ENABLED；词重复返回业务错误）
     * @param auth 当前认证管理员
     * @return 新建敏感词 DTO
     */
    @PostMapping
    public Result<SensitiveWordDTO> create(@Valid @RequestBody SensitiveWordDTO req, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.requireSuperAdmin(adminId);
        return Result.ok(sensitiveWordService.create(req));
    }

    /**
     * 编辑敏感词（word/status）。
     *
     * @param id   敏感词 ID
     * @param req  请求体（word 必填、status 可空保持原值）
     * @param auth 当前认证管理员
     * @return 更新后的敏感词 DTO
     */
    @PutMapping("/{id}")
    public Result<SensitiveWordDTO> update(@PathVariable Long id, @Valid @RequestBody SensitiveWordDTO req, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.requireSuperAdmin(adminId);
        return Result.ok(sensitiveWordService.update(id, req));
    }

    /**
     * 删除敏感词。
     *
     * @param id   敏感词 ID
     * @param auth 当前认证管理员
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.requireSuperAdmin(adminId);
        sensitiveWordService.delete(id);
        return Result.ok();
    }
}
