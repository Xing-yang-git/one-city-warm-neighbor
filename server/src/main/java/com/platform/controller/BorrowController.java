package com.platform.controller;

import com.platform.common.Result;
import com.platform.model.dto.ApproveRequest;
import com.platform.model.dto.BorrowRequestDTO;
import com.platform.model.dto.BorrowResponseDTO;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.ReturnRequest;
import com.platform.security.LoginUser;
import com.platform.service.BorrowService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 借用管理 REST API — 借用申请、审批、归还确认、损坏记录。
 *
 * <p>C端用户对闲置物品发起借用申请，物主审批后完成交接。
 * 借用状态流转：pending（待审批）→ approved（已同意）/ rejected（已拒绝）→ returned（已归还）。
 * 归还时借出方可填写损坏评估，归还后可补充损坏详情。</p>
 */
@RestController
@RequestMapping("/api/borrow-requests")
public class BorrowController {

    private final BorrowService borrowService;

    public BorrowController(BorrowService borrowService) {
        this.borrowService = borrowService;
    }

    /**
     * 借用记录详情。
     *
     * @param id 借用记录 ID
     * @return 借用详情（含物品信息、双方用户信息、状态流转时间线）
     */
    @GetMapping("/{id}")
    public Result<BorrowResponseDTO> getDetail(@PathVariable Long id) {
        return Result.ok(borrowService.getDetail(id));
    }

    /**
     * 发起借用申请 — 借入方对指定物品发起借用请求。
     *
     * @param req  借用申请信息（物品 ID、借出时长、备注）
     * @param auth 当前认证用户（借入方）
     * @return 创建的借用记录
     */
    @PostMapping
    public Result<BorrowResponseDTO> apply(@Valid @RequestBody BorrowRequestDTO req, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(borrowService.apply(userId, req));
    }

    /**
     * 审批借用申请 — 物主同意或拒绝借用请求。
     *
     * @param id   借用记录 ID
     * @param req  审批结果（approved / rejected）+ 审批备注
     * @param auth 当前认证用户（物主）
     * @return 空响应
     */
    @PutMapping("/{id}/approve")
    public Result<Void> approveReject(@PathVariable Long id, @Valid @RequestBody ApproveRequest req,
                                   Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        borrowService.approveReject(userId, id, req);
        return Result.ok();
    }

    /**
     * 我的借用申请列表（借入方视角）。
     *
     * @param auth 当前认证用户
     * @return 借用申请列表
     */
    @GetMapping("/applications/my")
    public Result<ListDTO<BorrowResponseDTO>> myApplications(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(borrowService.getMyApplications(userId)));
    }

    /**
     * 待审批的借用申请列表（物主视角）。
     *
     * @param auth 当前认证用户（物主）
     * @return 待审批借用列表
     */
    @GetMapping("/approvals/pending")
    public Result<ListDTO<BorrowResponseDTO>> pendingApprovals(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(borrowService.getPendingApprovals(userId)));
    }

    /**
     * 确认归还 — 借出方确认物品已归还，填写归还评估。
     *
     * @param id   借用记录 ID
     * @param req  归还信息（是否按时、损坏类型、归还备注、照片）
     * @param auth 当前认证用户（借出方）
     * @return 空响应
     */
    @PutMapping("/{id}/return")
    public Result<Void> confirmReturn(@PathVariable Long id, @Valid @RequestBody ReturnRequest req,
                                   Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        borrowService.confirmReturn(userId, id, req);
        return Result.ok();
    }

    /**
     * 补充归还后物品状况 — 借出方在已完成归还的记录中补填损坏信息。
     *
     * <p>仅物品所有者可操作，仅已完成归还（returned 状态）的记录可补填。</p>
     *
     * @param id   借用记录 ID
     * @param body 包含 damageType 字段的请求体
     * @param auth 当前认证用户（借出方）
     * @return 空响应
     */
    @PutMapping("/{id}/damage")
    public Result<Void> updateDamage(@PathVariable Long id, @RequestBody java.util.Map<String, String> body,
                                   Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        borrowService.updateDamage(userId, id, body.get("damageType"));
        return Result.ok();
    }
}
