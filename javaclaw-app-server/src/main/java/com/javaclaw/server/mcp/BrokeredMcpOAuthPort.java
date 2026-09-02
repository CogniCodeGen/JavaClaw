package com.javaclaw.server.mcp;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.McpOAuthAuthorizationRequest;
import com.javaclaw.extension.spi.McpOAuthBrokerPort;
import com.javaclaw.extension.spi.McpOAuthExchange;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.security.PinnedHttpNetworkBroker;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;

/**
 * 通过 DNS 固定 Broker 执行 OAuth 2.1 metadata、动态客户端注册和 PKCE token 交换。
 *
 * <p><strong>安全不变量：</strong>发现到的授权、注册和 token 地址必须是 HTTPS，且三个地址必须属于 metadata 声明的同一 issuer
 * Origin；私网地址每次连接都重新核对精确授权。访问令牌只以受限 UTF-8 字节返回给 Vault 调用方，不写入 DTO、日志或异常。
 */
public final class BrokeredMcpOAuthPort implements McpOAuthBrokerPort {
    private static final int MAXIMUM_METADATA_BYTES = 256 * 1024;
    private static final int MAXIMUM_TOKEN_BYTES = 16 * 1024;
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(30);

    private final Exchange exchange;
    private final CanonicalJson json;

    /**
     * 创建真实 OAuth 2.1 Broker。
     *
     * @param broker DNS 固定 HTTP Broker
     * @param grants 私网授权权威服务
     * @param json 严格 JSON codec
     */
    public BrokeredMcpOAuthPort(PinnedHttpNetworkBroker broker, PrivateNetworkGrantService grants, CanonicalJson json) {
        this(productionExchange(broker, grants), json);
    }

    BrokeredMcpOAuthPort(Exchange exchange, CanonicalJson json) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** {@inheritDoc} */
    @Override
    public McpOAuthAuthorizationRequest authorizationRequest(
            McpEndpoint endpoint, String codeChallenge, String state, URI redirectUri) throws Exception {
        McpEndpoint checkedEndpoint = Objects.requireNonNull(endpoint, "endpoint");
        requirePkceValue(codeChallenge, "codeChallenge");
        requireState(state);
        URI checkedRedirect = requireLoopbackRedirect(redirectUri);
        AuthorizationMetadata metadata = discover(checkedEndpoint, new CancellationSource());
        ClientRegistration client = register(checkedEndpoint, metadata, checkedRedirect, new CancellationSource());
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", client.clientId());
        query.put("redirect_uri", checkedRedirect.toString());
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        query.put("state", state);
        query.put("resource", checkedEndpoint.spec().endpointUri().orElseThrow().toString());
        URI authorizationUri = withQuery(metadata.authorizationEndpoint(), query);
        return new McpOAuthAuthorizationRequest(authorizationUri, Set.of(origin(metadata.authorizationEndpoint())));
    }

    /** {@inheritDoc} */
    @Override
    public byte[] exchange(McpOAuthExchange request) throws Exception {
        McpOAuthExchange checked = Objects.requireNonNull(request, "request");
        checked.cancellation().throwIfCancelled();
        URI redirect = requireLoopbackRedirect(checked.redirectUri());
        Callback callback = callback(checked.callbackUri(), redirect, checked.expectedState());
        String clientId = query(checked.authorizationUri(), "client_id");
        AuthorizationMetadata metadata = discover(checked.endpoint(), checked.cancellation());
        CanonicalPayload token = postForm(
                checked.endpoint(),
                metadata.tokenEndpoint(),
                Map.of(
                        "grant_type", "authorization_code",
                        "code", callback.code(),
                        "redirect_uri", redirect.toString(),
                        "client_id", clientId,
                        "code_verifier", checked.codeVerifier(),
                        "resource",
                                checked.endpoint()
                                        .spec()
                                        .endpointUri()
                                        .orElseThrow()
                                        .toString()),
                checked.cancellation());
        String tokenType = json.textField(token, "token_type").orElseThrow(() -> invalid("token_type 缺失"));
        if (!"bearer".equalsIgnoreCase(tokenType)) {
            throw invalid("token_type 必须为 Bearer");
        }
        return token(json.textField(token, "access_token").orElseThrow(() -> invalid("access_token 缺失")));
    }

