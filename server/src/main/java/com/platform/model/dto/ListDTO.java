package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用列表包装 DTO — 一次性返回全部数据的列表接口统一使用该包装。
 *
 * <p>字段名与前端约定的 {@code content} 数组一致（原分页 PageDTO 的 content 字段沿用），
 * 列表接口从「分页返回」改为「全量返回」后，前端继续通过 {@code res.data.data.content} 取值。</p>
 *
 * @param <T> 列表元素类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListDTO<T> {
    /** 列表数据（一次性返回全部元素） */
    private List<T> content;
}
