package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户评价记录 DTO — 对应 /api/ratings/user/{userId} 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRatingsDTO {
    /** 评价列表 */
    private List<RatingDTO> ratings;
    /** 平均评分（保留 1 位小数） */
    private Double averageScore;
    /** 评价总数 */
    private Long totalRatings;
}
