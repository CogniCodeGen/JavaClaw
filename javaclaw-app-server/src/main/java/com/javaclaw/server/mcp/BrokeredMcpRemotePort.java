package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.security.PinnedHttpNetworkBroker;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

/** 通过 DNS 固定 Network Broker 执行 Streamable HTTPS MCP JSON-RPC。 */
public final class BrokeredMcpRemotePort implements McpRemotePort {
    private static final int MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAXIMUM_CREDENTIAL_BYTES = 16 * 1024;
    private final Exchange exchange;
    private final SecretVaultService vault;
    private final CanonicalJson json;
    private final McpCatalogWireCodec catalogs;
    private final McpExternalDataWireCodec externalData;
    private final McpHttpResponseProcessor responses;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<String, SessionBinding> sessions = new ConcurrentHashMap<>();

    /**
     * 创建真实 HTTPS MCP 远端端口。
     *
     * @param broker DNS 固定 HTTP Broker
     * @param grants 私网授权权威服务
     * @param vault 凭据仓库
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public BrokeredMcpRemotePort(
            PinnedHttpNetworkBroker broker,
            PrivateNetworkGrantService grants,
            SecretVaultService vault,
            CanonicalJson json,
            Clock clock) {
        this(productionExchange(broker, grants), vault, json, clock);
    }

    BrokeredMcpRemotePort(Exchange exchange, SecretVaultService vault, CanonicalJson json) {
        this(exchange, vault, json, Clock.systemUTC());
    }

    BrokeredMcpRemotePort(Exchange exchange, SecretVaultService vault, CanonicalJson json, Clock clock) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.json = Objects.requireNonNull(json, "json");
        catalogs = new McpCatalogWireCodec(json);
        externalData = new McpExternalDataWireCodec(json);
        McpWireResponseDecoder decoder = new McpWireResponseDecoder(json);
        responses = new McpHttpResponseProcessor(json, decoder, new McpReverseRequestHandler(json, clock));
    }

    /** {@inheritDoc} */
    @Override
    public McpRemoteSession initialize(McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation)
            throws Exception {
        requireHttps(endpoint);
        if (!McpProtocol.VERSION.equals(requiredProtocol)) {
            throw new IllegalArgumentException("JavaClaw only supports MCP " + McpProtocol.VERSION);
        }
        CanonicalPayload params = json.encode(
                new InitializeParams(requiredProtocol, clientCapabilities(), new ClientInformation("JavaClaw", "5.0")));
        RpcExchange response = call(endpoint, "initialize", params, rejecting(), cancellation, false);
        String protocol = json.textField(response.result(), "protocolVersion")
                .orElseThrow(() -> new IllegalArgumentException("MCP initialize omitted protocolVersion"));
        if (!requiredProtocol.equals(protocol)) {
            return new McpRemoteSession(protocol, Set.of());
        }
        rememberSession(endpoint, response.response());
        notifyInitialized(endpoint, cancellation);
        Set<String> capabilities = json.objectField(response.result(), "capabilities")
                .map(json::fieldNames)
                .orElse(Set.of());
        return new McpRemoteSession(protocol, capabilities);
    }

