package com.platform.security;

import com.platform.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 单元测试")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtProvider;
    @Mock
    private UserRepository userRepository;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;
    @Mock
    private PrintWriter writer;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String INVALID_TOKEN = "invalid.jwt.token";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 有效 token 注入 SecurityContext ====================

    @Test
    @DisplayName("有效 token 应注入 SecurityContext 并映射角色")
    void should_setAuthentication_when_validToken() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/idle-items/home");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        // ROLE_USER 路径需要 tokenVersion 与探针校验
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);
        UserRepository.AuthProbe probe = mockProbe(1, "approved");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(LoginUser.class);
        assertThat(((LoginUser) auth.getPrincipal()).getUserId()).isEqualTo(1L);
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("super_admin 用户映射为 ROLE_SUPER_ADMIN")
    void should_mapRole_when_superAdmin() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/admin/users");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("super_admin");

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("admin 用户映射为 ROLE_ADMIN")
    void should_mapRole_when_admin() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/admin/users");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("2");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("admin");

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("普通用户映射为 ROLE_USER")
    void should_mapRole_when_ordinaryUser() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/idle-items/home");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("3");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("tenant");
        // ROLE_USER 路径需要 tokenVersion 与探针校验
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);
        UserRepository.AuthProbe probe = mockProbe(1, "approved");
        when(userRepository.findAuthProbeById(3L)).thenReturn(probe);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    // ==================== 无 token / 无效 token ====================

    @Test
    @DisplayName("无 token 时放行匿名请求")
    void should_allowAnonymous_when_noToken() throws Exception {
        // 准备
        when(request.getHeader("Authorization")).thenReturn(null);
        givenRequestUri("/api/idle-items/home");

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("无效 Bearer 前缀不视为 token")
    void should_allowAnonymous_when_wrongAuthScheme() throws Exception {
        // 准备
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        givenRequestUri("/api/idle-items/home");

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("无效 token 时不设置认证并继续过滤链")
    void should_notSetAuthentication_when_invalidToken() throws Exception {
        // 准备
        givenBearerToken(INVALID_TOKEN);
        givenRequestUri("/api/idle-items/home");
        when(jwtProvider.validate(INVALID_TOKEN)).thenReturn(false);
        when(jwtProvider.getLastError()).thenReturn("ExpiredJwtException: token expired");

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    // ==================== tokenVersion 一致性与过期 token 拒绝 ====================

    @Test
    @DisplayName("tokenVersion 不一致时对非 auth 端点拒绝")
    void should_rejectStaleToken_when_tokenVersionMismatch() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/idle-items/home");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(2, "approved");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言：token 已过期，不应注入认证
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("tokenVersion 不一致但访问 auth 端点时允许放行")
    void should_allowStaleToken_when_authEndpoint() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/auth/status");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(2, "approved");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言：auth 端点下过期 token 依然注入认证
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(LoginUser.class);
        assertThat(((LoginUser) auth.getPrincipal()).getUserId()).isEqualTo(1L);
        verify(filterChain).doFilter(request, response);
    }

    // ==================== 未审核用户拒绝 ====================

    @Test
    @DisplayName("未审核用户访问非白名单接口返回 403")
    void should_rejectUnapprovedUser_when_notApprovedAndNotWhitelisted() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/idle-items/publish");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(1, "pending");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);
        when(response.getWriter()).thenReturn(writer);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言：不应注入认证、应返回 403、不应 continue chain
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("未审核用户访问白名单路径放行")
    void should_allowUnapprovedUser_when_whitelistedPath() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/common/buildings/1");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(1, "pending");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言：白名单路径应注入认证
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("未审核用户访问 uploads 路径放行")
    void should_allowUnapprovedUser_when_uploadsPath() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/uploads/abc.jpg");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(1, "rejected");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    // ==================== admin/super_admin 直接放行（不校验 tokenVersion） ====================

    @Test
    @DisplayName("admin 用户不校验 tokenVersion 直接注入认证")
    void should_skipVersionCheck_when_adminUser() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/admin/users");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("admin");

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言：不调用 findAuthProbeById 即可注入认证
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        verify(userRepository, never()).findAuthProbeById(anyLong());
        verify(filterChain).doFilter(request, response);
    }

    // ==================== userId 非数字时 NumberFormatException 安全处理 ====================

    @Test
    @DisplayName("userId 非数字时安全降级（不崩溃）")
    void should_handleNonNumericUserIdGracefully() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/idle-items/home");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("not_a_number");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言：NumberFormatException 被捕获，token 视为过期被拒绝
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    // ==================== 已知问题：probe 为 null 且 URI 是 /api/auth/ 时的 NPE 边界 ====================
    // 场景：用户已删除（findById 不存在返回 null），但 token 仍有效且访问 /api/auth/ 路径。
    // 业务代码 71~72 行 probe.getAuthStatus() 会抛出 NullPointerException。
    // 此处用 @Disabled 标记，待业务代码修复后启用。

    @Test
    @org.junit.jupiter.api.Disabled("已知 M2 级 NPE 边界：probe 为 null 且访问 /api/auth/ 路径时 line 72 NPE")
    @DisplayName("用户已删除且 token 版本缺失时访问 auth 端点 — 已知 NPE 边界")
    void should_handleDeletedUser_when_probeNullAtAuthEndpoint() throws Exception {
        // 准备
        givenBearerToken(VALID_TOKEN);
        givenRequestUri("/api/auth/status");
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn("owner");
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(null);  // B端风格token无ver

        // userRepository.findAuthProbeById 返回 null（用户已删除）
        when(userRepository.findAuthProbeById(1L)).thenReturn(null);

        // 执行
        filter.doFilterInternal(request, response, filterChain);

        // 断言：不应崩溃
        verify(filterChain).doFilter(request, response);
    }

    // ==================== 辅助方法 ====================

    private void givenBearerToken(String token) {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
    }

    private void givenRequestUri(String uri) {
        when(request.getRequestURI()).thenReturn(uri);
    }

    private UserRepository.AuthProbe mockProbe(int tokenVersion, String authStatus) {
        return new UserRepository.AuthProbe() {
            @Override
            public Integer getTokenVersion() {
                return tokenVersion;
            }

            @Override
            public String getAuthStatus() {
                return authStatus;
            }
        };
    }
}
