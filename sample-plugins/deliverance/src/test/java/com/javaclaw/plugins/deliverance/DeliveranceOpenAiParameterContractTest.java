package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.service.api.DesktopServiceClient;
import com.javaclaw.service.api.PluginConfig;
import com.javaclaw.service.api.PluginLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveranceOpenAiParameterContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private DeliveranceOpenAiEndpoint endpoint;

    @BeforeEach
    void createEndpoint() {
        String policies = """
                [{"keyId":"key","prefix":"jcl_fixture","scopes":["CHAT_INVOKE"],
                  "modelAliases":[],"requestsPerMinute":60,"tokensPerMinute":100000,
                  "maxConcurrent":1}]
                """;
        Map<String, String> values = Map.of("external.apiKeyPolicies", policies);
        PluginConfig config = new PluginConfig() {
            @Override public String get(String key) { return values.get(key); }
            @Override public Map<String, String> asMap() { return values; }
        };
        DesktopServiceClient desktop = (serviceId, operation, contentType, payload, timeout, events) -> {
            throw new AssertionError("参数验证不应调用 Desktop 服务");
        };
        PluginLogger logger = new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message, Throwable failure) { }
        };
        endpoint = new DeliveranceOpenAiEndpoint(
                new DeliveranceServicePlugin(), json, config, desktop, logger);
    }

    @Test
    void forwardsEverySignedSchemaParameterAndMapsStandardOpenAiNames() throws Exception {
        JsonNode schema;
        try (var input = getClass().getClassLoader().getResourceAsStream("plugin.json")) {
            schema = json.readTree(input).path("inference").path("parameterSchema")
                    .path("properties").path("generation").path("properties");
        }
        ObjectNode request = json.createObjectNode();
        request.put("model", "local-chat");
        request.putArray("messages").addObject().put("role", "user").put("content", "hello");
        Map<String, Object> expected = new LinkedHashMap<>();
        schema.fields().forEachRemaining(entry -> {
            JsonNode definition = entry.getValue();
            JsonNode value = sample(definition);
            request.set(entry.getKey(), value);
            expected.put(entry.getKey(), json.convertValue(value, Object.class));
        });

        Map<String, Object> actual = endpoint.parameters(request);

        assertEquals(expected, actual);

        ObjectNode standard = request.deepCopy();
        standard.remove("maxTokens");
        standard.remove("topP");
        standard.remove("topLogprobs");
        standard.put("max_completion_tokens", 64);
        standard.put("top_p", 0.75);
        standard.put("top_logprobs", 3);
        Map<String, Object> mapped = endpoint.parameters(standard);
        assertEquals(64, mapped.get("maxTokens"));
        assertEquals(0.75, ((Number) mapped.get("topP")).doubleValue());
        assertEquals(3, mapped.get("topLogprobs"));
    }

    @Test
    void rejectsUnknownUnsupportedDuplicateAndOutOfRangeParameters() throws Exception {
        assertFailure("{\"unknown\":1}", "unknown_parameter", "unknown");
        assertFailure("{\"presence_penalty\":1}", "unsupported_parameter", "presence_penalty");
        assertFailure("{\"top_p\":2}", "invalid_request", "top_p");
        assertFailure("{\"max_tokens\":8,\"maxTokens\":9}", "invalid_request", "maxTokens");

        ObjectNode body = (ObjectNode) json.readTree(
                "{\"stream_options\":{\"include_usage\":true,\"future\":true}}");
        DeliveranceOpenAiEndpoint.ApiFailure failure = assertThrows(
                DeliveranceOpenAiEndpoint.ApiFailure.class, () -> endpoint.validateEnvelope(body));
        assertEquals("unknown_parameter", failure.code);
        assertEquals("stream_options.future", failure.param);
    }

    private void assertFailure(String fields, String code, String param) throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(fields);
        DeliveranceOpenAiEndpoint.ApiFailure failure = assertThrows(
                DeliveranceOpenAiEndpoint.ApiFailure.class, () -> endpoint.parameters(body));
        assertEquals(400, failure.status);
        assertEquals(code, failure.code);
        assertEquals(param, failure.param);
    }

    private JsonNode sample(JsonNode definition) {
        if (definition.has("default")) return definition.get("default");
        return switch (definition.path("type").asText()) {
            case "integer" -> json.getNodeFactory().numberNode(
                    definition.has("minimum") ? definition.path("minimum").asInt() : 1);
            case "number" -> json.getNodeFactory().numberNode(
                    definition.has("minimum") ? definition.path("minimum").asDouble() : 0.5);
            case "boolean" -> json.getNodeFactory().booleanNode(true);
            case "string" -> json.getNodeFactory().textNode("value");
            case "array" -> json.createArrayNode().add("value");
            default -> throw new AssertionError("未覆盖的参数 Schema 类型: " + definition);
        };
    }
}
