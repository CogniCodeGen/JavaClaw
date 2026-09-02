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
import com.javaclaw.extension.spi.McpOAuthAuthorizationRequest;
import com.javaclaw.extension.spi.McpOAuthExchange;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokeredMcpOAuthPortTest {
    private static final URI REDIRECT = URI.create("http://127.0.0.1:17845/oauth/callback");
    private static final String CHALLENGE = "0123456789012345678901234567890123456789012";
    private static final String STATE = "abcdefghijklmnopqrstuvwxyz0123456789ABCDE";

    @Test
    void metadata动态注册与Pkce交换全程走受控Broker() throws Exception {
        FakeExchange exchange = new FakeExchange(false);
        BrokeredMcpOAuthPort broker = new BrokeredMcpOAuthPort(exchange, new CanonicalJson());

        McpOAuthAuthorizationRequest request = broker.authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT);
        URI authorization = request.authorizationUri();

        assertEquals("https", authorization.getScheme());
        assertEquals(java.util.Set.of(URI.create("https://auth.example")), request.allowedOrigins());
        assertTrue(authorization.getRawQuery().contains("code_challenge_method=S256"));
        assertTrue(authorization.getRawQuery().contains("client_id=client-123"));
        assertEquals(3, exchange.requests.size());
        String registration = new String(exchange.requests.getLast().body(), StandardCharsets.UTF_8);
        assertTrue(registration.contains("\"token_endpoint_auth_method\":\"none\""));

        URI callback = URI.create(REDIRECT + "?code=one-time-code&state=" + STATE);
        byte[] token = broker.exchange(new McpOAuthExchange(
                endpoint(), authorization, callback, CHALLENGE, STATE, REDIRECT, new CancellationSource()));

        assertArrayEquals("vault-only-token".getBytes(StandardCharsets.UTF_8), token);
        assertEquals(6, exchange.requests.size());
        String tokenForm = new String(exchange.requests.getLast().body(), StandardCharsets.UTF_8);
        assertTrue(tokenForm.contains("code_verifier=" + CHALLENGE));
        assertTrue(tokenForm.contains("client_id=client-123"));
        assertFalse(authorization.toString().contains("vault-only-token"));
    }

    @Test
    void state不匹配时在发起Token网络请求前拒绝() throws Exception {
        FakeExchange exchange = new FakeExchange(false);
        BrokeredMcpOAuthPort broker = new BrokeredMcpOAuthPort(exchange, new CanonicalJson());
        URI authorization = broker.authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT)
                .authorizationUri();
        int requestsAfterStart = exchange.requests.size();

        McpOAuthExchange completion = new McpOAuthExchange(
                endpoint(),
                authorization,
                URI.create(REDIRECT + "?code=one-time-code&state=wrong-state-value-01234567890123456789"),
                CHALLENGE,
                STATE,
                REDIRECT,
                new CancellationSource());

        assertThrows(IllegalArgumentException.class, () -> broker.exchange(completion));
        assertEquals(requestsAfterStart, exchange.requests.size());
    }

    @Test
    void metadata发现非HttpsToken端点时永久拒绝() {
        FakeExchange exchange = new FakeExchange(true);
        BrokeredMcpOAuthPort broker = new BrokeredMcpOAuthPort(exchange, new CanonicalJson());

        assertThrows(
                IllegalArgumentException.class,
                () -> broker.authorizationRequest(endpoint(), CHALLENGE, STATE, REDIRECT));
        assertEquals(2, exchange.requests.size());
    }

    private static McpEndpoint endpoint() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        McpEndpointSpec spec = new McpEndpointSpec(
                WorkspaceId.parse("00000000-0000-0000-0000-000000000101"),
                "OAuth MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example/rpc")),
                Optional.empty(),
                McpAuthType.OAUTH_2_1_PKCE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(20));
        return new McpEndpoint("oauth-endpoint", 1, McpEndpointState.ENABLED, 0, spec, now, now);
    }

    private static BrokerResponse jsonResponse(String body) {
        return new BrokerResponse(
                200,
                Map.of("content-type", List.of("application/json; charset=utf-8")),
                body.getBytes(StandardCharsets.UTF_8),
                false);
    }

    private static final class FakeExchange implements BrokeredMcpOAuthPort.Exchange {
        private final boolean insecureTokenEndpoint;
        private final List<BrokerRequest> requests = new ArrayList<>();

        private FakeExchange(boolean insecureTokenEndpoint) {
            this.insecureTokenEndpoint = insecureTokenEndpoint;
        }

        @Override
        public BrokerResponse exchange(
                McpEndpoint endpoint, BrokerRequest request, com.javaclaw.api.CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            requests.add(request);
            String path = request.uri().getPath();
            if (path.equals("/.well-known/oauth-protected-resource")) {
                return jsonResponse("{\"authorization_servers\":[\"https://auth.example\"]}");
            }
            if (path.equals("/.well-known/oauth-authorization-server")) {
                String token = insecureTokenEndpoint ? "http://auth.example/token" : "https://auth.example/token";
                return jsonResponse("""
                        {
                          "issuer":"https://auth.example",
                          "authorization_endpoint":"https://auth.example/authorize",
                          "token_endpoint":"%s",
                          "registration_endpoint":"https://auth.example/register",
                          "code_challenge_methods_supported":["S256"],
                          "grant_types_supported":["authorization_code"],
                          "response_types_supported":["code"]
                        }
                        """.formatted(token));
            }
            if (path.equals("/register")) {
                return jsonResponse("{\"client_id\":\"client-123\"}");
            }
            if (path.equals("/token")) {
                return jsonResponse("{\"access_token\":\"vault-only-token\",\"token_type\":\"Bearer\"}");
            }
            throw new AssertionError("unexpected OAuth request: " + request.uri());
        }
    }
}
