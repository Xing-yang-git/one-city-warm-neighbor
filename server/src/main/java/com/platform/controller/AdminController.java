package com.platform.controller;

import com.platform.common.ActivityRole;
import com.platform.common.Result;
import com.platform.common.UserType;
import com.platform.model.dto.*;
import com.platform.security.LoginUser;
import com.platform.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;


/**
 * B端管理后台 REST API — 仪表盘、用户审核、内容管理、数据导出、系统配置。
 *
 * <p>提供 PC 管理端的全部后台接口：
 * <ul>
 *   <li>仪表盘数据（住户数、帖子数、借用/帮助统计、违规统计）</li>
 *   <li>用户审核（待审核/已通过/已驳回列表，审批操作）</li>
 *   <li>内容管理（闲置/求助列表、详情、下架）</li>
 *   <li>数据导出（住户/帖子/借用/帮助/下架/评价的 Excel 导出）</li>
 *   <li>记录查询（操作日志、导出历史）</li>
 *   <li>系统管理（管理员增删、小区/楼栋/单元/房间管理）</li>
 *   <li>代发功能（管理员以住户身份发布闲置/求助）</li>
 * </ul>
 *
 * <p>所有接口需管理员或超级管理员权限。列表类接口一次性返回全部数据（不分页）。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 导出文件名时间戳格式（yyyyMMdd_HHmm） */
    private static final DateTimeFormatter EXPORT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    /** 导出文件扩展名 */
    private static final String EXPORT_FILE_SUFFIX = ".xlsx";
    /** 操作日志导出文件名前缀 */
    private static final String EXPORT_OPERATION_PREFIX = "操作日志_";
    /** 导出日志文件名前缀 */
    private static final String EXPORT_EXPORT_PREFIX = "导出日志_";
    /** RFC 5987 附件下载响应头（UTF-8 编码文件名） */
    private static final String ATTACHMENT_DISPOSITION_UTF8 = "attachment; filename*=UTF-8''";

    // ===== 数据看板 =====
    /** 运营看板聚合数据（KPI + 趋势 + 完成率 + 损坏统计 + 排行） */
    @GetMapping("/dashboard")
    public Result<DashboardDTO> dashboard(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.getDashboard(adminId));
    }

    // ===== 住户审核 =====
    /** 按认证状态筛选审核列表，一次性返回全部 */
    @GetMapping("/audits")
    public Result<ListDTO<UserDTO>> audits(@RequestParam(required = false) String status,
                                           Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.getAudits(adminId, status)));
    }

    /** 审核各状态计数（待审核/已通过/已驳回/全部） */
    @GetMapping("/audits/counts")
    public Result<AuditCountDTO> auditCounts(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.getAuditCounts(adminId));
    }

    /** 审批住户认证（通过/驳回） */
    @PutMapping("/audits/{userId}")
    public Result<Void> auditUser(@PathVariable Long userId, @Valid @RequestBody AuditRequest req,
                                  Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.auditUser(adminId, userId, req);
        return Result.ok();
    }

    // ===== 内容管理 =====
    /** 按状态页签/类型/楼栋/关键词筛选内容列表，一次性返回全部 */
    @GetMapping("/content")
    public Result<ListDTO<ContentItemDTO>> contentList(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String type,
                                                       @RequestParam(name = "building_no", required = false) Integer buildingNo,
                                                       @RequestParam(name = "unit_no", required = false) Integer unitNo,
                                                       @RequestParam(required = false) String search,
                                                       @RequestParam(required = false) String moderationStatus,
                                                       @RequestParam(required = false) String moderatedBy,
                                                       Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.getContentList(adminId, status, type, buildingNo, unitNo, search,
                moderationStatus, moderatedBy)));
    }

    /** 内容各状态计数（展示中/待审批/进行中/已完成/违规/全部） */
    @GetMapping("/content/counts")
    public Result<ContentCountDTO> contentCounts(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.getContentCounts(adminId));
    }

    /** 内容详情（闲置/求助） */
    @GetMapping("/content/{id}")
    public Result<ContentItemDTO> contentDetail(@PathVariable Long id, @RequestParam String type,
                                                Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.getContentDetail(adminId, id, type));
    }

    /** 违规下架内容（审核场景带驳回原因） */
    @PutMapping("/content/{id}/offline")
    public Result<OperationResultDTO> offlineContent(@PathVariable Long id,
                                                     @Valid @RequestBody ContentOfflineRequest req,
                                                     Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.removeContent(adminId, id, req));
    }

    /** AI 内容审核通过 — 管理员人工确认帖子合规，将其上线。updatedAt 可选，用于乐观锁版本检查 */
    @PutMapping("/content/{id}/approve")
    public Result<Void> approveContent(@PathVariable Long id, @RequestParam String type,
                                       @RequestParam(required = false) String updatedAt,
                                       Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.approveContent(adminId, id, type, updatedAt);
        return Result.ok();
    }

    // ===== 小区列表（super_admin 创建管理员时选择目标小区） =====
    /** 全部小区列表 */
    @GetMapping("/tenants")
    public Result<ListDTO<TenantDTO>> tenants() {
        return Result.ok(new ListDTO<>(adminService.getAllTenants()));
    }

    // ===== 社区数据（登录后一次性加载） =====
    /** 当前小区楼栋/单元树形数据 */
    @GetMapping("/community")
    public Result<CommunityDTO> community(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.getCommunityData(adminId));
    }

    // ===== 楼栋列表 =====
    /** 当前小区楼栋列表 */
    @GetMapping("/buildings")
    public Result<ListDTO<BuildingDTO>> buildings(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.getBuildings(adminId)));
    }

    // ===== 个人信息 =====
    /** 更新当前管理员姓名 */
    @PutMapping("/profile")
    public Result<ProfileResponseDTO> updateProfile(@Valid @RequestBody Map<String, String> body,
                                                    Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.updateProfile(adminId, body.get("name")));
    }

    // ===== 修改密码 =====
    /** 修改当前管理员密码 */
    @PutMapping("/password")
    public Result<Void> updatePassword(@Valid @RequestBody Map<String, String> body, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.updatePassword(adminId, body.get("oldPassword"), body.get("newPassword"));
        return Result.ok();
    }

    // ===== 管理员账号管理（仅 super_admin） =====
    /** 管理员账号列表 */
    @GetMapping("/admins")
    public Result<ListDTO<AdminDTO>> admins(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.getAdmins(adminId)));
    }

    /** 创建管理员账号 */
    @PostMapping("/admins")
    public Result<AdminDTO> createAdmin(@Valid @RequestBody Map<String, String> body,
                                        Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        Long tenantId = body.get("tenantId") != null ? Long.valueOf(body.get("tenantId")) : null;
        String userType = body.getOrDefault("userType", UserType.ADMIN);
        return Result.ok(adminService.createAdmin(adminId,
                body.get("name"), body.get("phone"), body.get("password"), tenantId, userType));
    }

    /** 删除管理员账号 */
    @DeleteMapping("/admins/{id}")
    public Result<Void> deleteAdmin(@PathVariable Long id, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        adminService.deleteAdmin(adminId, id);
        return Result.ok();
    }

    // ===== 住户检索 =====
    /** 按楼栋/单元/房间/身份/关键词检索住户，一次性返回全部 */
    @GetMapping("/residents/search")
    public Result<ListDTO<ResidentDTO>> searchResidents(@RequestParam(name = "building_no", required = false) Integer buildingNo,
                                                        @RequestParam(name = "unit_no", required = false) Integer unitNo,
                                                        @RequestParam(required = false) String room,
                                                        @RequestParam(required = false) String userType,
                                                        @RequestParam(required = false) String keyword,
                                                        Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.searchResidents(adminId, buildingNo, unitNo, room, userType, keyword)));
    }

    // ===== 物业代发 =====
    /** 代发闲置物品 */
    @PostMapping("/proxy/idle")
    public Result<IdleItemDTO> proxyPublishIdle(@Valid @RequestBody IdleItemRequest req, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.proxyPublishIdle(adminId, req));
    }

    /** 代发求助 */
    @PostMapping("/proxy/help")
    public Result<HelpResponseDTO> proxyPublishHelp(@Valid @RequestBody HelpRequestDTO req, Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(adminService.proxyPublishHelp(adminId, req));
    }

    // ===== 互助记录 =====
    /** 互助记录列表（借入归还 + 求助完成），一次性返回全部 */
    @GetMapping("/records")
    public Result<ListDTO<RecordItemDTO>> records(@RequestParam(defaultValue = ActivityRole.BORROW) String type,
                                                  Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.getRecords(adminId, type)));
    }

    // ===== 数据导出 =====

    /**
     * 执行数据导出，生成多 Sheet 的 Excel 文件并返回二进制流。
     * 请求体包含勾选项目列表、日期范围和导出格式。
     */
    @PostMapping("/exports")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ExportRequest req,
                                         Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        byte[] excelBytes = adminService.exportData(adminId, req);
        // 文件名格式：{小区名}_{导出日期yyyyMMdd_HHmm}.xlsx
        String tenantName = adminService.getTenantName(adminId);
        String fileName = tenantName + "_"
                + ZonedDateTime.now(ZoneId.systemDefault()).format(EXPORT_TIME_FORMATTER)
                + EXPORT_FILE_SUFFIX;
        // RFC 5987 编码，支持中文文件名
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ATTACHMENT_DISPOSITION_UTF8 + encodedFileName)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }

    /** 查询导出日志（按时间倒序，一次性返回当前管理员所属小区的全部记录） */
    @GetMapping("/exports/logs")
    public Result<ListDTO<ExportLogDTO>> exportLogs(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.getExportLogs(adminId)));
    }

    // ===== 操作日志 =====
    /** 操作日志列表（一次性返回全部） */
    @GetMapping("/logs")
    public Result<ListDTO<OperationLogDTO>> operationLogs(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(adminService.getOperationLogs(adminId)));
    }

    /** 导出操作日志为 Excel 文件 */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportOperationLogs(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        byte[] bytes = adminService.exportOperationLogs(adminId);
        String filename = EXPORT_OPERATION_PREFIX
                + ZonedDateTime.now(ZoneId.systemDefault()).format(EXPORT_TIME_FORMATTER)
                + EXPORT_FILE_SUFFIX;
        return ResponseEntity.ok()
                // RFC 5987 编码，支持中文文件名
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ATTACHMENT_DISPOSITION_UTF8 + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    /** 导出导出日志为 Excel 文件 */
    @GetMapping("/exports/logs/export")
    public ResponseEntity<byte[]> exportExportLogs(Authentication auth) {
        Long adminId = ((LoginUser) auth.getPrincipal()).getUserId();
        byte[] bytes = adminService.exportExportLogs(adminId);
        String filename = EXPORT_EXPORT_PREFIX
                + ZonedDateTime.now(ZoneId.systemDefault()).format(EXPORT_TIME_FORMATTER)
                + EXPORT_FILE_SUFFIX;
        return ResponseEntity.ok()
                // RFC 5987 编码，支持中文文件名
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ATTACHMENT_DISPOSITION_UTF8 + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

}
