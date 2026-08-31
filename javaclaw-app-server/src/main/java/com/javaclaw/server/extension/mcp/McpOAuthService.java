package com.javaclaw.server.extension.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.BrokerResponse;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.server.security.SecretStore;

/** OAuth 2.1/PKCE flow with issuer/resource binding and a one-shot loopback callback. */
public final class McpOAuthService implements McpAuthorizationService {
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_METADATA_BYTES = 1024L * 1024L;
    private static final int MAX_CALLBACK_LINE = 16 * 1024;
    private static final String TOKEN_SECRET = "oauth-token";

    private final java.util.function.Function<McpConfiguration, NetworkBroker> brokers;
    private final SecretStore secrets;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, PendingAuthorization> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CopyOnWriteArrayList<Consumer<AuthorizationStatus>> statusListeners = new CopyOnWriteArrayList<>();

    /** 绑定 Broker、SecretStore 和编码器；OAuth 元数据/令牌请求也必须逐跳接受网络策略校验。 */
    public McpOAuthService(
            java.util.function.Function<McpConfiguration, NetworkBroker> brokers,
            SecretStore secrets,
            ObjectMapper json) {
        this.brokers = Objects.requireNonNull(brokers, "brokers");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Authorization start(McpConfiguration configuration) throws Exception {
        requireOpen();
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.transport() != McpConfiguration.Transport.HTTP
                || configuration.authentication().type() != McpConfiguration.AuthenticationType.OAUTH) {
            throw new IllegalArgumentException("MCP OAuth requires an HTTP OAuth configuration");
        }
        if (pending.size() >= 16) {
            throw new IllegalStateException("too many pending MCP OAuth authorizations");
        }
        OAuthMetadata metadata = discover(configuration);
        String id = "mcp_auth_" + UUID.randomUUID().toString().replace("-", "");
        ServerSocket callback = new ServerSocket();
        callback.setReuseAddress(false);
        callback.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        callback.setSoTimeout(Math.toIntExact(SESSION_TTL.toMillis()));
        URI redirect = URI.create("http://127.0.0.1:" + callback.getLocalPort() + "/oauth/callback/" + id);
        ClientIdentity client;
        try {
            client = clientIdentity(configuration, metadata, redirect);
        } catch (Exception failure) {
            callback.close();
            throw failure;
        }
        String state = randomUrlToken(32);
        String verifier = randomUrlToken(64);
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        URI authorizationUrl = authorizationUrl(configuration, metadata, client, redirect, state, challenge);
        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        PendingAuthorization session = new PendingAuthorization(
                id,
                configuration,
                metadata,
                client,
                redirect,
                state,
                verifier,
                callback,
                expiresAt,
                new AtomicBoolean(true));
        if (pending.putIfAbsent(id, session) != null) {
            callback.close();
            throw new IllegalStateException("duplicate MCP authorization id");
        }
        Thread.ofVirtual().name("javaclaw-mcp-oauth-" + id).start(() -> accept(session));
        emitStatus(session, "PENDING");
        return new Authorization(id, authorizationUrl, expiresAt);
    }

    @Override
    public boolean cancel(String authorizationId) {
        PendingAuthorization value = pending.remove(authorizationId);
        if (value == null) {
            return false;
        }
        value.active().set(false);
        try {
            value.callback().close();
        } catch (IOException ignored) {
        }
        emitStatus(value, "CANCELLED");
        return true;
    }

    @Override
    public AutoCloseable onStatus(Consumer<AuthorizationStatus> listener) {
        Consumer<AuthorizationStatus> value = Objects.requireNonNull(listener, "listener");
        statusListeners.add(value);
        return () -> statusListeners.remove(value);
    }

    /** 创建按配置解析 OAuth 头的短生命周期提供者；发送前验证 issuer/resource 绑定并按需刷新，返回值不得暴露给客户端。 */
    public McpHttpAuthorization authorization(McpConfiguration configuration) {
        return () -> tokenHeaders(configuration);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.keySet().forEach(this::cancel);
    }

