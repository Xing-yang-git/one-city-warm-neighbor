package com.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.AppTimeZone;
import com.platform.common.BizException;
import com.platform.common.AuthStatus;
import com.platform.common.UserFormatter;
import com.platform.common.UserType;
import com.platform.model.dto.AuthResponseDTO;
import com.platform.model.dto.AuthStatusDTO;
import com.platform.model.dto.LoginRequest;
import com.platform.model.dto.OperationResultDTO;
import com.platform.model.dto.PhoneLoginRequest;
import com.platform.model.dto.RegisterRequest;
import com.platform.model.dto.UserDTO;
import com.platform.model.dto.WxLoginRequest;
import com.platform.model.entity.Building;
import com.platform.model.entity.Room;
import com.platform.model.entity.Unit;
import com.platform.model.entity.User;
import com.platform.repository.BuildingRepository;
import com.platform.repository.RoomRepository;
import com.platform.repository.TenantRepository;
import com.platform.repository.UnitRepository;
import com.platform.repository.UserRepository;
import com.platform.security.JwtTokenProvider;
import com.platform.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final TenantRepository tenantRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final WeChatService weChatService;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public AuthService(UserRepository userRepository,
                       RoomRepository roomRepository,
                       BuildingRepository buildingRepository,
                       UnitRepository unitRepository,
                       TenantRepository tenantRepository,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       WeChatService weChatService,
                       ChatWebSocketHandler chatWebSocketHandler) {
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.buildingRepository = buildingRepository;
        this.unitRepository = unitRepository;
        this.tenantRepository = tenantRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.weChatService = weChatService;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    /** C端发新令牌：版本+1 使其他设备旧 token 失效，并主动踢掉其在线 WS 连接 */
    private String issueUserToken(User user) {
        int newVer = (user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1;
        user.setTokenVersion(newVer);
        userRepository.save(user);
        chatWebSocketHandler.closeUserSessions(user.getId().toString());
        return jwtTokenProvider.generateToken(user.getId().toString(), user.getUserType(), newVer);
    }

    @Transactional
    public AuthResponseDTO wxLogin(WxLoginRequest req) {
        // 真机: code → 微信 API → 稳定 openid;  本地开发: 未配 AppID 时 code 即 openid
        String openid = weChatService.code2Session(req.getCode());
        User user = userRepository.findByOpenid(openid).orElse(null);
        boolean needRegister = false;

        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setName(req.getName() != null ? req.getName() : "微信用户");
            user.setUserType(UserType.OWNER);
            user.setAuthStatus(AuthStatus.REGISTERING);
            user.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
            user = userRepository.save(user);
            needRegister = true;
        } else if (AuthStatus.PENDING.equals(user.getAuthStatus()) && user.getRoom() == null) {
            // 历史数据修复：用户被创建为 pending 状态但从未完成注册
            user.setAuthStatus(AuthStatus.REGISTERING);
            user = userRepository.save(user);
            needRegister = true;
        } else if (user.getPhone() != null && !user.getPhone().isEmpty()) {
            // 用户已用手机号完成注册——不再跳转注册页
            // 除非其明确处于 'registering' 状态
            if (!AuthStatus.REGISTERING.equals(user.getAuthStatus())) {
                needRegister = false;
            }
        }

        String token = issueUserToken(user);
        return AuthResponseDTO.builder()
                .token(token)
                .user(toDTO(user))
                .needRegister(needRegister ? Boolean.TRUE : null)
                .build();
    }

    public AuthResponseDTO adminLogin(LoginRequest req) {
        User user = userRepository.findByUsername(req.getUsername())
                .orElseThrow(() -> new BizException("账号或密码错误"));

        if (!UserType.ADMIN.equals(user.getUserType())
                && !UserType.SENIOR_ADMIN.equals(user.getUserType())
                && !UserType.SUPER_ADMIN.equals(user.getUserType())) {
            throw new BizException("账号或密码错误");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException("账号或密码错误");
        }

        String token = jwtTokenProvider.generateToken(user.getId().toString(), user.getUserType());
        return AuthResponseDTO.builder()
                .token(token)
                .user(toDTO(user))
                .build();
    }

    public AuthResponseDTO phoneLogin(PhoneLoginRequest req) {
        if (req.getTenantId() == null) {
            throw new BizException("请选择小区");
        }
        User user = userRepository.findByPhoneAndTenantId(req.getPhone(), req.getTenantId())
                .orElseThrow(() -> new BizException("手机号未注册"));

        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            throw new BizException("该账号未设置密码，请使用微信登录");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException("密码错误");
        }

        if (AuthStatus.BANNED.equals(user.getAuthStatus())) {
            String reason = user.getBannedReason() != null ? user.getBannedReason() : "如有疑问请联系物业";
            throw new BizException("账号已被封禁：" + reason);
        }

        String token = issueUserToken(user);
        return AuthResponseDTO.builder()
                .token(token)
                .user(toDTO(user))
                .build();
    }

    @Transactional
    /**
     * 用户注册：校验身份白名单 → 校验唯一性 → 创建/更新用户 → 签发 token。
     *
     * @param req    注册信息
     * @param userId 已有用户 ID（wxLogin 预创建），为 null 时新建用户
     * @return 注册结果（含新签发的 token）
     */
    public AuthResponseDTO register(RegisterRequest req, Long userId) {
        // 身份白名单前置校验：非法身份在 resolveRoom 创建楼栋/单元/房间等写操作之前即被拒绝
        String residentType = mapUserType(req.getUserType());

        Room room = resolveRoom(req.getTenantId(), req.getBuildingNo(), req.getUnitNo(), req.getRoom());
        validateUniqueness(req, userId, room, residentType);

        User user = getOrCreateUser(userId, req);
        fillUserProfile(user, req, room, residentType);
        user = userRepository.save(user);

        // 注册时不调用 issueUserToken：它会使其他设备的旧 token 失效并关闭全部 WS 连接（code 4001），
        // 客户端收到 4001 后会触发 forceRelogin（"账号已在其他设备登录"）并跳转登录页。
        // 注册不是"换设备登录"，不应踢掉当前设备的 WS；直接签发 token，tokenVersion 保持不变即可。
        String token = jwtTokenProvider.generateToken(
                user.getId().toString(), user.getUserType(), user.getTokenVersion());
        return AuthResponseDTO.builder()
                .token(token)
                .user(toDTO(user))
                .build();
    }

    /**
     * 校验手机号与房间/身份的注册唯一性。
     *
     * @param req          注册请求
     * @param userId       当前用户 ID，可为 null（未经过 wxLogin 的公开注册）
     * @param room         已解析的房间
     * @param residentType 已通过白名单校验的身份存储值，null 表示尚未选择身份
     */
    private void validateUniqueness(RegisterRequest req, Long userId, Room room, String residentType) {
        if (req.getPhone() == null || req.getPhone().isEmpty() || req.getTenantId() == null) {
            return;
        }
        // 手机号是否已在本小区注册？
        userRepository.findByPhoneAndTenantId(req.getPhone(), req.getTenantId())
                .ifPresent(existing -> {
                    if (userId != null && existing.getId().equals(userId)) return;
                    throw new BizException("该手机号已在本小区注册");
                });
        // 同一房间同一身份只能注册一个住户
        if (residentType == null || residentType.isEmpty()) {
            throw new BizException("请选择住户身份");
        }
        userRepository.findByRoomIdAndUserType(room.getId(), residentType)
                .ifPresent(existing -> {
                    if (userId != null && existing.getId().equals(userId)) return;
                    // 面向用户的提示用中文标签，避免出现"该房间已有owner注册"
                    throw new BizException("该房间已有" + UserFormatter.getUserTypeLabel(residentType) + "注册");
                });
    }

    /**
     * 获取已有用户或创建新用户。
     * 关键防护：当已有用户已设置手机号且与请求手机号不一致时，
     * 说明同设备上残留了上一个用户的 token，必须新建用户，
     * 否则会覆写旧用户数据导致其记录丢失。
     */
    private User getOrCreateUser(Long userId, RegisterRequest req) {
        if (userId != null) {
            User existing = userRepository.findById(userId)
                    .orElseThrow(() -> new BizException("用户不存在"));
            // 已有用户无手机号（wxLogin 预创建）或手机号匹配 → 同一用户补充注册
            if (existing.getPhone() == null || existing.getPhone().isEmpty()
                    || existing.getPhone().equals(req.getPhone())) {
                return existing;
            }
            // 手机号不匹配 → 残留旧 token，新建用户
        }
        User user = new User();
        user.setCreatedAt(LocalDateTime.now(AppTimeZone.APP_ZONE));
        return user;
    }

    /**
     * 填充用户基本资料字段：姓名、手机、密码、身份、证件图片、房间。
     *
     * @param user         目标用户
     * @param req          注册请求
     * @param room         已解析的房间
     * @param residentType 已通过白名单校验的身份存储值，null 表示尚未选择身份（保留原值）
     */
    private void fillUserProfile(User user, RegisterRequest req, Room room, String residentType) {
        user.setName(req.getName());
        user.setPhone(req.getPhone());
        user.setTenantId(req.getTenantId());

        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            validateAndSetPassword(user, req.getPassword());
        }
        if (residentType != null) {
            user.setUserType(residentType);
        }
        user.setAuthStatus(AuthStatus.PENDING);
        user.setRejectReason(null);

        if (req.getDocImages() != null && !req.getDocImages().isEmpty()) {
            try {
                user.setDocImages(OBJECT_MAPPER.writeValueAsString(req.getDocImages()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize docImages: {}", e.getMessage());
            }
        }

        user.setRoomId(room.getId());
        user.setRoom(room);
    }

    /**
     * 校验密码强度并设置加密后的密码哈希。
     */
    private void validateAndSetPassword(User user, String password) {
        if (password.length() < 8 || password.length() > 20) {
            throw new BizException("密码长度需要8-20位");
        }
        if (!password.matches(".*[a-zA-Z].*") || !password.matches(".*[0-9].*")) {
            throw new BizException("密码需要包含字母和数字");
        }
        user.setPasswordHash(passwordEncoder.encode(password));
    }

    /**
     * 将前端住户身份编码严格映射为数据库存储值：仅接受业主与租客。
     *
     * <p>白名单而非透传是必需的——注册接口公开且允许无凭证调用，
     * 若把未知值原样写入 user_type，提交 admin/super_admin 即可签发管理员身份的 token
     * （数据库 CHECK 约束恰好允许这些值），进而访问管理端接口。</p>
     *
     * @param raw 前端提交的身份编码（owner / tenant），null 表示尚未选择身份
     * @return 住户身份存储值（{@link UserType#OWNER} / {@link UserType#TENANT}）；未选择身份时返回 null
     * @throws BizException 编码不在白名单内
     */
    private String mapUserType(String raw) {
        if (raw == null) return null;
        return switch (raw) {
            case UserType.OWNER  -> UserType.OWNER;
            case UserType.TENANT -> UserType.TENANT;
            default -> throw new BizException("非法的用户类型");
        };
    }

    private Room resolveRoom(Long tenantId, Integer buildingNo, Integer unitNo, String roomText) {
        if (tenantId == null || buildingNo == null || unitNo == null || roomText == null) {
            throw new BizException("请完整填写小区、栋号、单元号和房号");
        }

        // 查找或创建楼栋（按数值楼栋号，避免字符串拼接产生"1栋栋"类脏数据）
        Building building = buildingRepository.findByTenantIdAndBuildingNo(tenantId, buildingNo)
                .orElseGet(() -> buildingRepository.save(
                        Building.builder()
                                .tenantId(tenantId)
                                .buildingNo(buildingNo)
                                .build()));

        // 查找或创建单元（按数值单元号）
        Unit unit = unitRepository.findByBuildingIdAndUnitNo(building.getId(), unitNo)
                .orElseGet(() -> unitRepository.save(
                        Unit.builder()
                                .buildingId(building.getId())
                                .unitNo(unitNo)
                                .build()));

        // 查找或创建房间
        return roomRepository.findByUnitIdAndRoomNumber(unit.getId(), roomText)
                .orElseGet(() -> roomRepository.save(
                        Room.builder()
                                .unitId(unit.getId())
                                .roomNumber(roomText)
                                .build()));
    }

    public AuthStatusDTO getAuthStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        return AuthStatusDTO.builder()
                .authStatus(user.getAuthStatus())
                .rejectReason(user.getRejectReason())
                .bannedReason(user.getBannedReason())
                .build();
    }

    @Transactional
    public OperationResultDTO appeal(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        if (!AuthStatus.BANNED.equals(user.getAuthStatus())) {
            throw new BizException("当前状态不支持申诉");
        }

        user.setAuthStatus(AuthStatus.PENDING);
        user.setRejectReason(null);
        userRepository.save(user);

        return OperationResultDTO.builder()
                .success(true)
                .message("申诉已提交，请等待物业审核")
                .build();
    }

    private UserDTO toDTO(User user) {
        List<String> docImages = Collections.emptyList();
        if (user.getDocImages() != null && !user.getDocImages().isEmpty()) {
            try {
                docImages = OBJECT_MAPPER.readValue(user.getDocImages(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.debug("Failed to parse docImages JSON: {}", e.getMessage());
            }
        }

        return UserDTO.builder()
                .id(user.getId())
                .openid(user.getOpenid())
                .username(user.getUsername())
                .name(user.getName())
                .phone(user.getPhone())
                .userType(user.getUserType())
                .authStatus(user.getAuthStatus())
                .roomId(user.getRoom() != null ? user.getRoom().getId() : null)
                .tenantId(user.getTenantId())
                .userRoom(UserFormatter.formatRoomWithType(user))
                .tenantName(resolveTenantName(user))
                .docImages(docImages)
                .rejectReason(user.getRejectReason())
                .bannedReason(user.getBannedReason())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String resolveTenantName(User user) {
        try {
            if (user.getRoom() != null
                    && user.getRoom().getUnit() != null
                    && user.getRoom().getUnit().getBuilding() != null) {
                Long tenantId = user.getRoom().getUnit().getBuilding().getTenantId();
                if (tenantId != null) {
                    return tenantRepository.findById(tenantId)
                            .map(t -> t.getName())
                            .orElse("");
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve tenant name: {}", e.getMessage());
        }
        return "";
    }
}
