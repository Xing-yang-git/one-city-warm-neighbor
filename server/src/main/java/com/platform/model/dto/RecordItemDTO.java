package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 互助记录条目 DTO — 互助记录列表（借入归还 + 求助完成）的单条记录。
 *
 * <p>由借用记录（{@code loadBorrowRecords}）与求助记录（{@code loadHelpRecords}）
 * 统一组装，字段对齐 B端 RecordsView 的展示需求：基础信息 + 5 节点时间线 + 双方评分。
 * borrow 类型的归还详情字段（lendDuration/condBefore/condAfter/returnStatus/damageType）
 * 仅借入记录填充，求助记录对应字段为 null。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordItemDTO {
    /** 记录 ID（借用/求助申请 ID） */
    private Long id;

    /** 记录类型：borrow(借入归还) / help(求助完成) */
    private String type;

    /** 物品/求助标题 */
    private String title;

    /** 发布方姓名（借出方/求助方，已脱敏） */
    private String publisher;

    /** 对方姓名（借入方/相助方，已脱敏） */
    private String peer;

    /** 内容摘要（当前复用标题） */
    private String content;

    /** 发布方房号（如 "3栋2单元1502"） */
    private String room;

    /** 开始时间（格式化 "yyyy-MM-dd HH:mm"） */
    private String timeStart;

    /** 结束时间（归还/完成时间，格式化；未结束为 null） */
    private String timeEnd;

    /** 记录创建时间（格式化，用于列表倒序排序） */
    private String createdAt;

    /** 状态（数据库原始值，如 returned/completed） */
    private String status;

    /** 时间线-发布时间 */
    private String publishedAt;

    /** 时间线-申请时间 */
    private String applyAt;

    /** 时间线-同意时间 */
    private String approveAt;

    /** 时间线-第一条评价标签（借入方评价/借出方评价等） */
    private String rating1Label;

    /** 时间线-第一条评价时间 */
    private String rating1Time;

    /** 时间线-第二条评价标签 */
    private String rating2Label;

    /** 时间线-第二条评价时间 */
    private String rating2Time;

    /** 借用时长描述（如 "3天"、"1周"），仅 borrow 记录 */
    private String lendDuration;

    /** 借出前状况标签（仅 borrow 记录） */
    private String condBefore;

    /** 归还后状况标签（仅 borrow 记录） */
    private String condAfter;

    /** 归还状态标签（仅 borrow 记录） */
    private String returnStatus;

    /** 损坏类型（数据库原始值，仅 borrow 记录） */
    private String damageType;

    /** 发布方收到的评分（1-5），无评价为 null */
    private Integer pubRatingScore;

    /** 对方感想（发布方视角，实际取被评人的反馈） */
    private String pubComment;

    /** 对方收到的评分（1-5），无评价为 null */
    private Integer peerRatingScore;

    /** 发布方感想（对方视角，实际取被评人的反馈） */
    private String peerComment;
}
