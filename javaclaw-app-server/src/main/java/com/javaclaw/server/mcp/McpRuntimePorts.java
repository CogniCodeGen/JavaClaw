package com.javaclaw.server.mcp;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpElicitationRequest;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpSamplingRequest;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpNetworkAuthorizationPort;
import com.javaclaw.extension.spi.McpOAuthAuthorizationRequest;
import com.javaclaw.extension.spi.McpOAuthBrokerPort;
import com.javaclaw.extension.spi.McpOAuthExchange;
import com.javaclaw.extension.spi.McpRemotePort;

/**
 * App Server MCP Host 的显式外部副作用端口。
 *
 * @param remote 经 Network Broker 或签名 Bundle Sandbox 的远端传输
 * @param interactions 受治理 elicitation/sampling
 * @param network 每次 HTTPS 调用前的实时授权
 * @param oauth OAuth 2.1 metadata 与 token Broker
 * @param available 是否已装配可真实执行的 Host 边界
 */
public record McpRuntimePorts(
        McpRemotePort remote,
        McpClientInteractionFactory interactions,
        McpNetworkAuthorizationPort network,
        McpOAuthBrokerPort oauth,
        boolean available) {
    /** 校验端口。 */
    public McpRuntimePorts {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(oauth, "oauth");
    }

    /**
     * 返回 fail-closed 端口；平台管理仍可使用，但所有远端动作明确失败。
     *
     * @return 关闭的 MCP 副作用边界
     */
    public static McpRuntimePorts unavailable() {
        return new McpRuntimePorts(
                new UnavailableRemote(),
                (turnId, workspaceId, endpoint) -> new RejectingInteractions(),
                endpoint -> {
                    throw new SecurityException("MCP Network Broker 尚未装配");
                },
                new UnavailableOAuth(),
                false);
    }

    private static final class UnavailableRemote implements McpRemotePort {
        @Override
        public McpRemoteSession initialize(
                McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation) {
            throw new IllegalStateException("MCP remote transport is unavailable");
        }

        @Override
        public McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation) {
            throw new IllegalStateException("MCP remote transport is unavailable");
        }

        @Override
        public McpInvocationResult invoke(
                McpEndpoint endpoint,
                McpInvocationRequest request,
                McpClientInteractionPort interactions,
                CancellationToken cancellation) {
            throw new IllegalStateException("MCP remote transport is unavailable");
        }
    }

    private static final class RejectingInteractions implements com.javaclaw.extension.spi.McpClientInteractionPort {
        @Override
        public Optional<CanonicalPayload> elicit(McpElicitationRequest request, CancellationToken cancellation) {
            return Optional.empty();
        }

        @Override
        public Optional<CanonicalPayload> sample(McpSamplingRequest request, CancellationToken cancellation) {
            return Optional.empty();
        }
    }

    private static final class UnavailableOAuth implements McpOAuthBrokerPort {
        @Override
        public McpOAuthAuthorizationRequest authorizationRequest(
                McpEndpoint endpoint, String codeChallenge, String state, java.net.URI redirectUri) {
            throw new IllegalStateException("MCP OAuth Broker is unavailable");
        }

        @Override
        public byte[] exchange(McpOAuthExchange exchange) {
            throw new IllegalStateException("MCP OAuth Broker is unavailable");
        }
    }
}
