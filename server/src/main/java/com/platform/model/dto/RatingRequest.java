package com.platform.model.dto;

import lombok.Data;


@Data
public class RatingRequest {
    // 后端字段名
    private Long borrowId;
    private Long helpApplicationId;
    private Integer score;
    // C端小程序字段名（别名）
    private Long targetId;        // 对应 borrowId 或 helpApplicationId
    /** 评价类型，取值见 {@link com.platform.common.RatingType}：{@code borrow}（借用评价）/ {@code help}（互助评价） */
    private String ratingType;
    private Integer overallScore; // 对应 score
    private String feedback;      // 互助感想文本
}
