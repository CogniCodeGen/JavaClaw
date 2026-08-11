package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.platform.json.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonImporterTest {

    private final McpJsonImporter importer =
            new McpJsonImporter(new JsonCodec(new ObjectMapper()));

    @Test
    void importsWrappedStdioAndHttpServers() {
        List<McpServerConfig> servers = importer.parse("""
                {
                  "mcpServers": {
                    "local": {
                      "command": "java",
                      "args": ["-jar", "server.jar"],
                      "env": {"PROFILE": "test"},
                      "enabled": false
                    },
                    "remote": {
                      "url": "https://example.test/mcp",
                      "headers": {"X-Client": "JavaClaw"}
                    }
                  }
                }
                """, null);

        assertEquals(2, servers.size());
        McpServerConfig local = servers.get(0);
        assertEquals("stdio", local.getTransport());
        assertEquals(List.of("-jar", "server.jar"), local.getArgs());
        assertEquals("test", local.getEnv().get("PROFILE"));
        assertFalse(local.isEnabled());

        McpServerConfig remote = servers.get(1);
        assertEquals("http", remote.getTransport());
        assertEquals("JavaClaw", remote.getHeaders().get("X-Client"));
        assertTrue(remote.isEnabled());
    }

    @Test
    void importsSingleServerWithFallbackName() {
        McpServerConfig server = importer.parse(
                "{\"command\":\"node\",\"args\":[\"server.js\"]}", "fallback").getFirst();

        assertEquals("fallback", server.getName());
        assertEquals("node", server.getCommand());
    }

    @Test
    void rejectsMalformedAndUnrecognizedDocuments() {
        assertTrue(importer.parse("  ", null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> importer.parse("[]", null));
        assertThrows(IllegalArgumentException.class, () -> importer.parse("{broken", null));
        assertThrows(IllegalArgumentException.class,
                () -> importer.parse("{\"command\":\"node\"}", null));
        assertThrows(IllegalArgumentException.class,
                () -> importer.parse("{\"label\":\"not-a-server\"}", null));
    }
}
