package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 社区楼栋/单元树形数据 DTO — 对应 /api/admin/community 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommunityDTO {
    /** 小区名称 */
    private String tenantName;
    /** 楼栋列表（按楼栋号升序） */
    private List<CommunityBuildingDTO> buildings;
}
