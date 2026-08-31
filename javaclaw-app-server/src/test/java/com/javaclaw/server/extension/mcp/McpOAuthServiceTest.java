package com.javaclaw.server.extension.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.BrokerResponse;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.server.security.SecretStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOAuthServiceTest {
    private static final URI ENDPOINT = URI.create("https://mcp.example.test/rpc");
    private static final URI ISSUER = URI.create("https://auth.example.test/issuer");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void completesPkceFlowWithIssuerAndResourceBindingWithoutLeakingTokens() throws Exception {
        InMemorySecrets secrets = new InMemorySecrets();
        OAuthBroker broker = new OAuthBroker(3600, "refresh-1", "read");
        CopyOnWriteArrayList<String> states = new CopyOnWriteArrayList<>();
        CountDownLatch statusEvents = new CountDownLatch(2);
        try (McpOAuthService oauth = new McpOAuthService(fixed(broker), secrets, json);
                AutoCloseable ignored = oauth.onStatus(status -> {
                    states.add(status.state());
                    statusEvents.countDown();
                })) {
            var started = oauth.start(configuration());
            Map<String, String> query = query(started.authorizationUrl().getRawQuery());
            assertEquals("S256", query.get("code_challenge_method"));
            assertEquals(ENDPOINT.toString(), query.get("resource"));
            assertEquals("read", query.get("scope"));
            assertTrue(query.get("code_challenge").length() >= 43);
            assertFalse(started.authorizationUrl().toString().contains("access-"));

            String page = callback(query, "authorization-code", ISSUER.toString());
            assertTrue(page.startsWith("HTTP/1.1 200"));
            assertEquals(
                    "Bearer access-1",
                    oauth.authorization(configuration()).headers().get("Authorization"));

            BrokerRequest token = broker.lastTokenRequest.get();
            assertTrue(token.tlsRequired());
            String form = new String(token.body(), StandardCharsets.UTF_8);
            assertTrue(form.contains("code_verifier="));
            assertTrue(form.contains("resource=" + encoded(ENDPOINT.toString())));
            assertFalse(form.contains("access-1"));
            assertFalse(token.headers().containsKey("Authorization"));
            assertTrue(
                    secrets.metadata("mcp:server", "oauth-token").orElseThrow().configured());
            assertTrue(statusEvents.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("PENDING", "AUTHORIZED"), states);
        }
    }

    @Test
    void cancellationPublishesOneTerminalStatusWithoutASecondFailure() throws Exception {
        InMemorySecrets secrets = new InMemorySecrets();
        OAuthBroker broker = new OAuthBroker(3600, "refresh-1", "read");
        CopyOnWriteArrayList<String> states = new CopyOnWriteArrayList<>();
        CountDownLatch statuses = new CountDownLatch(2);
        try (McpOAuthService oauth = new McpOAuthService(fixed(broker), secrets, json);
                AutoCloseable ignored = oauth.onStatus(status -> {
                    states.add(status.state());
                    statuses.countDown();
                })) {
            var started = oauth.start(configuration());

            assertTrue(oauth.cancel(started.authorizationId()));
            assertTrue(statuses.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertEquals(List.of("PENDING", "CANCELLED"), states);
        }
    }

    @Test
    void rejectsIssuerMixupAndExpandedTokenScope() throws Exception {
        InMemorySecrets secrets = new InMemorySecrets();
        OAuthBroker broker = new OAuthBroker(3600, "refresh-1", "read admin");
        try (McpOAuthService oauth = new McpOAuthService(fixed(broker), secrets, json)) {
            var started = oauth.start(configuration());
            Map<String, String> query = query(started.authorizationUrl().getRawQuery());
            String page = callback(query, "authorization-code", "https://evil.example/issuer");
            assertFalse(page.startsWith("HTTP/1.1 200"));
            assertTrue(secrets.resolve("mcp:server", "oauth-token").isEmpty());
        }

        secrets = new InMemorySecrets();
        broker = new OAuthBroker(3600, "refresh-1", "read admin");
        try (McpOAuthService oauth = new McpOAuthService(fixed(broker), secrets, json)) {
            Map<String, String> query =
                    query(oauth.start(configuration()).authorizationUrl().getRawQuery());
            String page = callback(query, "authorization-code", ISSUER.toString());
            assertFalse(page.startsWith("HTTP/1.1 200"));
            assertTrue(secrets.resolve("mcp:server", "oauth-token").isEmpty());
        }
    }

    @Test
    void rotatesRefreshTokensAndRejectsReuse() throws Exception {
        InMemorySecrets secrets = new InMemorySecrets();
        OAuthBroker broker = new OAuthBroker(1, "refresh-old", "read");
        broker.refreshToken = "refresh-new";
        try (McpOAuthService oauth = new McpOAuthService(fixed(broker), secrets, json)) {
            Map<String, String> query =
                    query(oauth.start(configuration()).authorizationUrl().getRawQuery());
            assertTrue(callback(query, "code", ISSUER.toString()).startsWith("HTTP/1.1 200"));
            assertEquals(
                    "Bearer access-2",
                    oauth.authorization(configuration()).headers().get("Authorization"));
            assertEquals(1, broker.refreshes.get());
            assertTrue(new String(broker.lastTokenRequest.get().body(), StandardCharsets.UTF_8)
                    .contains("refresh_token=refresh-old"));
            assertNotEquals("refresh-old", broker.refreshToken);
        }

        secrets = new InMemorySecrets();
        broker = new OAuthBroker(1, "refresh-old", "read");
        broker.refreshToken = "refresh-old";
        try (McpOAuthService oauth = new McpOAuthService(fixed(broker), secrets, json)) {
            Map<String, String> query =
                    query(oauth.start(configuration()).authorizationUrl().getRawQuery());
            assertTrue(callback(query, "code", ISSUER.toString()).startsWith("HTTP/1.1 200"));
            McpHttpAuthorization authorization = oauth.authorization(configuration());
            assertThrows(Exception.class, authorization::headers);
        }
    }

    private static java.util.function.Function<McpConfiguration, NetworkBroker> fixed(NetworkBroker broker) {
        return ignored -> broker;
    }

    private McpConfiguration configuration() {
        return new McpConfiguration(
                "server",
                null,
                "Server",
                1,
                true,
                McpConfiguration.Transport.HTTP,
                ENDPOINT,
                null,
                Set.of("mcp.example.test", "auth.example.test"),
                new McpConfiguration.Authentication(
                        McpConfiguration.AuthenticationType.OAUTH, null, null, "javaclaw-client", Set.of("read")),
                Duration.ofSeconds(30),
                1024 * 1024,
                null);
    }

    private static String callback(Map<String, String> authorizationQuery, String code, String issuer)
            throws Exception {
        URI redirect = URI.create(authorizationQuery.get("redirect_uri"));
        String target = redirect.getRawPath() + "?code=" + encoded(code)
                + "&state=" + encoded(authorizationQuery.get("state"))
                + "&iss=" + encoded(issuer);
        try (Socket socket = new Socket(redirect.getHost(), redirect.getPort())) {
            socket.setSoTimeout(3_000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            try (InputStream input = socket.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static Map<String, String> query(String raw) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            result.put(
                    URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private final class OAuthBroker implements NetworkBroker {
        private final int initialExpiry;
        private final String initialRefresh;
        private final String initialScope;
        private final AtomicInteger tokens = new AtomicInteger();
        private final AtomicInteger refreshes = new AtomicInteger();
        private final AtomicReference<BrokerRequest> lastTokenRequest = new AtomicReference<>();
        private volatile String refreshToken;

        private OAuthBroker(int initialExpiry, String initialRefresh, String initialScope) {
            this.initialExpiry = initialExpiry;
            this.initialRefresh = initialRefresh;
            this.initialScope = initialScope;
            refreshToken = "refresh-new";
        }

        @Override
        public BrokerResponse execute(BrokerRequest request, NetworkPolicy ignored) throws java.io.IOException {
            assertTrue(request.tlsRequired());
            ObjectNode result = json.createObjectNode();
            String path = request.uri().getPath();
            if (path.equals("/.well-known/oauth-protected-resource/rpc")) {
                result.put("resource", ENDPOINT.toString());
                result.putArray("authorization_servers").add(ISSUER.toString());
                result.putArray("scopes_supported").add("read");
            } else if (path.equals("/.well-known/oauth-authorization-server/issuer")) {
                result.put("issuer", ISSUER.toString());
                result.put(
                        "authorization_endpoint", ISSUER.resolve("/authorize").toString());
                result.put("token_endpoint", ISSUER.resolve("/token").toString());
                result.putArray("code_challenge_methods_supported").add("S256");
                result.putArray("scopes_supported").add("read");
                result.put("authorization_response_iss_parameter_supported", true);
            } else if (path.equals("/token")) {
                lastTokenRequest.set(request);
                String body = new String(request.body(), StandardCharsets.UTF_8);
                boolean refresh = body.contains("grant_type=refresh_token");
                int ordinal = tokens.incrementAndGet();
                if (refresh) {
                    refreshes.incrementAndGet();
                }
                result.put("access_token", "access-" + ordinal);
                result.put("token_type", "Bearer");
                result.put("expires_in", refresh ? 3600 : initialExpiry);
                result.put("refresh_token", refresh ? refreshToken : initialRefresh);
                result.put("scope", refresh ? "read" : initialScope);
            } else {
                throw new java.io.IOException("unexpected OAuth URI " + request.uri());
            }
            try {
                return new BrokerResponse(
                        200,
                        request.uri(),
                        Map.of("content-type", List.of("application/json")),
                        json.writeValueAsBytes(result),
                        0);
            } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
                throw new java.io.IOException(impossible);
            }
        }
    }

    private static final class InMemorySecrets implements SecretStore {
        private final Map<String, char[]> values = new ConcurrentHashMap<>();
        private final Map<String, SecretMetadata> metadata = new ConcurrentHashMap<>();

        @Override
        public SecretMetadata put(String namespace, String name, char[] value, String ignored) {
            String key = namespace + "\u0000" + name;
            char[] copy = value.clone();
            char[] old = values.put(key, copy);
            if (old != null) {
                Arrays.fill(old, '\0');
            }
            long revision = metadata.containsKey(key) ? metadata.get(key).revision() + 1 : 1;
            SecretMetadata result = new SecretMetadata(namespace, name, true, revision, Instant.now());
            metadata.put(key, result);
            return result;
        }

        @Override
        public Optional<char[]> resolve(String namespace, String name) {
            char[] value = values.get(namespace + "\u0000" + name);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public Optional<SecretMetadata> metadata(String namespace, String name) {
            return Optional.ofNullable(metadata.get(namespace + "\u0000" + name));
        }

        @Override
        public boolean remove(String namespace, String name, long ignoredRevision, String ignoredKey) {
            String key = namespace + "\u0000" + name;
            char[] removed = values.remove(key);
            metadata.remove(key);
            if (removed != null) {
                Arrays.fill(removed, '\0');
            }
            return removed != null;
        }

        @Override
        public int rotateMasterKey() {
            return 1;
        }
    }
}
