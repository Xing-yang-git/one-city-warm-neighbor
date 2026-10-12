package com.platform.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class RegisterRequest {
    /** 所属小区 ID */
    private Long tenantId;
    /** 楼栋号（数值，如 3） */
    private Integer buildingNo;
    /** 单元号（数值，如 2） */
    private Integer unitNo;
    /** 房间号（如 "1502"） */
    private String room;
    /** 真实姓名 */
    private String name;
    /** 手机号（注册账号） */
    private String phone;
    /** 登录密码 */
    private String password;
    /** 住户身份编码：owner(业主) / tenant(租客)。注册仅接受这两种身份 */
    @NotBlank(message = "请选择住户身份")
    private String userType;
    /** 实名认证材料图片 URL 列表 */
    private List<String> docImages;
}
