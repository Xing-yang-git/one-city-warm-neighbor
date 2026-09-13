package com.platform.controller;

import com.platform.common.PostType;
import com.platform.common.Result;
import com.platform.model.dto.IdleItemDTO;
import com.platform.model.dto.IdleItemRequest;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.PageDTO;
import com.platform.security.LoginUser;
import com.platform.service.IdleService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

/**
 * 闲置物品管理 REST API。
 *
 * <p>提供 C端闲置物品的完整生命周期管理：
 * <ul>
 *   <li>发布出借/求借物品</li>
 *   <li>首页流浏览（按 postType 筛选、分页）</li>
 *   <li>关键词搜索（租户隔离）</li>
 *   <li>物品详情</li>
 *   <li>下架 / 删除 / 修改</li>
 * </ul>
 *
 * <p>管理员可通过请求中指定目标住户 ID 进行代发操作（resolveUserId 逻辑）。</p>
 */
@RestController
@RequestMapping("/api/idle-items")
public class IdleController {

    private final IdleService idleService;

    public IdleController(IdleService idleService) {
        this.idleService = idleService;
    }

    /**
     * 发布闲置物品（出借或求借）。
     *
     * @param req  闲置物品发布请求体（标题、分类、图片、借出时长等）
     * @param auth 当前认证用户
     * @return 创建成功的闲置物品摘要
     */
    @PostMapping
    public Result<IdleItemDTO> publish(@Valid @RequestBody IdleItemRequest req, Authentication auth) {
        Long userId = resolveUserId(auth, req.getUserId());
        return Result.ok(idleService.publish(userId, req));
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
     * 首页闲置物品列表（按发布类型筛选、分页）。
     *
     * @param postType 发布类型：LEND(出借) / WANTED(求借)，默认 LEND
     * @param page     页码（从 0 开始）
     * @param size     每页条数
     * @param auth     当前认证用户（用于租户隔离）
     * @return 分页物品列表
     */
    @GetMapping("/home")
    public Result<PageDTO<IdleItemDTO>> homeList(@RequestParam(defaultValue = PostType.LEND) String postType,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "10") int size,
                               Authentication auth) {
        Long userId = auth != null && auth.getPrincipal() instanceof LoginUser loginUser
                ? loginUser.getUserId() : null;
        return Result.ok(idleService.getHomeList(postType, userId, page, size));
    }

    /**
     * 闲置物品详情。
     *
     * @param id   物品 ID
     * @param auth 当前认证用户（可能为 null，游客也可查看）
     * @return 物品详情（含发布者信息、是否已收藏等）
     */
    @GetMapping("/{id}")
    public Result<IdleItemDTO> detail(@PathVariable Long id, Authentication auth) {
        Long currentUserId = null;
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            currentUserId = loginUser.getUserId();
        }
        return Result.ok(idleService.getDetail(id, currentUserId));
    }

    /**
     * 关键词搜索闲置物品（租户隔离），支持三种搜索模式。
     *
     * @param keyword  搜索关键词
     * @param postType 发布类型筛选
     * @param page     页码（从 0 开始）
     * @param size     每页条数
     * @param mode     搜索模式：keyword(默认，LIKE 关键词) / semantic(语义向量) / 空或混合(语义+关键词去重合并)
     * @param auth     当前认证用户（用于租户隔离）
     * @return 搜索结果分页
     */
    @GetMapping("/search")
    public Result<PageDTO<IdleItemDTO>> search(@RequestParam String keyword,
                             @RequestParam(defaultValue = PostType.LEND) String postType,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "10") int size,
                             @RequestParam(defaultValue = "") String mode,
                             Authentication auth) {
        Long userId = auth != null && auth.getPrincipal() instanceof LoginUser loginUser
                ? loginUser.getUserId() : null;
        return Result.ok(idleService.search(userId, keyword, postType, page, size, mode));
    }

    /**
     * 当前用户的物品列表（我的发布）。
     *
     * @param postType 发布类型筛选
     * @param auth     当前认证用户
     * @return 用户发布的物品列表
     */
    @GetMapping("/my")
    public Result<ListDTO<IdleItemDTO>> myPosts(@RequestParam(defaultValue = PostType.LEND) String postType,
                                                Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(new ListDTO<>(idleService.getMyPosts(userId, postType)));
    }

    /**
     * 下架物品（发布者自行下架或管理员违规下架）。
     *
     * @param id   物品 ID
     * @param auth 当前认证用户
     * @return 空响应
     */
    @PutMapping("/{id}/delist")
    public Result<Void> delist(@PathVariable Long id, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        idleService.delist(userId, id);
        return Result.ok();
    }

    /**
     * 删除物品（软删除，仅发布者或管理员可操作）。
     *
     * @param id   物品 ID
     * @param auth 当前认证用户
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        idleService.deleteItem(userId, id);
        return Result.ok();
    }

    /**
     * 修改物品信息（仅发布者可修改）。
     *
     * @param id   物品 ID
     * @param req  修改后的物品信息
     * @param auth 当前认证用户
     * @return 更新后的物品信息
     */
    @PutMapping("/{id}")
    public Result<IdleItemDTO> update(@PathVariable Long id, @Valid @RequestBody IdleItemRequest req,
                            Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        return Result.ok(idleService.update(userId, id, req));
    }
}
