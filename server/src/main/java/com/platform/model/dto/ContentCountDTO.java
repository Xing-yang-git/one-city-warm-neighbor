package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容各状态计数 DTO — 对应 /api/admin/content/counts 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentCountDTO {
    /** 展示中的内容数（在线闲置 + 求助） */
    private Long showing;
    /** 待审批的内容数（借用/帮助申请 pending） */
    private Long pending;
    /** 进行中的内容数（active 状态） */
    private Long progressing;
    /** 已完成的内容数 */
    private Long completed;
    /** 违规下架的内容数 */
    private Long violation;
    /** 全部内容数 */
    private Long all;
}
