package com.javaclaw.server.mcp;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.McpTransport;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpRemotePort;

/** 按受信传输类型选择 HTTPS Broker 或签名 Bundle Sandbox。 */
final class CompositeMcpRemotePort implements McpRemotePort {
    private final McpRemotePort https;
    private final McpRemotePort stdio;

    /** @param https HTTPS Broker 端口 @param stdio 签名 Bundle Sandbox 端口 */
    CompositeMcpRemotePort(McpRemotePort https, McpRemotePort stdio) {
        this.https = Objects.requireNonNull(https, "https");
        this.stdio = Objects.requireNonNull(stdio, "stdio");
    }

    @Override
    public McpRemoteSession initialize(McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation)
            throws Exception {
        return select(endpoint).initialize(endpoint, requiredProtocol, cancellation);
    }

    @Override
    public McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        return select(endpoint).catalog(endpoint, cursor, cancellation);
    }

    @Override
    public McpResourcePage resources(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        return select(endpoint).resources(endpoint, cursor, cancellation);
    }

    @Override
    public McpResourceReadResult readResource(McpEndpoint endpoint, String uri, CancellationToken cancellation)
            throws Exception {
        return select(endpoint).readResource(endpoint, uri, cancellation);
    }

    @Override
    public McpPromptPage prompts(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        return select(endpoint).prompts(endpoint, cursor, cancellation);
    }

    @Override
    public McpPromptResult getPrompt(
            McpEndpoint endpoint, String name, Map<String, String> arguments, CancellationToken cancellation)
            throws Exception {
        return select(endpoint).getPrompt(endpoint, name, arguments, cancellation);
    }

    @Override
    public McpInvocationResult invoke(
            McpEndpoint endpoint,
            McpInvocationRequest request,
            McpClientInteractionPort interactions,
            CancellationToken cancellation)
            throws Exception {
        return select(endpoint).invoke(endpoint, request, interactions, cancellation);
    }

    private McpRemotePort select(McpEndpoint endpoint) {
        return Objects.requireNonNull(endpoint, "endpoint").spec().transport() == McpTransport.STREAMABLE_HTTPS
                ? https
                : stdio;
    }
}
