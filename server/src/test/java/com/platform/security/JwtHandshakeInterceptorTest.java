package com.platform.security;

import com.platform.common.UserType;
import com.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtHandshakeInterceptor 单元测试")
class JwtHandshakeInterceptorTest {

    @Mock
    private JwtTokenProvider jwtProvider;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ServerHttpRequest request;
    @Mock
    private ServerHttpResponse response;
    @Mock
    private WebSocketHandler wsHandler;

    @InjectMocks
    private JwtHandshakeInterceptor interceptor;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String INVALID_TOKEN = "invalid.jwt.token";
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        attributes = new HashMap<>();
    }

    // ==================== 有效 token 放行 ====================

    @Test
    @DisplayName("有效 token 放行并写入 userId 到 attributes")
    void should_allowHandshake_when_validToken() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.OWNER);
        // C端用户需要 tokenVersion 与探针校验通过
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);
        UserRepository.AuthProbe probe = mockProbe(1, "approved");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("userId", "1");
    }

    @Test
    @DisplayName("admin 用户不校验 token_version 直接放行")
    void should_allowAdminHandshake_withoutTokenVersion() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.ADMIN);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言：不调用 findAuthProbeById
        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("userId", "1");
        verify(userRepository, never()).findAuthProbeById(anyLong());
    }

    @Test
    @DisplayName("super_admin 用户不校验 token_version 直接放行")
    void should_allowSuperAdminHandshake_withoutTokenVersion() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.SUPER_ADMIN);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isTrue();
        verify(userRepository, never()).findAuthProbeById(anyLong());
    }

    // ==================== 缺 token / 无效 token ====================

    @Test
    @DisplayName("query 中无 token 参数时拒绝握手")
    void should_rejectHandshake_when_noToken() {
        // 准备
        givenQuery("userId=1&room=101"); // 没有 token 参数

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isFalse();
        assertThat(attributes).doesNotContainKey("userId");
    }

    @Test
    @DisplayName("query 为 null 时拒绝握手")
    void should_rejectHandshake_when_nullQuery() {
        // 准备
        when(request.getURI()).thenReturn(URI.create("ws://host/chat"));

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("无效 token 时拒绝握手")
    void should_rejectHandshake_when_invalidToken() {
        // 准备
        givenQueryWithToken(INVALID_TOKEN);
        when(jwtProvider.validate(INVALID_TOKEN)).thenReturn(false);
        when(jwtProvider.getLastError()).thenReturn("MalformedJwtException: ...");

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isFalse();
    }

    // ==================== C端 token_version 校验 ====================

    @Test
    @DisplayName("token_version 不一致时拒绝握手")
    void should_rejectHandshake_when_staleToken() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.OWNER);
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(2, "approved");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("token 无 ver 但用户是普通角色时视为 token 过期")
    void should_rejectHandshake_when_noVersionOnOrdinaryUser() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.TENANT);
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(null); // 无 ver claim

        UserRepository.AuthProbe probe = mockProbe(1, "approved");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言：ver 为 null → 拒绝
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("probe 为 null（用户已删除）时拒绝握手")
    void should_rejectHandshake_when_userDeleted() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.OWNER);
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);
        when(userRepository.findAuthProbeById(1L)).thenReturn(null);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isFalse();
    }

    // ==================== 未审核用户拒绝 ====================

    @Test
    @DisplayName("未审核用户拒绝握手")
    void should_rejectHandshake_when_unapprovedUser() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.OWNER);
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(1, "pending");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("已封禁用户拒绝握手")
    void should_rejectHandshake_when_bannedUser() {
        // 准备
        givenQueryWithToken(VALID_TOKEN);
        when(jwtProvider.validate(VALID_TOKEN)).thenReturn(true);
        when(jwtProvider.getUserId(VALID_TOKEN)).thenReturn("1");
        when(jwtProvider.getUserType(VALID_TOKEN)).thenReturn(UserType.OWNER);
        when(jwtProvider.getTokenVersion(VALID_TOKEN)).thenReturn(1);

        UserRepository.AuthProbe probe = mockProbe(1, "banned");
        when(userRepository.findAuthProbeById(1L)).thenReturn(probe);

        // 执行
        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        // 断言
        assertThat(result).isFalse();
    }

    // ==================== afterHandshake ====================

    @Test
    @DisplayName("afterHandshake 不抛异常")
    void should_notThrow_when_afterHandshake() {
        // 执行 & 断言 — afterHandshake 为 no-op，不应抛异常
        interceptor.afterHandshake(request, response, wsHandler, null);
    }

    // ==================== 辅助方法 ====================

    private void givenQueryWithToken(String token) {
        givenQuery("token=" + token);
    }

    private void givenQuery(String query) {
        when(request.getURI()).thenReturn(URI.create("ws://host/chat?" + query));
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
