package com.javaclaw.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * OAuth 2.1 Authorization Code + PKCE 的脱敏状态。
 *
 * @param id 授权流程标识
 * @param endpointId MCP Endpoint
 * @param endpointRevision 启动时的精确端点版本
 * @param authorizationHost 授权服务主机名；不含 URL、query、state 或 PKCE challenge
 * @param state 当前状态
 * @param expiresAt 回调截止时间
 * @param detail 稳定、非内容型状态码
 * @param updatedAt 状态更新时间
 */
public record McpOAuthAuthorization(
        String id,
        String endpointId,
        long endpointRevision,
        String authorizationHost,
        McpOAuthState state,
        Instant expiresAt,
        Optional<String> detail,
        Instant updatedAt) {
    /** 校验脱敏状态。 */
    public McpOAuthAuthorization {
        id = Preconditions.identifier(id, "id");
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        endpointRevision = Preconditions.positive(endpointRevision, "endpointRevision");
        authorizationHost = host(authorizationHost);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiresAt, "expiresAt");
        detail = Objects.requireNonNull(detail, "detail").map(McpOAuthAuthorization::errorCode);
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String host(String value) {
        String host = Preconditions.text(value, "authorizationHost").toLowerCase(Locale.ROOT);
        if (host.length() > 253
                || !host.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("authorizationHost must be a DNS host");
        }
        return host;
    }

    private static String errorCode(String value) {
        String code = Preconditions.text(value, "detail");
        if (!code.matches("[A-Z][A-Z0-9_]{0,95}")) {
            throw new IllegalArgumentException("detail must be a stable error code");
        }
        return code;
    }
}
