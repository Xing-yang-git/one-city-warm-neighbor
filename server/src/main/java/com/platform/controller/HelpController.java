package com.platform.controller;

import com.platform.common.Result;
import com.platform.model.dto.ApproveRequest;
import com.platform.model.dto.HelpRequestDTO;
import com.platform.model.dto.HelpResponseDTO;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.PageDTO;
import com.platform.security.LoginUser;
import com.platform.service.HelpService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * 互助求助 REST API — 发布求助、浏览、搜索、接单、审批、完成。
 *
 * <p>提供 C端社区互助求助的完整生命周期管理：
 * <ul>
 *   <li>发布求助（支持标记紧急、设置时间范围）</li>
 *   <li>首页流浏览（分页）</li>
 *   <li>关键词搜索（租户隔离）</li>
 *   <li>其他用户申请接单 → 求助者审批</li>
 *   <li>完成后双方互评</li>
 *   <li>下架 / 修改</li>
 * </ul>
 *
 * <p>管理员可通过代发功能以目标住户身份发布求助。</p>
 */
@RestController
@RequestMapping("/api/help-requests")
public class HelpController {

    private final HelpService helpService;

    public HelpController(HelpService helpService) {
        this.helpService = helpService;
    }

    /**
     * 发布求助信息。
     *
     * @param req  求助发布请求体（标题、分类、描述、图片、是否紧急、时间范围等）
     * @param auth 当前认证用户
     * @return 创建成功的求助摘要
     */
    @PostMapping
    public Result<HelpResponseDTO> publish(@Valid @RequestBody HelpRequestDTO req, Authentication auth) {
        Long userId = resolveUserId(auth, req.getUserId());
        return Result.ok(helpService.publish(userId, req));
    }

    /**
     * 解析实际操作用户 ID — 若当前认证用户为管理员且请求中指定了目标住户 ID，
     * 则使用目标住户 ID（代发场景）；否则使用认证用户自身 ID。
     */
    private Long resolveUserId(Authentication auth, Long requestUserId) {
        if (requestUserId != null && auth != null) {
            Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
            if (authorities != null && authorities.stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SUPER_ADMIN".equals(a.getAuthority()))) {
                return requestUserId;
            }
        }
        return ((LoginUser) auth.getPrincipal()).getUserId();
    }

    /**
     * 首页求助列表（分页、租户隔离）。
     *
     * @param page 页码（从 0 开始）
     * @param size 每页条数
     * @param auth 当前认证用户（用于租户隔离，可为 null）
     * @return 分页求助列表
     */
    @GetMapping("/home")
    public Result<PageDTO<HelpResponseDTO>> homeList(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "10") int size,
                               Authentication auth) {
        Long userId = auth != null && auth.getPrincipal() instanceof LoginUser loginUser
                ? loginUser.getUserId() : null;
        return Result.ok(helpService.getHomeList(userId, page, size));
    }

    /**
     * 求助详情。
     *
     * @param id   求助 ID
     * @param auth 当前认证用户（用于返回该用户对求助的申请状态，可为 null）
     * @return 求助详情（含发布者信息与当前用户的申请状态）
     */
    @GetMapping("/{id}")
    public Result<HelpResponseDTO> detail(@PathVariable Long id, Authentication auth) {
        Long userId = auth != null && auth.getPrincipal() instanceof LoginUser loginUser
                ? loginUser.getUserId() : null;
        return Result.ok(helpService.getDetail(id, userId));
    }

    /**
     * 关键词搜索求助（租户隔离）。
     *
     * @param keyword 搜索关键词
     * @param page    页码（从 0 开始）
     * @param size    每页条数
     * @param auth    当前认证用户（用于租户隔离）
     * @return 搜索结果分页
     */
    @GetMapping("/search")
    public Result<PageDTO<HelpResponseDTO>> search(@RequestParam String keyword,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "10") int size,
                             Authentication auth) {
        Long userId = auth != null && auth.getPrincipal() instanceof LoginUser loginUser
                ? loginUser.getUserId() : null;
        return Result.ok(helpService.search(userId, keyword, page, size));
    }

    /**
     * 当前用户的求助列表（我的发布）。
     *
     * @param auth 当前认证用户
     * @return 用户发布的求助列表
     */
    @GetMapping("/my")
    public Result<ListDTO<HelpResponseDTO>> myPosts(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(helpService.getMyPosts(userId)));
    }

    /**
     * 下架求助（发布者自行下架或管理员违规下架）。
     *
     * @param id   求助 ID
     * @param auth 当前认证用户
     * @return 空响应
     */
    @PutMapping("/{id}/delist")
    public Result<Void> delist(@PathVariable Long id, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        helpService.delist(userId, id);
        return Result.ok();
    }

    /**
     * 申请接单 — 社区成员对求助发起接单申请。
     *
     * @param id   求助 ID
     * @param body 包含 note（申请备注）的请求体
     * @param auth 当前认证用户（接单方）
     * @return 空响应
     */
    @PostMapping("/{id}/apply")
    public Result<Void> apply(@PathVariable Long id, @Valid @RequestBody Map<String, String> body,
                           Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        helpService.apply(userId, id, body.getOrDefault("note", ""));
        return Result.ok();
    }

    /**
     * 审批接单申请 — 求助发布者同意或拒绝接单申请。
     *
     * @param appId 帮助申请 ID
     * @param req   审批结果（approved / rejected）+ 审批备注
     * @param auth  当前认证用户（求助发布者）
     * @return 空响应
     */
    @PutMapping("/applications/{appId}/approve")
    public Result<Void> approveApplication(@PathVariable Long appId, @Valid @RequestBody ApproveRequest req,
                                        Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        helpService.approveReject(userId, appId, req);
        return Result.ok();
    }

    /**
     * 我的接单申请列表（接单方视角）。
     *
     * @param auth 当前认证用户
     * @return 接单申请列表
     */
    @GetMapping("/applications/my")
    public Result<ListDTO<HelpResponseDTO>> myApplications(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(helpService.getMyApplications(userId)));
    }

    /**
     * 待审批的接单申请列表（求助发布者视角）。
     *
     * @param auth 当前认证用户（求助发布者）
     * @return 待审批接单列表
     */
    @GetMapping("/applications/pending")
    public Result<ListDTO<HelpResponseDTO>> pendingApprovals(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(helpService.getPendingApprovals(userId)));
    }

    /**
     * 修改求助信息（仅发布者可修改）。
     *
     * @param id   求助 ID
     * @param req  修改后的求助信息
     * @param auth 当前认证用户
     * @return 更新后的求助信息
     */
    @PutMapping("/{id}")
    public Result<HelpResponseDTO> update(@PathVariable Long id, @Valid @RequestBody HelpRequestDTO req,
                            Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(helpService.update(userId, id, req));
    }

    /**
     * 确认帮助完成 — 求助发布者标记接单已完成。
     *
     * @param appId 帮助申请 ID
     * @param auth  当前认证用户（求助发布者）
     * @return 完成结果
     */
    @PutMapping("/applications/{appId}/complete")
    public Result<HelpResponseDTO> completeHelp(@PathVariable Long appId, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(helpService.completeHelp(userId, appId));
    }
}
