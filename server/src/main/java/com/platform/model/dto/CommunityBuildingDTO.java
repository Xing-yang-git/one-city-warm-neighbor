package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 社区楼栋节点 DTO（含单元列表）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommunityBuildingDTO {
    /** 楼栋 ID */
    private Long id;
    /** 楼栋号（数值，展示拼 "x栋"） */
    private Integer buildingNo;
    /** 单元列表（按单元号升序） */
    private List<CommunityUnitDTO> units;
}
