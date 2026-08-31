package com.javaclaw.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolSchemaTest {
    private final ObjectMapper json = new JsonRpcCodec().mapper();

    @Test
    void checkedInSchemaAndMethodCatalogMatchProtocolV1() throws Exception {
        var schema = read("/schema/protocol-v1.schema.json");
        var methods = read("/schema/methods-v1.json");

        assertEquals(ProtocolVersion.CURRENT, schema.path("protocolVersion").asInt());
        assertTrue(
                schema.path("$defs")
                        .path("item")
                        .path("properties")
                        .path("kind")
                        .path("enum")
                        .isMissingNode(),
                "item kinds must remain open for forward compatibility");
        Set<String> catalog = java.util.stream.StreamSupport.stream(
                        methods.path("methods").spliterator(), false)
                .map(value -> value.asText())
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(RpcMethods.V1, catalog);
    }

    @Test
    void unknownItemKindsRoundTripWithoutDomainKnowledge() throws Exception {
        String encoded = """
                {"id":"item_1","threadId":"thread_1","turnId":"turn_1",
                 "ordinal":1,"state":"COMPLETED","kind":"futureCapability",
                 "payload":{"nested":{"value":42},"newField":true},
                 "createdAt":"2026-08-27T00:00:00Z","updatedAt":"2026-08-27T00:00:01Z"}
                """;
        WireItem item = json.readValue(encoded, WireItem.class);
        WireItem roundTrip = json.readValue(json.writeValueAsBytes(item), WireItem.class);

        assertEquals("futureCapability", roundTrip.kind());
        assertEquals(42, roundTrip.payload().path("nested").path("value").asInt());
        assertEquals(Instant.parse("2026-08-27T00:00:01Z"), roundTrip.updatedAt());
    }

    @Test
    void interactionExtensionsRoundTripWithoutSecretsOrRawReasoning() throws Exception {
        var turn = new WireTurnExecutionSummary(
                "turn_1",
                "profile_chat",
                "openai",
                "gpt-test",
                "COMPLETED",
                Instant.parse("2026-08-30T00:00:00Z"),
                Instant.parse("2026-08-30T00:00:01Z"),
                12L,
                4L,
                1L);
        var summary = new WireThreadExecutionSummary("thread_1", List.of(turn));
        var preview =
                new WireSchedulePreview("0 0 9 * * ?", "Asia/Shanghai", List.of(Instant.parse("2026-08-31T01:00:00Z")));

        assertEquals(summary, json.readValue(json.writeValueAsBytes(summary), WireThreadExecutionSummary.class));
        assertEquals(preview, json.readValue(json.writeValueAsBytes(preview), WireSchedulePreview.class));
        String encoded = json.writeValueAsString(summary);
        assertTrue(!encoded.contains("secret"));
        assertTrue(!encoded.contains("reasoningContent"));
        assertTrue(encoded.contains("reasoningTokens"));
    }

    private com.fasterxml.jackson.databind.JsonNode read(String resource) throws Exception {
        try (var input = ProtocolSchemaTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return json.readTree(input);
        }
    }
}
