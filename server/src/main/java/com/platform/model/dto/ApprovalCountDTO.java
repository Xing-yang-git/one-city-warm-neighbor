package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待审批数量统计 DTO — 对应 /api/users/approvals/count 响应（管理 tab 红点）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalCountDTO {
    /** 待确认借入数（别人愿借出给我发布的 WANTED） */
    private Integer borrow;
    /** 待审批借出数（别人申请借入我发布的 LEND） */
    private Integer lend;
    /** 待审批帮助申请数 */
    private Integer help;
    /** 待审批总数 */
    private Integer total;
}
