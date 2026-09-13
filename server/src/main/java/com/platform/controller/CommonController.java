package com.platform.controller;

import com.platform.ai.PolishingClient;
import com.platform.common.Result;
import com.platform.model.dto.BuildingDTO;
import com.platform.model.dto.ListDTO;
import com.platform.model.dto.PolishRequest;
import com.platform.model.dto.PolishResponse;
import com.platform.model.dto.RoomDTO;
import com.platform.model.dto.TenantDTO;
import com.platform.model.dto.UnitDTO;
import com.platform.model.dto.UploadResponseDTO;
import com.platform.service.CommonService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 公共资源 REST API — 小区/楼栋/单元/房间级联查询、文件上传。
 *
 * <p>提供无需认证的公共数据接口：
 * <ul>
 *   <li>空间层级数据（小区 → 楼栋 → 单元 → 房间）供注册和筛选使用</li>
 *   <li>图片和语音文件上传</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/common")
public class CommonController {

    private final CommonService commonService;
    private final PolishingClient polishingClient;

    public CommonController(CommonService commonService, PolishingClient polishingClient) {
        this.commonService = commonService;
        this.polishingClient = polishingClient;
    }

    /**
     * 获取全部小区列表。
     *
     * @return 小区列表
     */
    @GetMapping("/tenants")
    public Result<ListDTO<TenantDTO>> getTenants() {
        return Result.ok(new ListDTO<>(commonService.getAllTenants()));
    }

    /**
     * 获取指定小区下的全部楼栋。
     *
     * @param tenantId 小区 ID
     * @return 楼栋列表
     */
    @GetMapping("/buildings")
    public Result<ListDTO<BuildingDTO>> getBuildings(@RequestParam Long tenantId) {
        return Result.ok(new ListDTO<>(commonService.getBuildingsByTenantId(tenantId)));
    }

    /**
     * 获取指定楼栋下的全部单元。
     *
     * @param buildingId 楼栋 ID
     * @return 单元列表
     */
    @GetMapping("/units")
    public Result<ListDTO<UnitDTO>> getUnits(@RequestParam Long buildingId) {
        return Result.ok(new ListDTO<>(commonService.getUnitsByBuildingId(buildingId)));
    }

    /**
     * 获取指定单元下的全部房间。
     *
     * @param unitId 单元 ID
     * @return 房间列表
     */
    @GetMapping("/rooms")
    public Result<ListDTO<RoomDTO>> getRooms(@RequestParam Long unitId) {
        return Result.ok(new ListDTO<>(commonService.getRoomsByUnitId(unitId)));
    }

    /**
     * 上传图片文件。
     *
     * @param file 图片文件（支持 jpg/png/gif/webp）
     * @return 上传后的文件访问 URL
     */
    @PostMapping("/upload")
    public Result<UploadResponseDTO> upload(@RequestParam("file") MultipartFile file) {
        String url = commonService.uploadFile(file);
        return Result.ok(new UploadResponseDTO(url));
    }

    /**
     * 上传语音文件 — 仅接受 mp3 / wav / aac / m4a 音频格式。
     *
     * @param file 音频文件
     * @return 上传后的文件访问 URL
     */
    @PostMapping("/upload-voice")
    public Result<UploadResponseDTO> uploadVoice(@RequestParam("file") MultipartFile file) {
        String url = commonService.uploadVoice(file);
        return Result.ok(new UploadResponseDTO(url));
    }

    /**
     * AI 文案优化 — 根据场景模式调用大模型生成/润色文本。
     *
     * <p>当前支持 mode=feedback（互助感想智能生成），
     * 根据角色、物品标题和补充背景生成口语化评价。</p>
     *
     * @param request 包含 mode、role、itemTitle、description
     * @return AI 生成的文本
     */
    @PostMapping("/polish")
    public Result<PolishResponse> polish(@Valid @RequestBody PolishRequest request) {
        if ("feedback".equals(request.getMode())) {
            String feedback = polishingClient.generateFeedback(
                    request.getRole(),
                    request.getItemTitle(),
                    request.getDescription()
            );
            return Result.ok(new PolishResponse(feedback));
        }
        return Result.error(400, "未知的 mode: " + request.getMode());
    }
}
