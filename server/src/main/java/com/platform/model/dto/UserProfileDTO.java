package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户个人资料 DTO — 对应 /api/users/profile 响应（我的 tab 数据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {
    /** 用户 ID */
    private Long id;
    /** 用户姓名 */
    private String name;
    /** 用户类型（owner/tenant 等原始值） */
    private String userType;
    /** 用户类型中文标签（业主/租客） */
    private String userTypeText;
    /** 房号信息（如 "3栋2单元1502"） */
    private String roomInfo;
    /** 是否已通过认证 */
    private Boolean isAuth;
    /** 平均评分（保留 1 位小数，无评分默认 5.0） */
    private Double score;
    /** 收到评价数 */
    private Integer ratingCount;
    /** 借出次数（闲置出借） */
    private Integer lendCount;
    /** 借入次数（闲置借入） */
    private Integer borrowCount;
    /** 按时归还率（百分比，无归还记录默认 100.0） */
    private Double borrowReturnRate;
    /** 求助发起次数 */
    private Integer helpReqCount;
    /** 求助相助次数 */
    private Integer helpProCount;
}