    private void accept(PendingAuthorization session) {
        boolean success = false;
        try (ServerSocket server = session.callback();
                Socket socket = server.accept()) {
            if (!socket.getInetAddress().isLoopbackAddress()) {
                throw new IOException("OAuth callback peer is not loopback");
            }
            socket.setSoTimeout(10_000);
            Callback callback = readCallback(socket, session);
            if (callback.error() != null) {
                sendPage(socket, 400, "Authorization was declined.");
                return;
            }
            // state 绑定浏览器往返，issuer 绑定授权服务器；两者不能互相替代，否则可能接受混淆来源的 code。
            if (session.metadata().authorizationResponseIssuer() && callback.issuer() == null) {
                throw new IOException("OAuth callback omitted issuer");
            }
            if (callback.issuer() != null
                    && !session.metadata()
                            .issuer()
                            .toString()
                            .equals(URI.create(callback.issuer()).normalize().toString())) {
                throw new IOException("OAuth callback issuer mismatch");
            }
            exchangeCode(session, callback.code());
            success = true;
            sendPage(socket, 200, "JavaClaw authorization completed. You can close this tab.");
        } catch (Exception ignored) {
            // Secrets and provider error bodies are deliberately not retained in diagnostics.
        } finally {
            boolean externallyCancelled = !session.active().get();
            session.active().set(false);
            pending.remove(session.id(), session);
            if (!success) {
                try {
                    session.callback().close();
                } catch (IOException ignored) {
                }
            }
            if (!externallyCancelled) {
                emitStatus(session, success ? "AUTHORIZED" : "FAILED");
            }
            session.destroy();
        }
    }

    private void emitStatus(PendingAuthorization session, String state) {
        AuthorizationStatus status =
                new AuthorizationStatus(session.id(), session.configuration().id(), state, Instant.now());
        statusListeners.forEach(listener -> {
            try {
                listener.accept(status);
            } catch (RuntimeException ignored) {
                // A disconnected client cannot affect authorization or secret persistence.
            }
        });
    }

