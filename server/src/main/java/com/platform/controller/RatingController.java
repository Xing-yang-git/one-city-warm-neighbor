package com.platform.controller;

import com.platform.common.Result;
import com.platform.model.dto.RatingRequest;
import com.platform.model.dto.UserRatingsDTO;
import com.platform.security.LoginUser;
import com.platform.service.RatingService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 用户评价 REST API — 提交评价、查看评价记录。
 *
 * <p>借用归还后或帮助完成后，双方可互相评价（1-5 星 + 文字反馈）。
 * 评价与借用记录（borrowId）或帮助申请（helpApplicationId）关联。</p>
 */
@RestController
@RequestMapping("/api/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    /**
     * 提交评价 — 借用归还或帮助完成后对合作方进行评分和反馈。
     *
     * @param req  评价请求（关联借用/帮助记录 ID、评分、反馈文字）
     * @param auth 当前认证用户（评价方）
     * @return 空响应
     */
    @PostMapping
    public Result<Void> submit(@Valid @RequestBody RatingRequest req, Authentication auth) {
        Long userId = ((LoginUser) auth.getPrincipal()).getUserId();
        ratingService.submitRating(userId, req);
        return Result.ok();
    }

    /**
     * 查看指定用户的评价记录（平均分和评价列表）。
     *
     * @param userId 目标用户 ID
     * @return 评价统计信息 + 评价列表
     */
    @GetMapping("/user/{userId}")
    public Result<UserRatingsDTO> getUserRatings(@PathVariable Long userId) {
        return Result.ok(ratingService.getUserRatings(userId));
    }
}
