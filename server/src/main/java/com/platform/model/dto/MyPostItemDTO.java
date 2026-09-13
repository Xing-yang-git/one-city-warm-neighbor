package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * C端"我的发布"管理页的统一 DTO。
 * 覆盖：发布(online/offline)、审批、进行中、已完成 四个 tab 的视图数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyPostItemDTO {

    // ==================== 基础标识字段 ====================

    /** 帖子主键 ID */
    private Long id;

    /** 内容大类，有效值："idle"（闲置）/ "help"（求助） */
    private String type;

    /** 子类型，用于进行中/已完成场景区分角色，取值见 {@link com.platform.common.ActivityRole} 的 role 族 */
    private String subType;

    /** 发布类型，有效值："LEND"（借出）/ "HELP"（求助） */
    private String postType;

    // ==================== 帖子内容字段（"发布" tab 展示） ====================

    /** 帖子标题 */
    private String title;

    /** 帖子分类标签 */
    private String category;

    /** 帖子描述文本 */
    private String description;

    /** 配图 URL，存储为 JSON 数组字符串 */
    private String images;

    /** 价格，仅闲置类型使用 */
    private BigDecimal price;

    /** 物品成色，仅闲置类型使用 */
    private String condition;

    /** 最长借出/借入天数 */
    private Integer maxDuration;

    /** 借用时长单位 */
    private String durationUnit;

    /** 取货方式 */
    private String pickupMethod;

    /** 是否为紧急求助，仅求助类型使用 */
    private Boolean isUrgent;

    /** 是否为物业代发 */
    private Boolean isProxy;

    /** 数据库原始状态值 */
    private String status;

    /** 前端展示状态，有效值："在线" / "已下架" / "进行中" / "已完成" */
    private String displayStatus;

    /** 帖子创建时间 */
    private LocalDateTime createdAt;

    /** 帖子更新时间 */
    private LocalDateTime updatedAt;

    // ==================== 对方信息字段（用于进行中/已完成） ====================

    /** 对方用户 ID，用于点击跳转聊天，外键关联 users 表 */
    private Long personId;

    /** 对方昵称 */
    private String personName;

    /** 对方房号，格式示例："3栋2单元1502号" */
    private String personRoom;

    /** 对方身份类型，有效值："业主" / "租客" */
    private String personType;

    /** 对方评分 */
    private Double personRating;

    // ==================== 用户统计数据（用于审批详情弹层） ====================

    /** 累计借入次数 */
    private Integer borrowCount;

    /** 借入归还率 */
    private Double borrowReturnRate;

    /** 累计借出次数 */
    private Integer lendCount;

    /** 累计求助次数 */
    private Integer helpReqCount;

    /** 累计帮忙次数 */
    private Integer helpProCount;

    // ==================== 申请说明 ====================

    /** 申请说明，如借用说明/借入说明/求助说明 */
    private String note;

    // ==================== 求助时间范围（仅 HELP 使用） ====================

    /** 期望开始时间，格式为 "yyyy-MM-dd HH:mm" */
    private String timeStart;

    /** 期望结束时间，格式为 "yyyy-MM-dd HH:mm" */
    private String timeEnd;

    // ==================== 进行中字段 ====================

    /** 剩余天数 */
    private Integer remainingDays;

    /** 剩余小时数 */
    private Integer remainingHours;

    /** 预计归还天数 */
    private Integer expectedReturnDays;

    /** 是否已逾期 */
    private Boolean isOverdue;

    /** 角色标签，有效值："借出住户" / "借走住户" / "帮助住户" / "求助住户" */
    private String roleLabel;

    /** 元信息文本，如 "剩余 3 天归还" */
    private String metaText;

    // ==================== 已完成字段 ====================

    /** 互助完成确认时间 */
    private LocalDateTime completedAt;

    /** 我给出的评分 */
    private Double myRating;

    /** 我给出的评价文字 */
    private String myFeedback;

    /** 对方给出的评分 */
    private Double theirRating;

    /** 对方给出的评价文字 */
    private String theirFeedback;

    // ==================== 借用归还明细（"我的"页记录弹框） ====================

    /** 归还后物品状况，有效值："normal"（完好）/ "severe"（严重损坏）/ "broken"（已损坏） */
    private String damageType;

    /** 是否按时归还 */
    private Boolean isOnTime;
}