    private AuthorizationMetadata discover(McpEndpoint endpoint, CancellationToken cancellation) throws Exception {
        URI resource = endpoint.spec().endpointUri().orElseThrow();
        URI resourceMetadataUri = origin(resource).resolve("/.well-known/oauth-protected-resource");
        CanonicalPayload resourceMetadata = get(endpoint, resourceMetadataUri, cancellation);
        List<String> authorizationServers = json.textArrayField(resourceMetadata, "authorization_servers", 4);
        if (authorizationServers.size() != 1) {
            throw invalid("resource metadata 必须声明一个 authorization server");
        }
        URI issuer = requireHttps(URI.create(authorizationServers.getFirst()), "authorization server");
        URI metadataUri = issuerMetadata(issuer);
        CanonicalPayload metadata = get(endpoint, metadataUri, cancellation);
        URI declaredIssuer = requireHttps(
                URI.create(json.textField(metadata, "issuer").orElseThrow(() -> invalid("issuer 缺失"))), "issuer");
        if (!normalizedIssuer(issuer).equals(normalizedIssuer(declaredIssuer))) {
            throw invalid("authorization server issuer 不匹配");
        }
        requireSupported(metadata);
        URI authorization = metadataUri(metadata, "authorization_endpoint", declaredIssuer);
        URI token = metadataUri(metadata, "token_endpoint", declaredIssuer);
        URI registration = metadataUri(metadata, "registration_endpoint", declaredIssuer);
        return new AuthorizationMetadata(declaredIssuer, authorization, token, registration);
    }

    private ClientRegistration register(
            McpEndpoint endpoint, AuthorizationMetadata metadata, URI redirectUri, CancellationToken cancellation)
            throws Exception {
        CanonicalPayload body = json.encode(Map.of(
                "client_name",
                "JavaClaw",
                "redirect_uris",
                List.of(redirectUri.toString()),
                "grant_types",
                List.of("authorization_code"),
                "response_types",
                List.of("code"),
                "token_endpoint_auth_method",
                "none"));
        CanonicalPayload response = postJson(endpoint, metadata.registrationEndpoint(), body, cancellation);
        String clientId = json.textField(response, "client_id")
                .map(value -> bounded(value, "client_id", 512))
                .orElseThrow(() -> invalid("动态注册响应缺少 client_id"));
        return new ClientRegistration(clientId);
    }

    private CanonicalPayload get(McpEndpoint endpoint, URI uri, CancellationToken cancellation) throws Exception {
        return request(endpoint, uri, "GET", Map.of("accept", List.of("application/json")), new byte[0], cancellation);
    }

    private CanonicalPayload postJson(
            McpEndpoint endpoint, URI uri, CanonicalPayload body, CancellationToken cancellation) throws Exception {
        return request(
                endpoint,
                uri,
                "POST",
                Map.of("accept", List.of("application/json"), "content-type", List.of("application/json")),
                body.json().getBytes(StandardCharsets.UTF_8),
                cancellation);
    }

    private CanonicalPayload postForm(
            McpEndpoint endpoint, URI uri, Map<String, String> fields, CancellationToken cancellation)
            throws Exception {
        byte[] body = form(fields).getBytes(StandardCharsets.UTF_8);
        return request(
                endpoint,
                uri,
                "POST",
                Map.of(
                        "accept",
                        List.of("application/json"),
                        "content-type",
                        List.of("application/x-www-form-urlencoded")),
                body,
                cancellation);
    }

