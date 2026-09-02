package com.javaclaw.extension.spi;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OAuth metadata 发现后的服务端内部浏览器请求。
 *
 * <p>该类型只允许在 App Server 与隔离 Browser Worker 的私有边界中传递。它不得进入 JSON-RPC、日志、Artifact 或 Desktop 状态，因为 {@code authorizationUri}
 * 包含 CSRF state 和 PKCE challenge。
 *
 * @param authorizationUri 带 state/challenge 的完整 HTTPS 授权 URI
 * @param allowedOrigins metadata 约束后的精确 HTTPS Origin；必须包含授权 URI Origin
 */
public record McpOAuthAuthorizationRequest(URI authorizationUri, Set<URI> allowedOrigins) {
    /** 校验授权 URI 和精确 Origin 集合。 */
    public McpOAuthAuthorizationRequest {
        authorizationUri = https(authorizationUri, false);
        allowedOrigins = Objects.requireNonNull(allowedOrigins, "allowedOrigins").stream()
                .map(value -> https(value, true))
                .collect(Collectors.toUnmodifiableSet());
        if (!allowedOrigins.contains(origin(authorizationUri))) {
            throw new IllegalArgumentException("allowedOrigins must contain the authorization origin");
        }
    }

    private static URI https(URI value, boolean originOnly) {
        URI uri = Objects.requireNonNull(value, "uri").normalize();
        requireHttpsAuthority(uri);
        if (originOnly) {
            requireOriginOnly(uri);
            return origin(uri);
        }
        return uri;
    }

    private static void requireHttpsAuthority(URI uri) {
        boolean invalidPort = uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65_535;
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("OAuth Browser URI must be an exact HTTPS target");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null || invalidPort) {
            throw new IllegalArgumentException("OAuth Browser URI contains unsupported authority data");
        }
    }

    private static void requireOriginOnly(URI uri) {
        boolean hasPath = uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath());
        if (hasPath || uri.getQuery() != null) {
            throw new IllegalArgumentException("OAuth Browser Origin must not contain a path or query");
        }
    }

    private static URI origin(URI uri) {
        try {
            return new URI(
                    uri.getScheme().toLowerCase(java.util.Locale.ROOT),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    null,
                    null,
                    null);
        } catch (java.net.URISyntaxException impossible) {
            throw new IllegalArgumentException("OAuth Browser Origin is invalid", impossible);
        }
    }
}
