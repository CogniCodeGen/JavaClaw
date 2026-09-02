package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpOAuthExchange;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokeredMcpOAuthValidationTest {
    private static final URI REDIRECT = URI.create("http://127.0.0.1:17845/oauth/callback");
    private static final String CHALLENGE = "c".repeat(43);
    private static final String STATE = "s".repeat(43);

    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 授权启动在网络前拒绝不安全PkceState与Redirect() {
        ScenarioExchange exchange = new ScenarioExchange();
        BrokeredMcpOAuthPort broker = broker(exchange);

        assertThrows(
                IllegalArgumentException.class,
                () -> broker.authorizationRequest(endpoint(), "short", STATE, REDIRECT));
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.authorizationRequest(endpoint(), "!".repeat(43), STATE, REDIRECT));
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.authorizationRequest(endpoint(), CHALLENGE, "short", REDIRECT));
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.authorizationRequest(endpoint(), CHALLENGE, "!".repeat(43), REDIRECT));
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.authorizationRequest(
                        endpoint(), CHALLENGE, STATE, URI.create("https://127.0.0.1:17845/oauth/callback")));
        assertEquals(0, exchange.requests.size());
    }

    @Test
    void 资源Metadata必须声明唯一Https授权服务() {
        for (String resource : List.of(
                "{}",
                "{\"authorization_servers\":[]}",
                "{\"authorization_servers\":[\"https://one.example\",\"https://two.example\"]}",
                "{\"authorization_servers\":[\"http://auth.example\"]}")) {
            ScenarioExchange exchange = new ScenarioExchange();
            exchange.resourceBody = resource;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> broker(exchange).authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT));
        }
    }

    @Test
    void 授权Metadata固定Issuer同源端点和Pkce能力() {
        assertMetadataRejected(invalidEndpointMetadata());
        assertMetadataRejected(invalidCapabilityMetadata());
    }

    private void assertMetadataRejected(List<String> invalidMetadata) {
        for (String metadata : invalidMetadata) {
            ScenarioExchange exchange = new ScenarioExchange();
            exchange.metadataBody = metadata;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> broker(exchange).authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT));
        }
    }

    private List<String> invalidEndpointMetadata() {
        return List.of(
                """
                {"authorization_endpoint":"https://auth.example/authorize",
                "token_endpoint":"https://auth.example/token","registration_endpoint":"https://auth.example/register",
                "code_challenge_methods_supported":["S256"]}
                """,
                metadata(
                        "https://other.example",
                        "https://auth.example/authorize",
                        "https://auth.example/token",
                        "https://auth.example/register",
                        "[\"S256\"]",
                        "[]",
                        "[]"),
                metadata(
                        "https://auth.example",
                        "https://other.example/authorize",
                        "https://auth.example/token",
                        "https://auth.example/register",
                        "[\"S256\"]",
                        "[]",
                        "[]"),
                metadata(
                        "https://auth.example",
                        "https://auth.example/authorize",
                        "https://other.example/token",
                        "https://auth.example/register",
                        "[\"S256\"]",
                        "[]",
                        "[]"),
                metadata(
                        "https://auth.example",
                        "https://auth.example/authorize",
                        "https://auth.example/token",
                        "https://other.example/register",
                        "[\"S256\"]",
                        "[]",
                        "[]"));
    }

    private List<String> invalidCapabilityMetadata() {
        return List.of(
                metadata(
                        "https://auth.example",
                        "https://auth.example/authorize",
                        "https://auth.example/token",
                        "https://auth.example/register",
                        "[]",
                        "[]",
                        "[]"),
                metadata(
                        "https://auth.example",
                        "https://auth.example/authorize",
                        "https://auth.example/token",
                        "https://auth.example/register",
                        "[\"S256\"]",
                        "[\"client_credentials\"]",
                        "[]"),
                metadata(
                        "https://auth.example",
                        "https://auth.example/authorize",
                        "https://auth.example/token",
                        "https://auth.example/register",
                        "[\"S256\"]",
                        "[]",
                        "[\"token\"]"));
    }

    @Test
    void Issuer路径尾斜杠与授权端点已有查询仍能规范生成请求() throws Exception {
        ScenarioExchange exchange = new ScenarioExchange();
        exchange.resourceBody = "{\"authorization_servers\":[\"https://auth.example/tenant/\"]}";
        exchange.metadataBody = metadata(
                "https://auth.example/tenant",
                "https://auth.example/authorize?audience=docs",
                "https://auth.example/token",
                "https://auth.example/register",
                "[\"S256\"]",
                "[]",
                "[]");

        URI authorization = broker(exchange)
                .authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT)
                .authorizationUri();

        assertTrue(authorization.getRawQuery().startsWith("audience=docs&"));
        assertTrue(exchange.requests.get(1).uri().getPath().endsWith("/tenant/"));
        assertEquals(Duration.ofSeconds(30), exchange.requests.getFirst().timeout());
    }

    @Test
    void Broker响应必须成功完整Json且Utf8合法() {
        List<BrokerResponse> invalidResponses = List.of(
                response(500, "application/json", "{}".getBytes(StandardCharsets.UTF_8), false),
                response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), true),
                response(200, "text/plain", "{}".getBytes(StandardCharsets.UTF_8), false),
                response(200, "application/json", new byte[] {(byte) 0xc3, (byte) 0x28}, false));

        for (BrokerResponse invalid : invalidResponses) {
            BrokeredMcpOAuthPort.Exchange exchange = (endpoint, request, cancellation) -> invalid;
            assertThrows(
                    Exception.class,
                    () -> broker(exchange).authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT));
        }
    }

    @Test
    void 动态注册必须返回有界非空ClientId() {
        for (String registration : List.of("{}", "{\"client_id\":\"\"}", clientId("x".repeat(513)))) {
            ScenarioExchange exchange = new ScenarioExchange();
            exchange.registrationBody = registration;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> broker(exchange).authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT));
        }
    }

    @Test
    void Callback必须精确匹配且唯一携带Code与State() throws Exception {
        ScenarioExchange exchange = new ScenarioExchange();
        BrokeredMcpOAuthPort broker = broker(exchange);
        URI authorization = broker.authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT)
                .authorizationUri();
        int requestsAfterStart = exchange.requests.size();
        List<URI> callbacks = List.of(
                URI.create("http://127.0.0.1:17846/oauth/callback?code=one&state=" + STATE),
                URI.create(REDIRECT + "?error=denied&state=" + STATE),
                URI.create(REDIRECT + "?code=one"),
                URI.create(REDIRECT + "?code=one&state=" + STATE + "&state=" + STATE),
                URI.create(REDIRECT + "?state=" + STATE),
                URI.create(REDIRECT + "?code=&state=" + STATE));

        for (URI callback : callbacks) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> broker.exchange(completion(authorization, callback, CHALLENGE, STATE)));
        }
        assertEquals(requestsAfterStart, exchange.requests.size());

        URI withoutClient = URI.create("https://auth.example/authorize?state=" + STATE);
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.exchange(completion(
                        withoutClient, URI.create(REDIRECT + "?code=one&state=" + STATE), CHALLENGE, STATE)));
        assertEquals(requestsAfterStart, exchange.requests.size());
    }

    @Test
    void Token响应只接受Bearer与有界无控制字符AccessToken() throws Exception {
        List<String> invalidTokens = List.of(
                "{}",
                "{\"access_token\":\"token\"}",
                "{\"access_token\":\"token\",\"token_type\":\"MAC\"}",
                "{\"access_token\":\"\",\"token_type\":\"Bearer\"}",
                "{\"access_token\":\"bad\\nvalue\",\"token_type\":\"Bearer\"}",
                json.encode(Map.of("access_token", "x".repeat(16_385), "token_type", "Bearer"))
                        .json(),
                json.encode(Map.of("access_token", "é".repeat(9_000), "token_type", "Bearer"))
                        .json());

        for (String tokenBody : invalidTokens) {
            ScenarioExchange exchange = new ScenarioExchange();
            BrokeredMcpOAuthPort broker = broker(exchange);
            URI authorization = broker.authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT)
                    .authorizationUri();
            exchange.tokenBody = tokenBody;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> broker.exchange(completion(
                            authorization, URI.create(REDIRECT + "?code=one&state=" + STATE), CHALLENGE, STATE)));
        }
    }

    @Test
    void Token类型忽略大小写且Json后缀ContentType可用() throws Exception {
        ScenarioExchange exchange = new ScenarioExchange();
        exchange.contentType = "application/oauth+json";
        exchange.tokenBody = "{\"access_token\":\"safe-token\",\"token_type\":\"bEaReR\"}";
        BrokeredMcpOAuthPort broker = broker(exchange);
        URI authorization = broker.authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT)
                .authorizationUri();

        byte[] token = broker.exchange(
                completion(authorization, URI.create(REDIRECT + "?code=one%20time&state=" + STATE), CHALLENGE, STATE));

        assertArrayEquals("safe-token".getBytes(StandardCharsets.UTF_8), token);
        String form = new String(exchange.requests.getLast().body(), StandardCharsets.UTF_8);
        assertTrue(form.contains("code=one+time"));
    }

    private BrokeredMcpOAuthPort broker(BrokeredMcpOAuthPort.Exchange exchange) {
        return new BrokeredMcpOAuthPort(exchange, json);
    }

    private static McpOAuthExchange completion(URI authorization, URI callback, String verifier, String expectedState) {
        return new McpOAuthExchange(
                endpoint(), authorization, callback, verifier, expectedState, REDIRECT, new CancellationSource());
    }

    private static McpEndpoint endpoint() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        McpEndpointSpec spec = new McpEndpointSpec(
                WorkspaceId.random(),
                "OAuth MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example/rpc")),
                Optional.empty(),
                McpAuthType.OAUTH_2_1_PKCE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(45));
        return new McpEndpoint("oauth-endpoint", 1, McpEndpointState.ENABLED, 0, spec, now, now);
    }

    private static String metadata(
            String issuer,
            String authorization,
            String token,
            String registration,
            String challenges,
            String grants,
            String responses) {
        return """
                {"issuer":"%s","authorization_endpoint":"%s","token_endpoint":"%s",
                "registration_endpoint":"%s","code_challenge_methods_supported":%s,
                "grant_types_supported":%s,"response_types_supported":%s}
                """.formatted(issuer, authorization, token, registration, challenges, grants, responses);
    }

    private static String clientId(String value) {
        return "{\"client_id\":\"" + value + "\"}";
    }

    private static BrokerResponse response(int status, String contentType, byte[] body, boolean truncated) {
        return new BrokerResponse(status, Map.of("content-type", List.of(contentType)), body, truncated);
    }

    private static final class ScenarioExchange implements BrokeredMcpOAuthPort.Exchange {
        private final List<BrokerRequest> requests = new ArrayList<>();
        private String resourceBody = "{\"authorization_servers\":[\"https://auth.example\"]}";
        private String metadataBody = metadata(
                "https://auth.example",
                "https://auth.example/authorize",
                "https://auth.example/token",
                "https://auth.example/register",
                "[\"S256\"]",
                "[\"authorization_code\"]",
                "[\"code\"]");
        private String registrationBody = "{\"client_id\":\"client-123\"}";
        private String tokenBody = "{\"access_token\":\"token\",\"token_type\":\"Bearer\"}";
        private String contentType = "application/json";

        @Override
        public BrokerResponse exchange(
                McpEndpoint endpoint, BrokerRequest request, com.javaclaw.api.CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            requests.add(request);
            String path = request.uri().getPath();
            String body;
            if (path.equals("/.well-known/oauth-protected-resource")) {
                body = resourceBody;
            } else if (path.contains("/.well-known/oauth-authorization-server")) {
                body = metadataBody;
            } else if (path.equals("/register")) {
                body = registrationBody;
            } else if (path.equals("/token")) {
                body = tokenBody;
            } else {
                throw new AssertionError("unexpected OAuth request: " + request.uri());
            }
            return response(200, contentType, body.getBytes(StandardCharsets.UTF_8), false);
        }
    }
}