    private Callback readCallback(Socket socket, PendingAuthorization session) throws IOException {
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII), 4096);
        String requestLine = reader.readLine();
        if (requestLine == null
                || requestLine.length() > MAX_CALLBACK_LINE
                || !requestLine.startsWith("GET ")
                || !requestLine.endsWith(" HTTP/1.1")) {
            throw new IOException("invalid OAuth callback request");
        }
        int headers = requestLine.length();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            headers += line.length();
            if (headers > MAX_CALLBACK_LINE) {
                throw new IOException("OAuth callback is too large");
            }
        }
        String target = requestLine.substring(4, requestLine.length() - 9);
        URI uri = URI.create("http://127.0.0.1" + target);
        if (!uri.getPath().equals(session.redirect().getPath())) {
            throw new IOException("OAuth callback path mismatch");
        }
        Map<String, String> query = query(uri.getRawQuery());
        if (!constantTime(session.state(), query.get("state"))) {
            throw new IOException("OAuth state mismatch");
        }
        String error = query.get("error");
        String code = query.get("code");
        if (error == null && (code == null || code.isBlank())) {
            throw new IOException("OAuth callback omitted code");
        }
        return new Callback(code, error, query.get("iss"));
    }

    private void exchangeCode(PendingAuthorization session, String code) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", session.redirect().toString());
        form.put("client_id", session.client().clientId());
        form.put("code_verifier", session.verifier());
        form.put("resource", session.metadata().resource().toString());
        Token token = tokenRequest(
                session.configuration(),
                session.metadata().tokenEndpoint(),
                session.client(),
                form,
                session.configuration().authentication().scopes(),
                null);
        storeToken(session.configuration(), session.metadata(), session.client(), token, "oauth-code-" + session.id());
    }

    private Map<String, String> tokenHeaders(McpConfiguration configuration) throws Exception {
        TokenBundle bundle = readToken(configuration);
        if (!bundle.resource().equals(configuration.endpoint().toString())) {
            throw new IllegalStateException("MCP OAuth token resource binding mismatch");
        }
        if (bundle.expiresAt().isBefore(Instant.now().plusSeconds(30))) {
            Object lock = refreshLocks.computeIfAbsent(configuration.id(), ignored -> new Object());
            // 同一 Server 的 refresh token 只能串行轮换；拿锁后重读，避免并发请求重复消费旧 token。
            synchronized (lock) {
                bundle = readToken(configuration);
                if (bundle.expiresAt().isBefore(Instant.now().plusSeconds(30))) {
                    bundle = refresh(configuration, bundle);
                }
            }
        }
        if (!"Bearer".equalsIgnoreCase(bundle.tokenType())) {
            throw new IllegalStateException("MCP OAuth token type is not Bearer");
        }
        return Map.of("Authorization", "Bearer " + bundle.accessToken());
    }

    private TokenBundle refresh(McpConfiguration configuration, TokenBundle bundle) throws Exception {
        if (bundle.refreshToken() == null || bundle.refreshToken().isBlank()) {
            throw new IllegalStateException("MCP OAuth token expired and cannot be refreshed");
        }
        ClientIdentity client = new ClientIdentity(bundle.clientId(), bundle.clientSecret(), bundle.tokenAuthMethod());
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", bundle.refreshToken());
        form.put("client_id", bundle.clientId());
        form.put("resource", bundle.resource());
        if (!bundle.scope().isBlank()) {
            form.put("scope", bundle.scope());
        }
        OAuthMetadata metadata = new OAuthMetadata(
                URI.create(bundle.resource()),
                URI.create(bundle.issuer()),
                URI.create(bundle.authorizationEndpoint()),
                URI.create(bundle.tokenEndpoint()),
                null,
                Set.of(),
                Set.of(),
                false);
        Token refreshed = tokenRequest(
                configuration, metadata.tokenEndpoint(), client, form, scopes(bundle.scope()), bundle.refreshToken());
        storeToken(configuration, metadata, client, refreshed, "oauth-refresh-" + UUID.randomUUID());
        return readToken(configuration);
    }

    private Token tokenRequest(
            McpConfiguration configuration,
            URI tokenEndpoint,
            ClientIdentity client,
            Map<String, String> parameters,
            Set<String> requestedScopes,
            String previousRefreshToken)
            throws Exception {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        if ("client_secret_basic".equals(client.tokenAuthMethod())) {
            if (client.clientSecret() == null) {
                throw new IllegalStateException("OAuth client secret is missing");
            }
            String basic = Base64.getEncoder()
                    .encodeToString((encode(client.clientId()) + ":" + encode(client.clientSecret()))
                            .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + basic);
        } else if (!"none".equals(client.tokenAuthMethod())) {
            throw new IllegalStateException("unsupported OAuth token authentication method");
        }
        JsonNode result = requestJson(
                configuration, "POST", tokenEndpoint, headers, form(parameters).getBytes(StandardCharsets.UTF_8));
        String access = requiredText(result, "access_token");
        String type = result.path("token_type").asText("Bearer");
        if (!"Bearer".equalsIgnoreCase(type)) {
            throw new IOException("OAuth token type is not Bearer");
        }
        if (result.has("expires_in")
                && (!result.path("expires_in").isIntegralNumber()
                        || !result.path("expires_in").canConvertToLong())) {
            throw new IOException("OAuth token expiry is invalid");
        }
        long expires = result.path("expires_in").asLong(3600);
        if (expires < 1 || expires > Duration.ofDays(365).toSeconds()) {
            throw new IOException("OAuth token expiry is invalid");
        }
        String refresh = text(result, "refresh_token");
        // 不接受刷新后复用旧 token 的响应；在写入 SecretStore 前验证轮换与 scope，失败不覆盖旧凭据。
        if (previousRefreshToken != null
                && (refresh == null || refresh.isBlank() || constantTime(previousRefreshToken, refresh))) {
            throw new IOException("OAuth refresh token was not rotated");
        }
        JsonNode scopeValue = result.get("scope");
        if (scopeValue != null && !scopeValue.isTextual()) {
            throw new IOException("OAuth token scope is invalid");
        }
        Set<String> granted = scopes(scopeValue == null ? "" : scopeValue.asText());
        Set<String> requested = requestedScopes == null ? Set.of() : Set.copyOf(requestedScopes);
        if (!requested.isEmpty() && !requested.containsAll(granted)) {
            throw new IOException("OAuth token expanded the requested scope");
        }
        String effectiveScope = canonicalScopes(granted.isEmpty() ? requested : granted);
        return new Token(access, refresh, type, expires, effectiveScope);
    }

    private void storeToken(
            McpConfiguration configuration,
            OAuthMetadata metadata,
            ClientIdentity client,
            Token token,
            String idempotencyKey)
            throws Exception {
        ObjectNode value = json.createObjectNode();
        value.put("accessToken", token.accessToken());
        if (token.refreshToken() != null) {
            value.put("refreshToken", token.refreshToken());
        }
        value.put("tokenType", token.tokenType());
        value.put("expiresAt", Instant.now().plusSeconds(token.expiresIn()).toString());
        value.put("issuer", metadata.issuer().toString());
        value.put("resource", metadata.resource().toString());
        value.put("authorizationEndpoint", metadata.authorizationEndpoint().toString());
        value.put("tokenEndpoint", metadata.tokenEndpoint().toString());
        value.put("clientId", client.clientId());
        if (client.clientSecret() != null) {
            value.put("clientSecret", client.clientSecret());
        }
        value.put("tokenAuthMethod", client.tokenAuthMethod());
        value.put("scope", token.scope());
        char[] encoded = json.writeValueAsString(value).toCharArray();
        try {
            secrets.put(namespace(configuration.id()), TOKEN_SECRET, encoded, idempotencyKey);
        } finally {
            Arrays.fill(encoded, '\0');
        }
    }

    private TokenBundle readToken(McpConfiguration configuration) throws Exception {
        char[] encoded = secrets.resolve(namespace(configuration.id()), TOKEN_SECRET)
                .orElseThrow(() -> new IllegalStateException("MCP OAuth authorization is required"));
        try {
            JsonNode value = json.readTree(new String(encoded));
            return new TokenBundle(
                    requiredText(value, "accessToken"),
                    text(value, "refreshToken"),
                    requiredText(value, "tokenType"),
                    Instant.parse(requiredText(value, "expiresAt")),
                    requiredText(value, "issuer"),
                    requiredText(value, "resource"),
                    requiredText(value, "authorizationEndpoint"),
                    requiredText(value, "tokenEndpoint"),
                    requiredText(value, "clientId"),
                    text(value, "clientSecret"),
                    requiredText(value, "tokenAuthMethod"),
                    value.path("scope").asText(""));
        } finally {
            Arrays.fill(encoded, '\0');
        }
    }

    private OAuthMetadata discover(McpConfiguration configuration) throws Exception {
        URI endpoint = configuration.endpoint();
        URI protectedMetadata = wellKnown(endpoint, "oauth-protected-resource");
        JsonNode resource =
                requestJson(configuration, "GET", protectedMetadata, Map.of("Accept", "application/json"), new byte[0]);
        URI resourceId = https(requiredText(resource, "resource"));
        if (!resourceId.equals(endpoint)) {
            throw new IOException("OAuth protected resource metadata is not endpoint-bound");
        }
        JsonNode servers = resource.path("authorization_servers");
        if (!servers.isArray() || servers.isEmpty()) {
            throw new IOException("OAuth protected resource metadata has no issuer");
        }
        URI issuer = issuer(servers.get(0).asText());
        URI wellKnown = wellKnown(issuer, "oauth-authorization-server");
        JsonNode as = requestJson(configuration, "GET", wellKnown, Map.of("Accept", "application/json"), new byte[0]);
        URI declaredIssuer = issuer(requiredText(as, "issuer"));
        if (!declaredIssuer.equals(issuer)) {
            throw new IOException("OAuth issuer mismatch");
        }
        URI authorizationEndpoint = https(requiredText(as, "authorization_endpoint"));
        URI tokenEndpoint = https(requiredText(as, "token_endpoint"));
        URI registrationEndpoint =
                text(as, "registration_endpoint") == null ? null : https(text(as, "registration_endpoint"));
        Set<String> challengeMethods = strings(as.path("code_challenge_methods_supported"));
        if (!challengeMethods.contains("S256")) {
            throw new IOException("OAuth authorization server does not support PKCE S256");
        }
        return new OAuthMetadata(
                resourceId,
                issuer,
                authorizationEndpoint,
                tokenEndpoint,
                registrationEndpoint,
                strings(resource.path("scopes_supported")),
                strings(as.path("scopes_supported")),
                as.path("authorization_response_iss_parameter_supported").asBoolean(false));
    }

    private ClientIdentity clientIdentity(McpConfiguration configuration, OAuthMetadata metadata, URI redirect)
            throws Exception {
        String configured = configuration.authentication().clientId();
        if (configured != null) {
            String secretName = configuration.authentication().credentialName();
            boolean metadataDocument = configured.startsWith("https://");
            if (metadataDocument) {
                URI document = https(configured);
                if (document.getRawQuery() != null || secretName != null) {
                    throw new IllegalArgumentException("OAuth client metadata document cannot use query or secret");
                }
                configured = document.toString();
            }
            String secret = null;
            if (secretName != null) {
                char[] chars = secrets.resolve(namespace(configuration.id()), secretName)
                        .orElseThrow(() -> new IllegalStateException("MCP OAuth client secret is not configured"));
                try {
                    secret = new String(chars);
                } finally {
                    Arrays.fill(chars, '\0');
                }
            }
            return new ClientIdentity(configured, secret, secret == null ? "none" : "client_secret_basic");
        }
        if (metadata.registrationEndpoint() == null) {
            throw new IllegalStateException("MCP OAuth needs a client id and server does not support DCR");
        }
        ObjectNode request = json.createObjectNode();
        request.putArray("redirect_uris").add(redirect.toString());
        request.put("client_name", "JavaClaw");
        request.put("token_endpoint_auth_method", "none");
        request.putArray("grant_types").add("authorization_code").add("refresh_token");
        request.putArray("response_types").add("code");
        JsonNode response = requestJson(
                configuration,
                "POST",
                metadata.registrationEndpoint(),
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                json.writeValueAsBytes(request));
        String secret = text(response, "client_secret");
        String authMethod =
                response.path("token_endpoint_auth_method").asText(secret == null ? "none" : "client_secret_basic");
        if (!(authMethod.equals("none") || authMethod.equals("client_secret_basic"))) {
            throw new IOException("DCR returned an unsupported token auth method");
        }
        if (authMethod.equals("client_secret_basic") && secret == null) {
            throw new IOException("DCR omitted the client secret");
        }
        if (authMethod.equals("none") && secret != null) {
            throw new IOException("DCR returned a secret for a public client");
        }
        return new ClientIdentity(requiredText(response, "client_id"), secret, authMethod);
    }

    private URI authorizationUrl(
            McpConfiguration configuration,
            OAuthMetadata metadata,
            ClientIdentity client,
            URI redirect,
            String state,
            String challenge) {
        Set<String> requested = configuration.authentication().scopes();
        Set<String> supported =
                metadata.resourceScopes().isEmpty() ? metadata.authorizationScopes() : metadata.resourceScopes();
        if (!supported.isEmpty() && !supported.containsAll(requested)) {
            throw new IllegalArgumentException("requested MCP OAuth scope is not supported");
        }
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", client.clientId());
        query.put("redirect_uri", redirect.toString());
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        query.put("state", state);
        query.put("resource", metadata.resource().toString());
        if (!requested.isEmpty()) {
            query.put("scope", canonicalScopes(requested));
        }
        String separator = metadata.authorizationEndpoint().getRawQuery() == null ? "?" : "&";
        return URI.create(metadata.authorizationEndpoint() + separator + form(query));
    }

    private JsonNode requestJson(
            McpConfiguration configuration, String method, URI uri, Map<String, String> headers, byte[] body)
            throws Exception {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("OAuth metadata and token endpoints must use HTTPS");
        }
        BrokerResponse response = brokers.apply(configuration)
                .execute(
                        new BrokerRequest(method, uri, headers, body, HTTP_TIMEOUT, MAX_METADATA_BYTES, 3, true),
                        new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, configuration.networkAllowlist()));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OAuth endpoint returned HTTP " + response.statusCode());
        }
        JsonNode value = json.readTree(response.body());
        if (value == null || !value.isObject()) {
            throw new IOException("OAuth response is invalid");
        }
        return value;
    }

    private static void sendPage(Socket socket, int status, String message) {
        try {
            byte[] body = ("<!doctype html><meta charset=utf-8><title>JavaClaw</title><p>" + message + "</p>")
                    .getBytes(StandardCharsets.UTF_8);
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            writer.write("HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
                    + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n");
            writer.flush();
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        } catch (IOException ignored) {
        }
    }

    private static Map<String, String> query(String raw) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            String name = decode(separator < 0 ? part : part.substring(0, separator));
            String value = decode(separator < 0 ? "" : part.substring(separator + 1));
            if (result.putIfAbsent(name, value) != null) {
                throw new IOException("duplicate OAuth callback parameter");
            }
        }
        return result;
    }

    private static String form(Map<String, String> values) {
        ArrayList<String> parts = new ArrayList<>();
        values.forEach((name, value) -> parts.add(encode(name) + "=" + encode(value)));
        return String.join("&", parts);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String value) throws IOException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid OAuth query", failure);
        }
    }

    private String randomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean constantTime(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static URI wellKnown(URI resource, String name) throws Exception {
        String path = resource.getRawPath();
        if (path == null || path.equals("/")) {
            path = "";
        }
        return new URI(
                        resource.getScheme(),
                        null,
                        resource.getHost(),
                        resource.getPort(),
                        "/.well-known/" + name + path,
                        null,
                        null)
                .normalize();
    }

    private static URI https(String value) throws IOException {
        final URI result;
        try {
            result = URI.create(value).normalize();
        } catch (IllegalArgumentException failure) {
            throw new IOException("OAuth endpoint is not a valid URL", failure);
        }
        if (!"https".equalsIgnoreCase(result.getScheme())
                || result.getHost() == null
                || result.getUserInfo() != null
                || result.getFragment() != null) {
            throw new IOException("OAuth endpoint is not a safe HTTPS URL");
        }
        return result;
    }

    private static URI issuer(String value) throws IOException {
        URI result = https(value);
        if (result.getRawQuery() != null) {
            throw new IOException("OAuth issuer must not contain a query");
        }
        return result;
    }

    private static Set<String> scopes(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String scope : value.strip().split("\\s+")) {
            if (!scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,256}")) {
                throw new IOException("OAuth scope contains an invalid token");
            }
            result.add(scope);
        }
        return Set.copyOf(result);
    }

    private static String canonicalScopes(Set<String> scopes) {
        return scopes.stream().sorted().collect(java.util.stream.Collectors.joining(" "));
    }

    private static Set<String> strings(JsonNode value) {
        if (!value.isArray()) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        value.forEach(item -> {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        });
        return Set.copyOf(result);
    }

    private static String requiredText(JsonNode value, String field) throws IOException {
        String result = text(value, field);
        if (result == null || result.isBlank()) {
            throw new IOException("OAuth response omitted " + field);
        }
        return result;
    }

    private static String text(JsonNode value, String field) {
        JsonNode found = value == null ? null : value.get(field);
        return found != null && found.isTextual() ? found.asText() : null;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("MCP OAuth service is closed");
        }
    }

    private static String namespace(String id) {
        return "mcp:" + id;
    }

    private record OAuthMetadata(
            URI resource,
            URI issuer,
            URI authorizationEndpoint,
            URI tokenEndpoint,
            URI registrationEndpoint,
            Set<String> resourceScopes,
            Set<String> authorizationScopes,
            boolean authorizationResponseIssuer) {}

    private record ClientIdentity(String clientId, String clientSecret, String tokenAuthMethod) {}

    private record Token(String accessToken, String refreshToken, String tokenType, long expiresIn, String scope) {}

    private record TokenBundle(
            String accessToken,
            String refreshToken,
            String tokenType,
            Instant expiresAt,
            String issuer,
            String resource,
            String authorizationEndpoint,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String tokenAuthMethod,
            String scope) {}

    private record Callback(String code, String error, String issuer) {}

    private record PendingAuthorization(
            String id,
            McpConfiguration configuration,
            OAuthMetadata metadata,
            ClientIdentity client,
            URI redirect,
            String state,
            String verifier,
            ServerSocket callback,
            Instant expiresAt,
            AtomicBoolean active) {
        void destroy() {
            /* Strings are immutable; lifetime is bounded to the callback thread. */
        }
    }
}
