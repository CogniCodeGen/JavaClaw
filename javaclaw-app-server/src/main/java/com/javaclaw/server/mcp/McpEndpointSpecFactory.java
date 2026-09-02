package com.javaclaw.server.mcp;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpTransport;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.McpRpcContracts;
import com.javaclaw.server.persistence.PersistenceException;

/** 从受信平台来源构造不能由客户端伪造的 MCP Endpoint 配置。 */
final class McpEndpointSpecFactory {
    private McpEndpointSpecFactory() {}

    static McpEndpointSpec signedBundle(
            McpRpcContracts.SignedBundleRegisterPayload request, SignedBundleMcpSource source, CanonicalJson json) {
        McpRpcContracts.SignedBundleRegisterPayload checked = Objects.requireNonNull(request, "request");
        SignedBundleMcpLaunch launch = Objects.requireNonNull(source, "source").require(checked.bundleId());
        String protocol = Objects.requireNonNull(json, "json")
                .textField(launch.descriptor(), "protocolVersion")
                .orElseThrow(() -> PersistenceException.invalidRequest("签名 MCP 描述缺少 protocolVersion"));
        if (!McpProtocol.VERSION.equals(protocol)) {
            throw PersistenceException.invalidRequest("签名 MCP Bundle 必须精确使用 " + McpProtocol.VERSION);
        }
        return new McpEndpointSpec(
                checked.workspaceId(),
                checked.displayName(),
                McpTransport.SIGNED_BUNDLE_STDIO,
                Optional.empty(),
                Optional.of(launch.bundleId()),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                checked.requestTimeout());
    }

    static McpEndpointSpec oauth(McpEndpointSpec current, CredentialRef credential) {
        return new McpEndpointSpec(
                current.workspaceId(),
                current.displayName(),
                current.transport(),
                current.endpointUri(),
                current.signedBundleId(),
                current.authType(),
                Optional.of(credential),
                current.apiKeyHeader(),
                current.privateNetworkGrant(),
                current.requestTimeout());
    }
}
