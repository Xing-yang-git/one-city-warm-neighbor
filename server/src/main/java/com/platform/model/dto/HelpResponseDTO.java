package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * C端求助详情响应 DTO。
 * 包含求助帖子的完整信息、发布者信息、当前用户的申请状态以及发布者的历史互助统计数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpResponseDTO {

    /** 求助帖子主键 ID */
    private Long id;

    /** 发布者用户 ID，外键关联 users 表 */
    private Long userId;

    /** 发布者昵称 */
    private String userName;

    /** 发布者房号 */
    private String userRoom;

    /** 求助标题 */
    private String title;

    /** 求助详细描述 */
    private String description;

    /** 求助分类标签 */
    private String category;

    /** 是否为紧急求助 */
    private Boolean isUrgent;

    /** 期望开始时间 */
    private LocalDateTime timeStart;

    /** 期望结束时间 */
    private LocalDateTime timeEnd;

    /** 求助地点/位置描述 */
    private String location;


    /** 配图 URL，可能为 JSON 数组字符串 */
    private String images;

    /** 求助状态，有效值："online"（在线）/ "draft"（草稿）/ "offline"（已下架）/ "completed"（已完成） */
    private String status;

    /** 统一下架原因，闲置与求助共用 */
    private String delistReason;

    /** 是否为物业代发 */
    private Boolean isProxy;

    /** 帮忙人数 */
    private int helperCount;

    /** 当前用户的帮忙申请 ID，NULL 表示当前用户尚未申请帮忙 */
    private Long applicationId;

    /** 当前用户的帮忙申请状态，有效值："pending"（待审核）/ "approved"（已同意）/ "rejected"（已拒绝），NULL 表示未申请 */
    private String applicationStatus;

    /** 帮忙者用户 ID，外键关联 users 表 */
    private Long helperId;

    /** 帮忙者昵称 */
    private String helperName;

    /** 申请附言/说明文字 */
    private String applicationNote;

    /** 帖子创建时间 */
    private LocalDateTime createdAt;

    /** 发布者评分 */
    private Double rating;

    /** 发布者累计帮助他人次数 */
    private Long helpCount;

    /** 发布者累计被帮助次数 */
    private Long helpedCount;

    /** 发布者累计借入次数 */
    private Long borrowCount;

    /** 发布者累计借出次数 */
    private Long lendCount;

    /** 发布者归还率，格式为百分比字符串 */
    private String returnRate;

    /**
     * 当前登录用户对该求助的申请状态，仅详情接口返回。
     * 有效值："pending"（待审批）/ "approved"（已同意）/ "rejected"（已拒绝）/ "completed"（已完成）；
     * NULL 表示当前用户从未申请过该求助（或未登录）。
     * C端据此判断「我来帮忙」按钮是否禁用——rejected 允许重新申请，不禁用。
     */
    private String userApplyStatus;
}
