package com.platform.service;

import com.platform.common.BizException;
import com.platform.model.dto.BuildingDTO;
import com.platform.model.dto.RoomDTO;
import com.platform.model.dto.TenantDTO;
import com.platform.model.dto.UnitDTO;
import com.platform.repository.BuildingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.TenantRepository;
import com.platform.repository.UnitRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CommonService {

    private final TenantRepository tenantRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final RoomRepository roomRepository;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    public CommonService(TenantRepository tenantRepository,
                         BuildingRepository buildingRepository,
                         UnitRepository unitRepository,
                         RoomRepository roomRepository) {
        this.tenantRepository = tenantRepository;
        this.buildingRepository = buildingRepository;
        this.unitRepository = unitRepository;
        this.roomRepository = roomRepository;
    }

    public List<TenantDTO> getAllTenants() {
        return tenantRepository.findAll().stream()
                .map(TenantDTO::from)
                .toList();
    }

    public List<BuildingDTO> getBuildingsByTenantId(Long tenantId) {
        return buildingRepository.findByTenantId(tenantId).stream()
                .map(BuildingDTO::from)
                .collect(Collectors.toList());
    }

    public List<UnitDTO> getUnitsByBuildingId(Long buildingId) {
        return unitRepository.findByBuildingId(buildingId).stream()
                .map(UnitDTO::from)
                .collect(Collectors.toList());
    }

    public List<RoomDTO> getRoomsByUnitId(Long unitId) {
        return roomRepository.findByUnitId(unitId).stream()
                .map(RoomDTO::from)
                .collect(Collectors.toList());
    }

    public String uploadFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException("文件为空");
        }

        // 上传端点匿名可访问（注册流程需上传证件照）且 /uploads/** 被静态托管，
        // 必须挡住 html/svg 等可被浏览器执行的类型，防止存储型 XSS。
        // 不可信任客户端文件名与 Content-Type：wx.uploadFile 在真机上的临时文件常无扩展名、
        // Content-Type 可能为 application/octet-stream（曾导致注册上传证件照被误拒）。
        // 改为读取文件头魔数识别真实类型，存盘扩展名由服务端按魔数生成，恶意类型无法落盘。
        String ext = detectImageExtension(file);
        if (ext == null) {
            throw new BizException("仅支持上传 jpg/jpeg/png/gif/webp 图片");
        }

        try {
            Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);

            String filename = java.util.UUID.randomUUID().toString() + ext;
            Path targetPath = uploadPath.resolve(filename);
            file.transferTo(targetPath.toFile());

            return "/uploads/" + filename;
        } catch (IOException e) {
            throw new BizException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 通过文件头魔数识别图片真实类型，返回对应扩展名；无法识别返回 null。
     * jpg: FF D8 FF / png: 89 'P' 'N' 'G' / gif: "GIF8" / webp: "RIFF"....."WEBP"
     */
    private String detectImageExtension(MultipartFile file) {
        byte[] h = new byte[12];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            throw new BizException("文件读取失败");
        }
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (n >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return ".png";
        }
        if (n >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
            return ".gif";
        }
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    /** 上传语音文件，仅接受 mp3/wav/aac/m4a 格式 */
    public String uploadVoice(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException("文件为空");
        }
        String ext = detectAudioExtension(file);
        if (ext == null) {
            throw new BizException("仅支持上传 mp3/wav/aac/m4a 音频格式");
        }
        try {
            Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);
            String filename = java.util.UUID.randomUUID().toString() + ext;
            Path targetPath = uploadPath.resolve(filename);
            file.transferTo(targetPath.toFile());
            return "/uploads/" + filename;
        } catch (IOException e) {
            throw new BizException("文件上传失败: " + e.getMessage());
        }
    }

    /**
     * 通过文件头魔数识别音频真实类型，返回对应扩展名；无法识别返回 null。
     * mp3: FF FB / FF F3 / FF F2 (MPEG) | 49 44 33 (ID3v2)
     * wav: "RIFF" ... "WAVE"
     * aac: FF F1 / FF F9 (ADTS)
     * m4a: "....ftyp" (MP4 container)
     */
    private String detectAudioExtension(MultipartFile file) {
        byte[] h = new byte[12];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            throw new BizException("文件读取失败");
        }
        // MP3: ID3v2 tag header
        if (n >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
            return ".mp3";
        }
        // MP3: MPEG frame sync (11 bits = 0x7FF)
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && ((h[1] & 0xFF) & 0xE0) == 0xE0) {
            return ".mp3";
        }
        // WAV: "RIFF" ... "WAVE"
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') {
            return ".wav";
        }
        // AAC: ADTS header (FF F1 or FF F9)
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && ((h[1] & 0xFF) == 0xF1 || (h[1] & 0xFF) == 0xF9)) {
            return ".aac";
        }
        // M4A: MP4 container — "....ftyp" at offset 4
        if (n >= 11 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            return ".m4a";
        }
        return null;
    }
}
