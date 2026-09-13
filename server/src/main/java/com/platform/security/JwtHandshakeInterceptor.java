package com.platform.security;

import com.platform.common.BizStatus;
import com.platform.common.UserType;
import com.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器 — 校验 URL query 中的 token 参数。
 * 身份以 token 为准（不信任客户端传的 userId）；C端用户额外校验 token_version（单会话登录）。
 * 通过后将 userId 写入 session attributes 供 ChatWebSocketHandler 使用。
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    private final JwtTokenProvider jwtProvider;
    private final UserRepository userRepository;

    public JwtHandshakeInterceptor(JwtTokenProvider jwtProvider, UserRepository userRepository) {
        this.jwtProvider = jwtProvider;
        this.userRepository = userRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractQueryParam(request.getURI().getQuery(), "token");
        if (token == null || !jwtProvider.validate(token)) {
            log.warn("WS handshake rejected: missing or invalid token — {}", jwtProvider.getLastError());
            return false;
        }

        String userId = jwtProvider.getUserId(token);
        String userType = jwtProvider.getUserType(token);

        // C端用户校验 token_version（单会话登录）；B端管理员 token 不带 ver，跳过
        if (!UserType.ADMIN.equals(userType) && !UserType.SUPER_ADMIN.equals(userType)) {
            Integer ver = jwtProvider.getTokenVersion(token);
            UserRepository.AuthProbe probe = null;
            try { probe = userRepository.findAuthProbeById(Long.valueOf(userId)); }
            catch (NumberFormatException ignored) { log.debug("WS握手：解析userId格式失败 userId={}", userId, ignored); }
            if (ver == null || probe == null || !ver.equals(probe.getTokenVersion())) {
                log.info("WS handshake rejected: stale token for user {} — tokenVer={}, currentVer={}",
                        userId, ver, probe == null ? null : probe.getTokenVersion());
                return false;
            }
            // 未审核用户不允许建立聊天 WS 连接
            if (!BizStatus.APPROVED.equals(probe.getAuthStatus())) {
                log.info("WS handshake rejected: unapproved user {} — authStatus={}", userId, probe.getAuthStatus());
                return false;
            }
        }

        attributes.put("userId", userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    /** 从 query string 中提取参数值（URL decode），不存在返回 null */
    private String extractQueryParam(String query, String name) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0 && name.equals(param.substring(0, eq))) {
                return URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
