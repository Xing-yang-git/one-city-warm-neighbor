package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 社区单元节点 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommunityUnitDTO {
    /** 单元 ID */
    private Long id;
    /** 单元号（数值，展示拼 "x单元"） */
    private Integer unitNo;
}
