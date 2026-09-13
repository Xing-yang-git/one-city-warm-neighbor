package com.platform.controller;

import com.platform.common.Result;
import com.platform.model.dto.ApprovalCountDTO;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.MyPostItemDTO;
import com.platform.model.dto.UserProfileDTO;
import com.platform.security.LoginUser;
import com.platform.service.UserActivityService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户活动/个人中心 REST API — 个人信息、我的发布、审批管理、进行中/已完成记录。
 *
 * <p>提供 C端"我的"Tab 所有数据接口：
 * <ul>
 *   <li>个人资料（含评价统计）</li>
 *   <li>我的发布（闲置 + 求助）</li>
 *   <li>审批管理（我收到的借用/接单申请）</li>
 *   <li>进行中和已完成的借用/帮助记录</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/users")
public class UserActivityController {

    private final UserActivityService userActivityService;

    public UserActivityController(UserActivityService userActivityService) {
        this.userActivityService = userActivityService;
    }

    /**
     * 当前用户个人资料 — 我的 tab。
     * 一次调用返回用户信息、评分和统计数据。
     */
    @GetMapping("/profile")
    public Result<UserProfileDTO> profile(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(userActivityService.getProfile(userId));
    }

    /**
     * 我发布的帖子 — 发布 tab。
     * @param status 可选过滤条件："online" | "offline" | "completed"
     */
    @GetMapping("/posts")
    public Result<ListDTO<MyPostItemDTO>> myPosts(@RequestParam(required = false) String status, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(userActivityService.getMyPosts(userId, status)));
    }

    /**
     * 待审批事项 — 审批 tab。
     * @param type 审批类别，取值见 {@link com.platform.common.ActivityRole} 的 type 族
     */
    @GetMapping("/approvals")
    public Result<ListDTO<MyPostItemDTO>> approvals(@RequestParam String type, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(userActivityService.getApprovals(userId, type)));
    }

    /**
     * 待审批数量统计 — tabBar「管理」红点与审批 tab 角标。
     * 返回 { borrow, lend, help, total }。
     */
    @GetMapping("/approvals/count")
    public Result<ApprovalCountDTO> approvalsCount(Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(userActivityService.getApprovalCounts(userId));
    }

    /**
     * 进行中的交易 — 进行中 tab。
     * @param role 角色视角，取值见 {@link com.platform.common.ActivityRole} 的 role 族
     */
    @GetMapping("/in-progress")
    public Result<ListDTO<MyPostItemDTO>> inProgress(@RequestParam String role, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(userActivityService.getInProgress(userId, role)));
    }

    /**
     * 已完成的交易 — 已完成 tab。
     * @param role 角色视角，取值见 {@link com.platform.common.ActivityRole} 的 role 族
     */
    @GetMapping("/completed")
    public Result<ListDTO<MyPostItemDTO>> completed(@RequestParam String role, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(userActivityService.getCompleted(userId, role)));
    }
}