    /** {@inheritDoc} */
    @Override
    public McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        ensureInitialized(endpoint, cancellation);
        McpCatalogWireCodec.Request request = catalogs.request(Objects.requireNonNull(cursor, "cursor"));
        RpcExchange response = call(endpoint, request.method(), request.params(), rejecting(), cancellation, true);
        return catalogs.decode(request, response.result());
    }

    /** {@inheritDoc} */
    @Override
    public McpResourcePage resources(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        ensureInitialized(endpoint, cancellation);
        RequestIdentity identity = nextRequest();
        CanonicalPayload params =
                externalData.pageParams(Objects.requireNonNull(cursor, "cursor"), identity.progress());
        RpcExchange response = call(endpoint, identity, "resources/list", params, rejecting(), cancellation, true);
        return externalData.resourcePage(response.result(), response.progress());
    }

    /** {@inheritDoc} */
    @Override
    public McpResourceReadResult readResource(McpEndpoint endpoint, String uri, CancellationToken cancellation)
            throws Exception {
        ensureInitialized(endpoint, cancellation);
        RequestIdentity identity = nextRequest();
        CanonicalPayload params = externalData.resourceReadParams(uri, identity.progress());
        RpcExchange response = call(endpoint, identity, "resources/read", params, rejecting(), cancellation, true);
        return externalData.resourceResult(response.result(), response.progress());
    }

    /** {@inheritDoc} */
    @Override
    public McpPromptPage prompts(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        ensureInitialized(endpoint, cancellation);
        RequestIdentity identity = nextRequest();
        CanonicalPayload params =
                externalData.pageParams(Objects.requireNonNull(cursor, "cursor"), identity.progress());
        RpcExchange response = call(endpoint, identity, "prompts/list", params, rejecting(), cancellation, true);
        return externalData.promptPage(response.result(), response.progress());
    }

    /** {@inheritDoc} */
    @Override
    public McpPromptResult getPrompt(
            McpEndpoint endpoint, String name, Map<String, String> arguments, CancellationToken cancellation)
            throws Exception {
        ensureInitialized(endpoint, cancellation);
        RequestIdentity identity = nextRequest();
        CanonicalPayload params = externalData.promptGetParams(name, arguments, identity.progress());
        RpcExchange response = call(endpoint, identity, "prompts/get", params, rejecting(), cancellation, true);
        return externalData.promptResult(response.result(), response.progress());
    }

    /** {@inheritDoc} */
    @Override
    public McpInvocationResult invoke(
            McpEndpoint endpoint,
            McpInvocationRequest request,
            McpClientInteractionPort interactions,
            CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(interactions, "interactions");
        ensureInitialized(endpoint, cancellation);
        RequestIdentity identity = nextRequest();
        CanonicalPayload params = json.encode(new ToolCallParams(
                request.tool().name(),
                request.arguments(),
                new ToolCallMetadata(request.idempotencyKey(), identity.progress())));
        RpcExchange response = call(endpoint, identity, "tools/call", params, interactions, cancellation, true);
        boolean successful = !json.booleanField(response.result(), "isError").orElse(false);
        return new McpInvocationResult(successful, response.result(), Optional.empty(), response.progress());
    }

    /**
     * 检查 Endpoint 不能绕过 HTTPS Broker。精确 DNS/授权检查在同一次 socket 建立前执行。
     *
     * @param endpoint 当前 Endpoint 版本
     */
    public void authorize(McpEndpoint endpoint) {
        requireHttps(endpoint);
    }

    private void ensureInitialized(McpEndpoint endpoint, CancellationToken cancellation) throws Exception {
        SessionBinding current = sessions.get(endpoint.id());
        if (current == null || current.endpointRevision() != endpoint.revision()) {
            McpRemoteSession initialized = initialize(endpoint, McpProtocol.VERSION, cancellation);
            if (!McpProtocol.VERSION.equals(initialized.protocolVersion())) {
                throw new IllegalStateException("MCP endpoint rejected the fixed protocol version");
            }
        }
    }

    private RpcExchange call(
            McpEndpoint endpoint,
            String method,
            CanonicalPayload params,
            McpClientInteractionPort interactions,
            CancellationToken cancellation,
            boolean requireFixedSession)
            throws Exception {
        return call(endpoint, nextRequest(), method, params, interactions, cancellation, requireFixedSession);
    }

    private RpcExchange call(
            McpEndpoint endpoint,
            RequestIdentity identity,
            String method,
            CanonicalPayload params,
            McpClientInteractionPort interactions,
            CancellationToken cancellation,
            boolean requireFixedSession)
            throws Exception {
        CanonicalPayload envelope = json.encode(new WireRequest("2.0", identity.id(), method, params));
        BrokerResponse response = post(endpoint, envelope, cancellation, requireFixedSession);
        McpHttpResponseProcessor.ProcessedResponse processed = responses.process(
                response,
                identity.id(),
                identity.progress(),
                endpoint,
                interactions,
                cancellation,
                reverse -> post(endpoint, reverse, cancellation, true));
        return new RpcExchange(processed.result(), response, processed.progress());
    }

    private BrokerResponse post(
            McpEndpoint endpoint, CanonicalPayload envelope, CancellationToken cancellation, boolean includeSession)
            throws Exception {
        Map<String, List<String>> headers = baseHeaders(endpoint, includeSession);
        Optional<com.javaclaw.api.CredentialRef> credential = endpoint.spec().credential();
        if (endpoint.spec().authType() == McpAuthType.NONE) {
            return send(endpoint, envelope, headers, cancellation);
        }
        com.javaclaw.api.CredentialRef reference = credential.orElseThrow(
                () -> new SecurityException("MCP authentication requires an active CredentialRef"));
        return vault.use(reference, secret -> {
            LinkedHashMap<String, List<String>> authenticated = new LinkedHashMap<>(headers);
            addCredential(endpoint, authenticated, secret);
            return send(endpoint, envelope, authenticated, cancellation);
        });
    }

    private BrokerResponse send(
            McpEndpoint endpoint,
            CanonicalPayload envelope,
            Map<String, List<String>> headers,
            CancellationToken cancellation)
            throws Exception {
        byte[] body = envelope.json().getBytes(StandardCharsets.UTF_8);
        BrokerRequest request = new BrokerRequest(
                endpoint.spec().endpointUri().orElseThrow(),
                "POST",
                headers,
                body,
                MAXIMUM_RESPONSE_BYTES,
                endpoint.spec().requestTimeout());
        BrokerResponse response = exchange.exchange(endpoint, request, cancellation);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.truncated()) {
            throw new IllegalStateException(
                    "MCP HTTP response was unsuccessful or truncated: " + response.statusCode());
        }
        return response;
    }

    private Map<String, List<String>> baseHeaders(McpEndpoint endpoint, boolean includeSession) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("accept", List.of("application/json, text/event-stream"));
        headers.put("content-type", List.of("application/json"));
        headers.put("mcp-protocol-version", List.of(McpProtocol.VERSION));
        if (includeSession) {
            SessionBinding binding = sessions.get(endpoint.id());
            if (binding != null && binding.endpointRevision() == endpoint.revision()) {
                binding.sessionId().ifPresent(value -> headers.put("mcp-session-id", List.of(value)));
            }
        }
        return Map.copyOf(headers);
    }

    private void addCredential(McpEndpoint endpoint, Map<String, List<String>> headers, byte[] secret)
            throws CharacterCodingException {
        String value = credentialText(secret);
        switch (endpoint.spec().authType()) {
            case BEARER, OAUTH_2_1_PKCE -> headers.put("authorization", List.of("Bearer " + value));
            case API_KEY -> headers.put(endpoint.spec().apiKeyHeader().orElseThrow(), List.of(value));
            case NONE -> throw new IllegalStateException("NONE authentication must not resolve a credential");
        }
    }

    private String credentialText(byte[] secret) throws CharacterCodingException {
        if (secret.length == 0 || secret.length > MAXIMUM_CREDENTIAL_BYTES) {
            throw new SecurityException("MCP credential length is outside the allowed range");
        }
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(secret))
                .toString();
    }

    private void rememberSession(McpEndpoint endpoint, BrokerResponse response) {
        Optional<String> sessionId = response.headers().getOrDefault("mcp-session-id", List.of()).stream()
                .findFirst()
                .map(BrokeredMcpRemotePort::sessionId);
        sessions.put(endpoint.id(), new SessionBinding(endpoint.revision(), sessionId));
    }

    private void notifyInitialized(McpEndpoint endpoint, CancellationToken cancellation) throws Exception {
        CanonicalPayload notification =
                json.encode(new WireNotification("2.0", "notifications/initialized", json.parse("{}")));
        post(endpoint, notification, cancellation, true);
    }

    private static Exchange productionExchange(PinnedHttpNetworkBroker broker, PrivateNetworkGrantService grants) {
        PinnedHttpNetworkBroker checkedBroker = Objects.requireNonNull(broker, "broker");
        PrivateNetworkGrantService checkedGrants = Objects.requireNonNull(grants, "grants");
        return (endpoint, request, cancellation) -> checkedBroker.exchange(
                request,
                permission(endpoint),
                cancellation,
                (origin, addresses) -> authorizePrivate(endpoint, origin, addresses, checkedGrants));
    }

    private static void authorizePrivate(
            McpEndpoint endpoint, URI origin, Set<String> addresses, PrivateNetworkGrantService grants) {
        PrivateNetworkGrantRef reference = endpoint.spec()
                .privateNetworkGrant()
                .orElseThrow(() -> new SecurityException("private MCP origin requires an explicit grant"));
        grants.requireAuthorized(
                reference.id(),
                reference.revision(),
                endpoint.spec().workspaceId(),
                PrivateNetworkPurpose.MCP,
                origin,
                addresses);
    }

    private static PermissionProfile permission(McpEndpoint endpoint) {
        URI uri = endpoint.spec().endpointUri().orElseThrow();
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        Duration timeout = endpoint.spec().requestTimeout();
        return new PermissionProfile(
                "mcp-network-" + endpoint.id(),
                endpoint.revision(),
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(uri.getHost()), Set.of(port), true),
                new ProcessPermission(Set.of(), false, timeout),
                new ToolPermission(Set.of(), ToolRisk.NETWORK, ApprovalRequirement.NONE),
                new ResourceLimits(32L * 1024 * 1024, MAXIMUM_RESPONSE_BYTES, 1, 32));
    }

    private static void requireHttps(McpEndpoint endpoint) {
        if (Objects.requireNonNull(endpoint, "endpoint").spec().transport() != McpTransport.STREAMABLE_HTTPS) {
            throw new IllegalArgumentException("Brokered MCP transport only accepts HTTPS endpoints");
        }
    }

    private static String sessionId(String value) {
        String checked = Objects.requireNonNull(value, "sessionId");
        if (checked.isBlank() || checked.length() > 1_024 || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("MCP session id is invalid");
        }
        return checked;
    }

    private RequestIdentity nextRequest() {
        long value = requestSequence.incrementAndGet();
        return new RequestIdentity("javaclaw-mcp-" + value, "javaclaw-progress-" + value);
    }

    private CanonicalPayload clientCapabilities() {
        return json.parse("{\"elicitation\":{\"form\":{}},\"sampling\":{}}");
    }

    private static McpClientInteractionPort rejecting() {
        return new McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, CancellationToken cancellation) {
                return Optional.empty();
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, CancellationToken cancellation) {
                return Optional.empty();
            }
        };
    }

    @FunctionalInterface
    interface Exchange {
        BrokerResponse exchange(McpEndpoint endpoint, BrokerRequest request, CancellationToken cancellation)
                throws Exception;
    }

    private record InitializeParams(
            String protocolVersion, CanonicalPayload capabilities, ClientInformation clientInfo) {}

    private record ClientInformation(String name, String version) {}

    private record ToolCallParams(String name, CanonicalPayload arguments, ToolCallMetadata _meta) {}

    private record ToolCallMetadata(String javaclawIdempotencyKey, String progressToken) {}

    private record WireRequest(String jsonrpc, String id, String method, CanonicalPayload params) {}

    private record WireNotification(String jsonrpc, String method, CanonicalPayload params) {}

    private record RpcExchange(
            CanonicalPayload result, BrokerResponse response, List<com.javaclaw.api.McpProgress> progress) {}

    private record RequestIdentity(String id, String progress) {}

    private record SessionBinding(long endpointRevision, Optional<String> sessionId) {}
}
