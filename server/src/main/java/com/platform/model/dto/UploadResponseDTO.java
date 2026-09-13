package com.platform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件上传响应 DTO — 图片/语音上传返回的文件访问 URL。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponseDTO {
    /** 上传后的文件访问 URL */
    private String url;
}
