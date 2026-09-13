package com.platform.service;

import com.platform.model.dto.AuthResponseDTO;
import com.platform.model.dto.AuthStatusDTO;
import com.platform.model.dto.LoginRequest;
import com.platform.model.dto.OperationResultDTO;
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
import com.platform.common.BizStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 单元测试")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private BuildingRepository buildingRepository;
    @Mock
    private UnitRepository unitRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private WeChatService weChatService;
    @Mock
    private ChatWebSocketHandler chatWebSocketHandler;

    @InjectMocks
    private AuthService authService;

    private Long userId;
    private User user;
    private String openid;
    private String token;

    @BeforeEach
    void setUp() {
        userId = 1L;
        openid = "wx_openid_test";
        token = "mock.jwt.token";

        user = User.builder()
                .id(userId)
                .openid(openid)
                .name("测试用户")
                .userType("业主")
                .authStatus("approved")
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }

    // ==================== wxLogin ====================

    @Test
    @DisplayName("微信登录 - 新用户自动注册并返回token和needRegister")
    void should_registerNewUser_when_wxLoginWithNewOpenid() {
        // 准备
        WxLoginRequest req = new WxLoginRequest();
        req.setCode(openid);
        req.setName("新用户");

        // 本地开发模式下 code 即 openid
        when(weChatService.code2Session(openid)).thenReturn(openid);
        when(userRepository.findByOpenid(openid)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(userId);
            return u;
        });
        // 发新令牌时版本 +1，首次登录版本为 1
        when(jwtTokenProvider.generateToken(userId.toString(), "业主", 1)).thenReturn(token);

        // 执行
        AuthResponseDTO result = authService.wxLogin(req);

        // 断言
        assertThat(result.getToken()).isEqualTo(token);
        assertThat(result.getUser()).isNotNull();
        assertThat(result.getNeedRegister()).isEqualTo(true);
        // 创建用户 + issueUserToken 各保存一次
        verify(userRepository, times(2)).save(any(User.class));
    }

    @Test
    @DisplayName("微信登录 - 已注册用户直接返回token")
    void should_returnToken_when_userExists() {
        // 准备
        WxLoginRequest req = new WxLoginRequest();
        req.setCode(openid);

        when(weChatService.code2Session(openid)).thenReturn(openid);
        when(userRepository.findByOpenid(openid)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(jwtTokenProvider.generateToken(userId.toString(), "业主", 1)).thenReturn(token);

        // 执行
        AuthResponseDTO result = authService.wxLogin(req);

        // 断言
        assertThat(result.getToken()).isEqualTo(token);
        assertThat(result.getNeedRegister()).isNull();
        // issueUserToken 会更新 tokenVersion 保存一次
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("微信登录 - pending状态且无房间的用户触发重注册")
    void should_triggerReRegistration_when_pendingAndNoRoom() {
        // 准备
        user.setAuthStatus("pending");
        user.setRoom(null);
        WxLoginRequest req = new WxLoginRequest();
        req.setCode(openid);

        when(weChatService.code2Session(openid)).thenReturn(openid);
        when(userRepository.findByOpenid(openid)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(jwtTokenProvider.generateToken(userId.toString(), "业主", 1)).thenReturn(token);

        // 执行
        AuthResponseDTO result = authService.wxLogin(req);

        // 断言
        assertThat(result.getNeedRegister()).isEqualTo(true);
        assertThat(user.getAuthStatus()).isEqualTo(BizStatus.REGISTERING);
    }

    @Test
    @DisplayName("微信登录 - 用户名null时默认使用'微信用户'")
    void should_useDefaultName_when_nameIsNull() {
        // 准备
        WxLoginRequest req = new WxLoginRequest();
        req.setCode(openid);
        req.setName(null);

        when(weChatService.code2Session(openid)).thenReturn(openid);
        when(userRepository.findByOpenid(openid)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(userId);
            return u;
        });
        when(jwtTokenProvider.generateToken(userId.toString(), "业主", 1)).thenReturn(token);

        // 执行
        AuthResponseDTO result = authService.wxLogin(req);

        // 断言
        UserDTO dto = result.getUser();
        assertThat(dto.getName()).isEqualTo("微信用户");
    }

    // ==================== adminLogin ====================

    @Test
    @DisplayName("管理员登录 - 正常登录成功返回token")
    void should_adminLogin_when_validCredentials() {
        // 准备
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("admin123");

        User adminUser = User.builder()
                .id(userId)
                .username("admin")
                .passwordHash("hashed_password")
                .name("管理员")
                .userType("admin")
                .authStatus("approved")
                .build();

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("admin123", "hashed_password")).thenReturn(true);
        when(jwtTokenProvider.generateToken(userId.toString(), "admin")).thenReturn(token);

        // 执行
        AuthResponseDTO result = authService.adminLogin(req);

        // 断言
        assertThat(result.getToken()).isEqualTo(token);
        assertThat(result.getUser()).isNotNull();
    }

    @Test
    @DisplayName("管理员登录 - 用户不存在时抛出异常")
    void should_throwException_when_usernameNotFound() {
        // 准备
        LoginRequest req = new LoginRequest();
        req.setUsername("nonexistent");
        req.setPassword("password");

        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> authService.adminLogin(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("账号或密码错误");
    }

    @Test
    @DisplayName("管理员登录 - 非管理员用户登录时抛出异常")
    void should_throwException_when_notAdmin() {
        // 准备
        LoginRequest req = new LoginRequest();
        req.setUsername("user1");
        req.setPassword("password");

        User normalUser = User.builder()
                .id(userId)
                .username("user1")
                .userType("业主")
                .build();

        when(userRepository.findByUsername("user1")).thenReturn(Optional.of(normalUser));

        // 执行 & 断言
        assertThatThrownBy(() -> authService.adminLogin(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("账号或密码错误");
    }

    @Test
    @DisplayName("管理员登录 - 密码不匹配时抛出异常")
    void should_throwException_when_passwordMismatch() {
        // 准备
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("wrongpassword");

        User adminUser = User.builder()
                .id(userId)
                .username("admin")
                .passwordHash("hashed_password")
                .userType("admin")
                .build();

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("wrongpassword", "hashed_password")).thenReturn(false);

        // 执行 & 断言
        assertThatThrownBy(() -> authService.adminLogin(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("账号或密码错误");
    }

    @Test
    @DisplayName("管理员登录 - super_admin用户也可登录")
    void should_allowSuperAdminLogin() {
        // 准备
        LoginRequest req = new LoginRequest();
        req.setUsername("superadmin");
        req.setPassword("pass");

        User superAdmin = User.builder()
                .id(userId)
                .username("superadmin")
                .passwordHash("hash")
                .userType("super_admin")
                .name("超级管理员")
                .build();

        when(userRepository.findByUsername("superadmin")).thenReturn(Optional.of(superAdmin));
        when(passwordEncoder.matches("pass", "hash")).thenReturn(true);
        when(jwtTokenProvider.generateToken(userId.toString(), "super_admin")).thenReturn(token);

        // 执行
        AuthResponseDTO result = authService.adminLogin(req);

        // 断言
        assertThat(result.getToken()).isEqualTo(token);
    }

    // ==================== register ====================

    @Test
    @DisplayName("用户注册 - 正常注册并解析房间信息")
    void should_registerUser_when_validInput() {
        // 准备
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(3);
        req.setUnitNo(2);
        req.setRoom("1502");
        req.setName("张三");
        req.setPhone("13800138000");
        req.setUserType("业主");
        req.setDocImages(List.of("http://img1.jpg"));

        Building building = Building.builder().id(100L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(200L).buildingId(building.getId()).unitNo(2).build();
        Room room = Room.builder().id(300L).unitId(unit.getId()).roomNumber("1502").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 3)).thenReturn(Optional.of(building));
        when(unitRepository.findByBuildingIdAndUnitNo(building.getId(), 2)).thenReturn(Optional.of(unit));
        when(roomRepository.findByUnitIdAndRoomNumber(unit.getId(), "1502")).thenReturn(Optional.of(room));
        // 唯一性校验：手机号未被占用，该房间无同身份住户
        when(userRepository.findByPhoneAndTenantId("13800138000", tenantId)).thenReturn(Optional.empty());
        when(userRepository.findByRoomIdAndUserType(room.getId(), "业主")).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        // 注册不递增 tokenVersion（保持默认 0）
        when(jwtTokenProvider.generateToken(userId.toString(), "业主", 0)).thenReturn("mock-token");

        // 执行
        AuthResponseDTO result = authService.register(req, userId);
        UserDTO resultUser = result.getUser();

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getToken()).isEqualTo("mock-token");
        assertThat(resultUser.getName()).isEqualTo("张三");
        assertThat(user.getAuthStatus()).isEqualTo("pending");
        assertThat(user.getRoomId()).isEqualTo(room.getId());
        // 注册保存 + issueUserToken 更新版本各一次
        verify(userRepository, atLeastOnce()).save(any(User.class));
    }

    @Test
    @DisplayName("用户注册 - 同房间不同身份可并存：已有业主时租客仍可注册")
    void should_registerTenant_when_roomOnlyHasOwner() {
        // 准备：房间查重按 (房间, 身份) 维度进行，业主的存在不会命中租客的查重
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(3);
        req.setUnitNo(2);
        req.setRoom("1502");
        req.setName("李四");
        req.setPhone("13900139000");
        req.setUserType("tenant");

        Building building = Building.builder().id(100L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(200L).buildingId(building.getId()).unitNo(2).build();
        Room room = Room.builder().id(300L).unitId(unit.getId()).roomNumber("1502").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 3)).thenReturn(Optional.of(building));
        when(unitRepository.findByBuildingIdAndUnitNo(building.getId(), 2)).thenReturn(Optional.of(unit));
        when(roomRepository.findByUnitIdAndRoomNumber(unit.getId(), "1502")).thenReturn(Optional.of(room));
        when(userRepository.findByPhoneAndTenantId("13900139000", tenantId)).thenReturn(Optional.empty());
        // 该房间尚无租客——即便已有业主，同身份查重也不会命中
        when(userRepository.findByRoomIdAndUserType(room.getId(), "租客")).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        // 注册不递增 tokenVersion（保持默认 0）
        when(jwtTokenProvider.generateToken(userId.toString(), "租客", 0)).thenReturn("mock-token");

        // 执行
        AuthResponseDTO result = authService.register(req, userId);

        // 断言：注册成功，且前端编码 tenant 已映射为数据库值"租客"参与查重
        assertThat(result.getToken()).isEqualTo("mock-token");
        assertThat(user.getUserType()).isEqualTo("租客");
        verify(userRepository).findByRoomIdAndUserType(room.getId(), "租客");
    }

    @Test
    @DisplayName("用户注册 - 同房间已有业主时拦截业主注册")
    void should_throwException_when_roomAlreadyHasOwner() {
        // 准备：房间 300 已被另一位业主（id=99）注册
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(3);
        req.setUnitNo(2);
        req.setRoom("1502");
        req.setPhone("13800138000");
        req.setUserType("owner");

        Building building = Building.builder().id(100L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(200L).buildingId(building.getId()).unitNo(2).build();
        Room room = Room.builder().id(300L).unitId(unit.getId()).roomNumber("1502").build();
        User existingOwner = User.builder().id(99L).userType("业主").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 3)).thenReturn(Optional.of(building));
        when(unitRepository.findByBuildingIdAndUnitNo(building.getId(), 2)).thenReturn(Optional.of(unit));
        when(roomRepository.findByUnitIdAndRoomNumber(unit.getId(), "1502")).thenReturn(Optional.of(room));
        when(userRepository.findByPhoneAndTenantId("13800138000", tenantId)).thenReturn(Optional.empty());
        when(userRepository.findByRoomIdAndUserType(room.getId(), "业主")).thenReturn(Optional.of(existingOwner));

        // 执行 & 断言：提示需体现身份
        assertThatThrownBy(() -> authService.register(req, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该房间已有业主注册");
    }

    @Test
    @DisplayName("用户注册 - 同房间已有租客时拦截租客注册")
    void should_throwException_when_roomAlreadyHasTenant() {
        // 准备：房间 300 已被另一位租客（id=99）注册
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(3);
        req.setUnitNo(2);
        req.setRoom("1502");
        req.setPhone("13800138000");
        req.setUserType("tenant");

        Building building = Building.builder().id(100L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(200L).buildingId(building.getId()).unitNo(2).build();
        Room room = Room.builder().id(300L).unitId(unit.getId()).roomNumber("1502").build();
        User existingTenant = User.builder().id(99L).userType("租客").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 3)).thenReturn(Optional.of(building));
        when(unitRepository.findByBuildingIdAndUnitNo(building.getId(), 2)).thenReturn(Optional.of(unit));
        when(roomRepository.findByUnitIdAndRoomNumber(unit.getId(), "1502")).thenReturn(Optional.of(room));
        when(userRepository.findByPhoneAndTenantId("13800138000", tenantId)).thenReturn(Optional.empty());
        when(userRepository.findByRoomIdAndUserType(room.getId(), "租客")).thenReturn(Optional.of(existingTenant));

        // 执行 & 断言
        assertThatThrownBy(() -> authService.register(req, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该房间已有租客注册");
    }

    @Test
    @DisplayName("用户注册 - 同一用户重复提交时放行查重")
    void should_passUniquenessCheck_when_sameUserResubmits() {
        // 准备：手机号与房间查重命中的都是本人（如被驳回后重新提交），应放行
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(3);
        req.setUnitNo(2);
        req.setRoom("1502");
        req.setName("张三");
        req.setPhone("13800138000");
        req.setUserType("owner");

        Building building = Building.builder().id(100L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(200L).buildingId(building.getId()).unitNo(2).build();
        Room room = Room.builder().id(300L).unitId(unit.getId()).roomNumber("1502").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 3)).thenReturn(Optional.of(building));
        when(unitRepository.findByBuildingIdAndUnitNo(building.getId(), 2)).thenReturn(Optional.of(unit));
        when(roomRepository.findByUnitIdAndRoomNumber(unit.getId(), "1502")).thenReturn(Optional.of(room));
        // 两项查重命中的用户 id 均等于当前 userId
        when(userRepository.findByPhoneAndTenantId("13800138000", tenantId)).thenReturn(Optional.of(user));
        when(userRepository.findByRoomIdAndUserType(room.getId(), "业主")).thenReturn(Optional.of(user));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        // 注册不递增 tokenVersion（保持默认 0）
        when(jwtTokenProvider.generateToken(userId.toString(), "业主", 0)).thenReturn("mock-token");

        // 执行
        AuthResponseDTO result = authService.register(req, userId);

        // 断言：未被唯一性校验拦截
        assertThat(result.getToken()).isEqualTo("mock-token");
    }

    @Test
    @DisplayName("用户注册 - 未选择住户身份时抛出异常")
    void should_throwException_when_userTypeMissing() {
        // 准备：userType 缺失时无法按 (房间, 身份) 查重，应在校验前明确拒绝
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(3);
        req.setUnitNo(2);
        req.setRoom("1502");
        req.setPhone("13800138000");
        // 不设置 userType

        Building building = Building.builder().id(100L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(200L).buildingId(building.getId()).unitNo(2).build();
        Room room = Room.builder().id(300L).unitId(unit.getId()).roomNumber("1502").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 3)).thenReturn(Optional.of(building));
        when(unitRepository.findByBuildingIdAndUnitNo(building.getId(), 2)).thenReturn(Optional.of(unit));
        when(roomRepository.findByUnitIdAndRoomNumber(unit.getId(), "1502")).thenReturn(Optional.of(room));
        when(userRepository.findByPhoneAndTenantId("13800138000", tenantId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> authService.register(req, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("请选择住户身份");
    }

    @Test
    @DisplayName("用户注册 - 用户不存在时抛出异常")
    void should_throwException_when_userNotFound() {
        // 准备（房间信息完整但 userId 对应的用户不存在；不填手机号以跳过唯一性校验）
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(3);
        req.setUnitNo(2);
        req.setRoom("1502");

        Building building = Building.builder().id(100L).tenantId(tenantId).buildingNo(3).build();
        Unit unit = Unit.builder().id(200L).buildingId(building.getId()).unitNo(2).build();
        Room room = Room.builder().id(300L).unitId(unit.getId()).roomNumber("1502").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 3)).thenReturn(Optional.of(building));
        when(unitRepository.findByBuildingIdAndUnitNo(building.getId(), 2)).thenReturn(Optional.of(unit));
        when(roomRepository.findByUnitIdAndRoomNumber(unit.getId(), "1502")).thenReturn(Optional.of(room));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> authService.register(req, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("用户不存在");
    }

    @Test
    @DisplayName("用户注册 - 房间信息不完整时抛出异常")
    void should_throwException_when_roomInfoIncomplete() {
        // 准备（解析房间先于用户查询执行，因此无需 stub 用户查询）
        RegisterRequest req = new RegisterRequest();
        req.setBuildingNo(3);
        // tenantId、unit 和 room 为空

        // 执行 & 断言
        assertThatThrownBy(() -> authService.register(req, userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("请完整填写小区、栋号、单元号和房号");
    }

    @Test
    @DisplayName("用户注册 - 创建新的Building/Unit/Room当不存在时")
    void should_createNewBuildingUnitRoom_when_notExist() {
        // 准备
        Long tenantId = 10L;
        RegisterRequest req = new RegisterRequest();
        req.setTenantId(tenantId);
        req.setBuildingNo(5);
        req.setUnitNo(1);
        req.setRoom("101");

        Building newBuilding = Building.builder().id(101L).tenantId(tenantId).buildingNo(5).build();
        Unit newUnit = Unit.builder().id(201L).buildingId(newBuilding.getId()).unitNo(1).build();
        Room newRoom = Room.builder().id(301L).unitId(newUnit.getId()).roomNumber("101").build();

        when(buildingRepository.findByTenantIdAndBuildingNo(tenantId, 5)).thenReturn(Optional.empty());
        when(buildingRepository.save(any(Building.class))).thenReturn(newBuilding);
        when(unitRepository.findByBuildingIdAndUnitNo(newBuilding.getId(), 1)).thenReturn(Optional.empty());
        when(unitRepository.save(any(Unit.class))).thenReturn(newUnit);
        when(roomRepository.findByUnitIdAndRoomNumber(newUnit.getId(), "101")).thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenReturn(newRoom);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        // 注册不递增 tokenVersion（保持默认 0）
        when(jwtTokenProvider.generateToken(userId.toString(), "业主", 0)).thenReturn("mock-token");

        // 执行
        AuthResponseDTO result = authService.register(req, userId);

        // 断言
        assertThat(result).isNotNull();
        assertThat(result.getToken()).isEqualTo("mock-token");
        assertThat(user.getRoomId()).isEqualTo(newRoom.getId());
        verify(buildingRepository).save(any(Building.class));
        verify(unitRepository).save(any(Unit.class));
        verify(roomRepository).save(any(Room.class));
    }

    // ==================== getAuthStatus ====================

    @Test
    @DisplayName("获取认证状态 - 正常返回认证状态")
    void should_returnAuthStatus_when_userExists() {
        // 准备
        user.setAuthStatus("pending");
        user.setRejectReason(null);
        user.setBannedReason(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行
        AuthStatusDTO result = authService.getAuthStatus(userId);

        // 断言
        assertThat(result.getAuthStatus()).isEqualTo("pending");
        assertThat(result.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("获取认证状态 - 用户不存在时抛出异常")
    void should_throwException_when_userNotFoundInStatus() {
        // 准备
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // 执行 & 断言
        assertThatThrownBy(() -> authService.getAuthStatus(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("用户不存在");
    }

    // ==================== appeal ====================

    @Test
    @DisplayName("申诉 - banned用户申诉成功")
    void should_appeal_when_userIsBanned() {
        // 准备
        user.setAuthStatus("banned");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        // 执行
        OperationResultDTO result = authService.appeal(userId);

        // 断言
        assertThat(result.getSuccess()).isEqualTo(true);
        assertThat(user.getAuthStatus()).isEqualTo("pending");
        assertThat(user.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("申诉 - 非banned用户申诉时抛出异常")
    void should_throwException_when_userNotBanned() {
        // 准备
        user.setAuthStatus("approved");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // 执行 & 断言
        assertThatThrownBy(() -> authService.appeal(userId))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("当前状态不支持申诉");
    }
}
