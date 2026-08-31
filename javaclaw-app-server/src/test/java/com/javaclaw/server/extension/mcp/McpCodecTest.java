package com.javaclaw.server.extension.mcp;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCodecTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpCodec codec = new McpCodec(json);

    @Test
    void addsPerRequestVersionAndCapabilitiesWithoutInitializeState() {
        ObjectNode request = codec.request(McpProtocol.DISCOVER, json.createObjectNode());

        assertEquals("2.0", request.path("jsonrpc").asText());
        assertEquals(
                McpProtocol.VERSION,
                request.path("params")
                        .path("_meta")
                        .path(McpProtocol.PROTOCOL_VERSION_META)
                        .asText());
        assertEquals(
                "JavaClaw",
                request.path("params")
                        .path("_meta")
                        .path(McpProtocol.CLIENT_INFO_META)
                        .path("name")
                        .asText());
        assertFalse(request.toString().contains("initialize"));
    }

    @Test
    void rejectsMismatchedIdsAndPreservesErrorData() {
        ObjectNode request = codec.request(McpProtocol.TOOLS_LIST, json.createObjectNode());
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", "wrong");
        response.putObject("result").put("resultType", "complete");

        assertThrows(McpProtocolException.class, () -> codec.result(request, response));
    }

    @Test
    void shipsThePinnedOfficialSchemaSnapshot() throws Exception {
        byte[] bytes;
        try (InputStream input = McpCodec.class.getResourceAsStream(McpProtocol.SCHEMA_RESOURCE)) {
            assertNotNull(input, "pinned MCP schema resource");
            bytes = input.readAllBytes();
        }
        assertTrue(bytes.length > 150_000, "the full schema, not a pointer file, must ship");
        assertEquals(
                McpProtocol.SCHEMA_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var schema = json.readTree(bytes);
        assertEquals(
                "https://json-schema.org/draft/2020-12/schema",
                schema.path("$schema").asText());
        assertEquals(
                McpProtocol.DISCOVER,
                schema.at("/$defs/DiscoverRequest/properties/method/const").asText());
        assertEquals(
                McpProtocol.SUBSCRIPTIONS_LISTEN,
                schema.at("/$defs/SubscriptionsListenRequest/properties/method/const")
                        .asText());
        assertEquals(
                McpProtocol.TOOLS_CALL,
                schema.at("/$defs/CallToolRequest/properties/method/const").asText());
    }
}
