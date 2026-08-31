package com.javaclaw.server.extension.mcp;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpRoutingHeadersTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpCodec codec = new McpCodec(json);

    @Test
    void mirrorsSafeTypedToolParametersAndEncodesUnicodeName() throws Exception {
        ObjectNode params = json.createObjectNode();
        params.put("name", "天气");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("region", "us-west1");
        arguments.put("dryRun", true);
        ObjectNode schema = (ObjectNode) json.readTree("""
                {"type":"object","properties":{
                  "region":{"type":"string","x-mcp-header":"Region"},
                  "dryRun":{"type":"boolean","x-mcp-header":"Dry-Run"}}}
                """);
        ObjectNode request = codec.request(McpProtocol.TOOLS_CALL, params);

        Map<String, String> headers = McpHttpRoutingHeaders.forRequest(request, schema, arguments);

        assertEquals(McpProtocol.TOOLS_CALL, headers.get("Mcp-Method"));
        assertTrue(headers.get("Mcp-Name").startsWith("=?base64?"));
        assertEquals("us-west1", headers.get("Mcp-Param-Region"));
        assertEquals("true", headers.get("Mcp-Param-Dry-Run"));
    }

    @Test
    void rejectsAnnotationsHiddenBehindComposition() throws Exception {
        ObjectNode schema = (ObjectNode) json.readTree("""
                {"type":"object","oneOf":[{"properties":{
                  "token":{"type":"string","x-mcp-header":"Token"}}}]}
                """);
        ObjectNode params = json.createObjectNode();
        params.put("name", "tool");
        ObjectNode request = codec.request(McpProtocol.TOOLS_CALL, params);

        assertThrows(
                IllegalArgumentException.class,
                () -> McpHttpRoutingHeaders.forRequest(request, schema, json.createObjectNode()));
    }

    @Test
    void routesTaskLifecycleByTaskId() {
        ObjectNode params = json.createObjectNode().put("taskId", "task-123");
        ObjectNode request = codec.request(McpProtocol.TASKS_GET, params);

        Map<String, String> headers = McpHttpRoutingHeaders.forRequest(request, null, null);

        assertEquals(McpProtocol.TASKS_GET, headers.get("Mcp-Method"));
        assertEquals("task-123", headers.get("Mcp-Name"));
    }
}
