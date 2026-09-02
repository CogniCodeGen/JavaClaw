package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP 端点可编辑配置，不包含任何 Secret 明文。
 *
 * @param workspaceId 所属 Workspace
 * @param displayName 用户可见名称
 * @param transport 受信传输来源
 * @param endpointUri HTTPS 端点；stdio 时为空
 * @param signedBundleId 已验证签名 Bundle；HTTPS 时为空
 * @param authType 认证类型
 * @param credential Vault 引用；NONE 时为空，OAuth 授权完成前可为空
 * @param apiKeyHeader API Key header 名；仅 API_KEY 使用
 * @param privateNetworkGrant 可选的精确私网授权版本
 * @param requestTimeout 单次远端请求超时
 */
public record McpEndpointSpec(
        WorkspaceId workspaceId,
        String displayName,
        McpTransport transport,
        Optional<URI> endpointUri,
        Optional<String> signedBundleId,
        McpAuthType authType,
        Optional<CredentialRef> credential,
        Optional<String> apiKeyHeader,
        Optional<PrivateNetworkGrantRef> privateNetworkGrant,
        Duration requestTimeout) {
    /** 校验传输、认证和非敏感配置。 */
    public McpEndpointSpec {
        Objects.requireNonNull(workspaceId, "workspaceId");
        displayName = Preconditions.text(displayName, "displayName");
        Objects.requireNonNull(transport, "transport");
        endpointUri = Objects.requireNonNull(endpointUri, "endpointUri");
        signedBundleId = Objects.requireNonNull(signedBundleId, "signedBundleId")
                .map(value -> Preconditions.identifier(value, "signedBundleId"));
        Objects.requireNonNull(authType, "authType");
        credential = Objects.requireNonNull(credential, "credential");
        apiKeyHeader =
                Objects.requireNonNull(apiKeyHeader, "apiKeyHeader").map(value -> requireHeaderName(value.strip()));
        privateNetworkGrant = Objects.requireNonNull(privateNetworkGrant, "privateNetworkGrant");
        requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        requireTimeout(requestTimeout);
        requireTransport(transport, endpointUri, signedBundleId);
        requireAuthentication(authType, credential, apiKeyHeader, transport);
    }

    private static void requireTransport(
            McpTransport transport, Optional<URI> endpointUri, Optional<String> signedBundleId) {
        if (transport == McpTransport.STREAMABLE_HTTPS) {
            URI uri = endpointUri.orElseThrow(() -> new IllegalArgumentException("HTTPS MCP requires endpointUri"));
            requireHttps(uri);
            if (signedBundleId.isPresent()) {
                throw new IllegalArgumentException("HTTPS MCP must not reference a Bundle");
            }
            return;
        }
        if (endpointUri.isPresent() || signedBundleId.isEmpty()) {
            throw new IllegalArgumentException("stdio MCP requires only a signed Bundle id");
        }
    }

    private static void requireAuthentication(
            McpAuthType authType,
            Optional<CredentialRef> credential,
            Optional<String> apiKeyHeader,
            McpTransport transport) {
        if (transport == McpTransport.SIGNED_BUNDLE_STDIO && authType != McpAuthType.NONE) {
            throw new IllegalArgumentException("stdio MCP authentication belongs to the signed Bundle");
        }
        if (authType == McpAuthType.NONE && (credential.isPresent() || apiKeyHeader.isPresent())) {
            throw new IllegalArgumentException("NONE authentication must not reference credentials");
        }
        if ((authType == McpAuthType.BEARER || authType == McpAuthType.API_KEY) && credential.isEmpty()) {
            throw new IllegalArgumentException("selected authentication requires CredentialRef");
        }
        if ((authType == McpAuthType.API_KEY) != apiKeyHeader.isPresent()) {
            throw new IllegalArgumentException("apiKeyHeader is required only for API_KEY");
        }
        credential.ifPresent(reference -> {
            if (!reference.namespace().equals("mcp") && !reference.namespace().equals("oauth")) {
                throw new IllegalArgumentException("MCP credential namespace must be mcp or oauth");
            }
        });
    }

    private static void requireHttps(URI uri) {
        boolean valid = uri.isAbsolute()
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getFragment() == null
                && uri.getQuery() == null;
        if (!valid) {
            throw new IllegalArgumentException(
                    "MCP endpointUri must be an HTTPS URI without credentials/query/fragment");
        }
    }

    private static String requireHeaderName(String header) {
        if (!header.matches("[A-Za-z][A-Za-z0-9-]{0,79}")) {
            throw new IllegalArgumentException("apiKeyHeader is invalid");
        }
        String normalized = header.toLowerCase(Locale.ROOT);
        if (normalized.equals("host") || normalized.equals("cookie") || normalized.equals("authorization")) {
            throw new IllegalArgumentException("apiKeyHeader is reserved");
        }
        return header;
    }

    private static void requireTimeout(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("requestTimeout must be between 1 nanosecond and 2 minutes");
        }
    }
}
