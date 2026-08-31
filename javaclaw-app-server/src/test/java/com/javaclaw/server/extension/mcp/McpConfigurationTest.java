package com.javaclaw.server.extension.mcp;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.javaclaw.server.extension.McpRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsOnlyBrokeredHttpsOrPluginOwnedStdio() {
        McpConfiguration http = McpConfiguration.parse(record(null, """
                {"transport":"http","url":"https://mcp.example.test/rpc",
                 "networkAllowlist":["mcp.example.test"],
                 "auth":{"type":"bearer","credentialName":"access"}}
                """), json);
        assertEquals(McpConfiguration.Transport.HTTP, http.transport());

        assertThrows(
                IllegalArgumentException.class,
                () -> McpConfiguration.parse(
                        record(
                                null,
                                "{\"transport\":\"http\",\"url\":\"http://example.test\","
                                        + "\"networkAllowlist\":[\"example.test\"]}"),
                        json));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpConfiguration.parse(record(null, "{\"transport\":\"stdio\",\"processId\":\"p\"}"), json));
    }

    private static McpRepository.McpRecord record(String pluginId, String config) {
        return new McpRepository.McpRecord("server", pluginId, "Server", config, true, "CONFIGURED", 1, Instant.EPOCH);
    }
}
