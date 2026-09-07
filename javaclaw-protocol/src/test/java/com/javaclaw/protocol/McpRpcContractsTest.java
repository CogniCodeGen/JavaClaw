package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRpcContractsTest {
    @Test
    void OAuth查询必须按授权或Endpoint二选一() {
        assertEquals(
                Optional.of("oauth-one"),
                McpRpcContracts.OAuthQuery.authorization("oauth-one").authorizationId());
        assertEquals(
                Optional.of("endpoint-one"),
                McpRpcContracts.OAuthQuery.endpoint("endpoint-one").endpointId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.OAuthQuery(Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpRpcContracts.OAuthQuery(Optional.of("oauth"), Optional.of("endpoint")));
    }

    @Test
    void WorkspaceId按ProtocolV3标量编码() {
        CanonicalJson json = new CanonicalJson();
        WorkspaceId workspaceId = WorkspaceId.parse("00000000-0000-0000-0000-000000000101");

        String encoded =
                json.encode(new McpRpcContracts.WorkspaceQuery(workspaceId)).json();

        assertEquals("{\"workspaceId\":\"00000000-0000-0000-0000-000000000101\"}", encoded);
        assertFalse(encoded.contains("\"value\""));
    }

    @Test
    void OAuth公共投影不包含UrlPkceCodeToken或Credential() {
        CanonicalJson json = new CanonicalJson();
        McpOAuthAuthorization authorization = new McpOAuthAuthorization(
                "oauth-one",
                "endpoint-one",
                2,
                "login.example.test",
                McpOAuthState.PENDING,
                Instant.parse("2026-09-01T00:10:00Z"),
                Optional.empty(),
                Instant.parse("2026-09-01T00:00:00Z"));

        String encoded = json.encode(authorization).json();

        assertTrue(encoded.contains("login.example.test"));
        assertFalse(encoded.contains("authorizationUri"));
        assertFalse(encoded.contains("credential"));
        assertFalse(encoded.contains("challenge"));
        assertFalse(encoded.contains("code"));
        assertEquals(authorization, json.decode(json.encode(authorization), McpOAuthAuthorization.class));
    }

    @Test
    void OAuth逐方法Schema不暴露完成回调或敏感字段() throws IOException {
        String schema = resource("/schema/mcp-v3.schema.json");
        String methods = resource("/schema/methods-v3.json");
        new CanonicalJson().parse(schema);

        assertTrue(methods.contains("mcp/oauth/read"));
        assertTrue(methods.contains("mcp/oauth/start"));
        assertTrue(methods.contains("mcp/oauth/cancel"));
        assertTrue(methods.contains("mcp/resource/list"));
        assertTrue(methods.contains("mcp/resource/read"));
        assertTrue(methods.contains("mcp/prompt/list"));
        assertTrue(methods.contains("mcp/prompt/get"));
        assertFalse(methods.contains("mcp/oauth/complete"));
        assertFalse(schema.contains("authorizationUri"));
        assertFalse(schema.contains("codeVerifier"));
        assertFalse(schema.contains("codeChallenge"));
        assertFalse(schema.contains("callbackUri"));
        assertTrue(schema.contains("authorizationHost"));
    }

    private static String resource(String name) throws IOException {
        try (var input = McpRpcContractsTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
