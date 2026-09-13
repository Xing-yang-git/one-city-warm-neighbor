package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用操作结果 DTO — 无具体业务数据返回的操作类接口（如申诉、内容下架）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationResultDTO {
    /** 是否成功 */
    private Boolean success;
    /** 结果提示信息 */
    private String message;
}
