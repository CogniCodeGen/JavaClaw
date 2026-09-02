package com.javaclaw.server.mcp;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpOAuthState;

/**
 * App Server 内部持久化的 OAuth 会话。
 *
 * <p>完整授权 URI 含 state/challenge，CredentialRef 指向 PKCE 临时材料；两者只允许留在 H2、Vault 和隔离 Browser 私有管道，禁止编码进 RPC、日志、Artifact 或
 * Desktop 状态。
 */
record McpOAuthSession(
        String id,
        String endpointId,
        long endpointRevision,
        URI authorizationUri,
        CredentialRef credential,
        McpOAuthState state,
        Instant expiresAt,
        Optional<String> detail,
        Instant updatedAt) {
    McpOAuthSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(endpointId, "endpointId");
        if (endpointRevision < 1) {
            throw new IllegalArgumentException("endpointRevision must be positive");
        }
        URI uri = Objects.requireNonNull(authorizationUri, "authorizationUri").normalize();
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("authorizationUri must be an exact HTTPS URI");
        }
        authorizationUri = uri;
        credential = Objects.requireNonNull(credential, "credential");
        if (!"oauth".equals(credential.namespace())) {
            throw new IllegalArgumentException("OAuth temporary credential must use oauth namespace");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiresAt, "expiresAt");
        detail = Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    McpOAuthAuthorization projection() {
        return new McpOAuthAuthorization(
                id,
                endpointId,
                endpointRevision,
                authorizationUri.getHost().toLowerCase(Locale.ROOT),
                state,
                expiresAt,
                detail,
                updatedAt);
    }

    McpOAuthSession transition(McpOAuthState next, Optional<String> nextDetail, Instant now) {
        return new McpOAuthSession(
                id, endpointId, endpointRevision, authorizationUri, credential, next, expiresAt, nextDetail, now);
    }
}
