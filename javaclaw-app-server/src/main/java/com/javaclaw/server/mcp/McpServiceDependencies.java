package com.javaclaw.server.mcp;

import java.util.Objects;

import com.javaclaw.extension.spi.McpCredentialStatusPort;
import com.javaclaw.extension.spi.McpNetworkAuthorizationPort;
import com.javaclaw.extension.spi.McpRemotePort;

/**
 * MCP Host 的外部副作用边界集合。
 *
 * @param remote Broker/Sandbox 后的远端传输
 * @param interactions 受治理反向交互
 * @param network 实时网络授权检查
 * @param credentials Vault 引用实时检查
 * @param signedBundles 已验签 Bundle 实时来源
 */
public record McpServiceDependencies(
        McpRemotePort remote,
        McpClientInteractionFactory interactions,
        McpNetworkAuthorizationPort network,
        McpCredentialStatusPort credentials,
        SignedBundleMcpSource signedBundles) {
    /** 校验全部边界。 */
    public McpServiceDependencies {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(signedBundles, "signedBundles");
    }
}