    private CanonicalPayload request(
            McpEndpoint endpoint,
            URI uri,
            String method,
            Map<String, List<String>> headers,
            byte[] body,
            CancellationToken cancellation)
            throws Exception {
        URI checkedUri = requireHttps(uri, "OAuth endpoint");
        BrokerRequest request = new BrokerRequest(
                checkedUri,
                method,
                headers,
                body,
                MAXIMUM_METADATA_BYTES,
                minimum(endpoint.spec().requestTimeout(), MAXIMUM_TIMEOUT));
        BrokerResponse response = exchange.exchange(endpoint, request, cancellation);
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.truncated()) {
            throw invalid("OAuth Broker 响应失败或被截断：" + response.statusCode());
        }
        requireJson(response.headers());
        return json.parse(decode(response.body()));
    }

    private URI metadataUri(CanonicalPayload metadata, String field, URI issuer) {
        URI value = requireHttps(
                URI.create(json.textField(metadata, field).orElseThrow(() -> invalid(field + " 缺失"))), field);
        if (!origin(value).equals(origin(issuer))) {
            throw invalid(field + " 必须与 issuer 同 Origin");
        }
        return value;
    }

    private void requireSupported(CanonicalPayload metadata) {
        List<String> challenges = json.textArrayField(metadata, "code_challenge_methods_supported", 16);
        if (!challenges.contains("S256")) {
            throw invalid("authorization server 不支持 PKCE S256");
        }
        List<String> grants = json.textArrayField(metadata, "grant_types_supported", 16);
        if (!grants.isEmpty() && !grants.contains("authorization_code")) {
            throw invalid("authorization server 不支持 authorization_code");
        }
        List<String> responses = json.textArrayField(metadata, "response_types_supported", 16);
        if (!responses.isEmpty() && !responses.contains("code")) {
            throw invalid("authorization server 不支持 code response type");
        }
    }

    private static Callback callback(URI callback, URI redirect, String expectedState) {
        URI checked = Objects.requireNonNull(callback, "callbackUri").normalize();
        if (!sameCallback(checked, redirect)) {
            throw invalid("OAuth callback 与启动回调不匹配");
        }
        Map<String, List<String>> values = query(checked);
        if (values.containsKey("error")) {
            throw invalid("OAuth authorization server 返回错误");
        }
        String state = unique(values, "state");
        if (!MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), expectedState.getBytes(StandardCharsets.UTF_8))) {
            throw invalid("OAuth state 不匹配");
        }
        return new Callback(unique(values, "code"));
    }

    private static boolean sameCallback(URI actual, URI expected) {
        return Objects.equals(actual.getScheme(), expected.getScheme())
                && Objects.equals(actual.getHost(), expected.getHost())
                && effectivePort(actual) == effectivePort(expected)
                && Objects.equals(path(actual), path(expected))
                && actual.getUserInfo() == null
                && actual.getFragment() == null;
    }

    private static URI requireLoopbackRedirect(URI value) {
        URI uri = Objects.requireNonNull(value, "redirectUri").normalize();
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost()))
                && uri.getPort() > 0
                && uri.getUserInfo() == null
                && uri.getFragment() == null
                && uri.getQuery() == null;
        if (!loopback) {
            throw invalid("OAuth redirect 必须是固定 loopback HTTP URI");
        }
        return uri;
    }

    private static URI issuerMetadata(URI issuer) {
        String suffix = path(issuer).equals("/") ? "" : path(issuer);
        return origin(issuer).resolve("/.well-known/oauth-authorization-server" + suffix);
    }

    private static URI normalizedIssuer(URI issuer) {
        URI checked = requireHttps(issuer, "issuer");
        String normalizedPath = path(checked);
        if (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return URI.create(origin(checked) + normalizedPath);
    }

    private static URI requireHttps(URI value, String name) {
        URI uri = Objects.requireNonNull(value, name).normalize();
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw invalid(name + " 必须是无凭据和 fragment 的 HTTPS URI");
        }
        return uri;
    }

    private static URI withQuery(URI base, Map<String, String> values) {
        String separator = base.getRawQuery() == null ? "?" : "&";
        return URI.create(base + separator + form(values));
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(Objects.requireNonNull(value), StandardCharsets.UTF_8);
    }

    private static String query(URI uri, String name) {
        return unique(query(Objects.requireNonNull(uri, "uri")), name);
    }

    private static Map<String, List<String>> query(URI uri) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        String raw = Optional.ofNullable(uri.getRawQuery()).orElse("");
        if (raw.isEmpty()) {
            return Map.of();
        }
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return Map.copyOf(result);
    }

    private static String unique(Map<String, List<String>> query, String name) {
        List<String> values = query.getOrDefault(name, List.of());
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw invalid("OAuth 参数必须且只能出现一次：" + name);
        }
        return bounded(values.getFirst(), name, 8_192);
    }

    private static void requireJson(Map<String, List<String>> headers) {
        boolean json = headers.getOrDefault("content-type", List.of()).stream()
                .map(String::toLowerCase)
                .anyMatch(value -> value.startsWith("application/json") || value.contains("+json"));
        if (!json) {
            throw invalid("OAuth 响应 Content-Type 必须是 JSON");
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static byte[] token(String value) {
        String checked = bounded(value, "access_token", MAXIMUM_TOKEN_BYTES);
        if (checked.chars().anyMatch(Character::isISOControl)) {
            throw invalid("access_token 包含控制字符");
        }
        byte[] bytes = checked.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_TOKEN_BYTES) {
            throw invalid("access_token 超过大小上限");
        }
        return bytes;
    }

    private static String bounded(String value, String name, int maximumLength) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank() || checked.length() > maximumLength) {
            throw invalid(name + " 长度不合法");
        }
        return checked;
    }

    private static void requirePkceValue(String value, String name) {
        String checked = bounded(value, name, 128);
        if (!checked.matches("[A-Za-z0-9._~-]{43,128}")) {
            throw invalid(name + " 不符合 PKCE 约束");
        }
    }

    private static void requireState(String value) {
        String checked = bounded(value, "state", 256);
        if (!checked.matches("[A-Za-z0-9._~-]{32,256}")) {
            throw invalid("state 不符合安全约束");
        }
    }

    private static URI origin(URI uri) {
        int port = effectivePort(uri);
        String suffix = port == 443 ? "" : ":" + port;
        return URI.create("https://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + suffix);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private static String path(URI uri) {
        return uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static Exchange productionExchange(PinnedHttpNetworkBroker broker, PrivateNetworkGrantService grants) {
        PinnedHttpNetworkBroker checkedBroker = Objects.requireNonNull(broker, "broker");
        PrivateNetworkGrantService checkedGrants = Objects.requireNonNull(grants, "grants");
        return (endpoint, request, cancellation) -> checkedBroker.exchange(
                request,
                permission(endpoint, request.uri()),
                cancellation,
                (origin, addresses) -> authorizePrivate(endpoint, origin, addresses, checkedGrants));
    }

    private static PermissionProfile permission(McpEndpoint endpoint, URI target) {
        int port = effectivePort(target);
        Duration timeout = minimum(endpoint.spec().requestTimeout(), MAXIMUM_TIMEOUT);
        return new PermissionProfile(
                "mcp-oauth-" + endpoint.id(),
                endpoint.revision(),
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(target.getHost()), Set.of(port), true),
                new ProcessPermission(Set.of(), false, timeout),
                new ToolPermission(Set.of(), ToolRisk.NETWORK, ApprovalRequirement.NONE),
                new ResourceLimits(8L * 1024 * 1024, MAXIMUM_METADATA_BYTES, 1, 16));
    }

    private static void authorizePrivate(
            McpEndpoint endpoint, URI origin, Set<String> addresses, PrivateNetworkGrantService grants) {
        PrivateNetworkGrantRef reference = endpoint.spec()
                .privateNetworkGrant()
                .orElseThrow(() -> new SecurityException("private OAuth origin requires an explicit grant"));
        grants.requireAuthorized(
                reference.id(),
                reference.revision(),
                endpoint.spec().workspaceId(),
                PrivateNetworkPurpose.MCP,
                origin,
                addresses);
    }

    @FunctionalInterface
    interface Exchange {
        BrokerResponse exchange(McpEndpoint endpoint, BrokerRequest request, CancellationToken cancellation)
                throws Exception;
    }

    private record AuthorizationMetadata(
            URI issuer, URI authorizationEndpoint, URI tokenEndpoint, URI registrationEndpoint) {}

    private record ClientRegistration(String clientId) {}

    private record Callback(String code) {}
}
