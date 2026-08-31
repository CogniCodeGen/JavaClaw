package com.javaclaw.server.extension.mcp;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.server.security.SecretStore;

/** Resolves static MCP credentials just-in-time and wipes the temporary character buffer. */
public final class McpCredentialAuthorization implements McpHttpAuthorization {
    private final String serverId;
    private final McpConfiguration.Authentication authentication;
    private final SecretStore secrets;

    /** 绑定认证元数据和 SecretStore；每次请求按需解析静态凭据，不将值放入公开配置。 */
    public McpCredentialAuthorization(
            String serverId, McpConfiguration.Authentication authentication, SecretStore secrets) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public Map<String, String> headers() {
        if (authentication.type() == McpConfiguration.AuthenticationType.NONE) {
            return Map.of();
        }
        if (authentication.type() == McpConfiguration.AuthenticationType.OAUTH) {
            throw new IllegalStateException("MCP OAuth authorization is not complete");
        }
        char[] value = secrets.resolve("mcp:" + serverId, authentication.credentialName())
                .orElseThrow(() -> new IllegalStateException("MCP credential is not configured"));
        try {
            String secret = new String(value);
            return authentication.type() == McpConfiguration.AuthenticationType.BEARER
                    ? Map.of("Authorization", "Bearer " + secret)
                    : Map.of(authentication.headerName(), secret);
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}
