package com.javaclaw.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptOptimizationRpcContractsTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void strictContractsRoundTripExplicitConfirmations() {
        PromptOptimizationRpcContracts.StartPayload start = new PromptOptimizationRpcContracts.StartPayload(
                WorkspaceId.parse("00000000-0000-0000-0000-000000000001"),
                new AgentProfileRef("profile", 4),
                true,
                PromptOptimizationRpcContracts.BILLING_CONFIRMATION);
        PromptOptimizationRpcContracts.AdoptPayload adopt = new PromptOptimizationRpcContracts.AdoptPayload(
                PromptOptimizationId.parse("00000000-0000-0000-0000-000000000002"),
                true,
                PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION);

        assertEquals(start, json.decode(json.encode(start), PromptOptimizationRpcContracts.StartPayload.class));
        assertEquals(adopt, json.decode(json.encode(adopt), PromptOptimizationRpcContracts.AdoptPayload.class));
    }

    @Test
    void methodCatalogReferencesSchemasForEveryOptimizationMethod() throws IOException {
        String methods = resource("/schema/methods-v2.json");
        String schema = resource("/schema/prompt-optimization-v2.schema.json");

        List.of(
                        "start\", \"kind\": \"command\", \"paramsSchema\": \"prompt-optimization-v2.schema.json#/$defs/startCommand",
                        "read\", \"kind\": \"query\", \"paramsSchema\": \"prompt-optimization-v2.schema.json#/$defs/readPayload",
                        "list\", \"kind\": \"query\", \"paramsSchema\": \"prompt-optimization-v2.schema.json#/$defs/listPayload",
                        "cancel\", \"kind\": \"command\", \"paramsSchema\": \"prompt-optimization-v2.schema.json#/$defs/cancelCommand",
                        "adopt\", \"kind\": \"command\", \"paramsSchema\": \"prompt-optimization-v2.schema.json#/$defs/adoptCommand")
                .forEach(contract -> assertTrue(methods.contains("profile/prompt/optimization/" + contract)));
        assertTrue(schema.contains(PromptOptimizationRpcContracts.BILLING_CONFIRMATION));
        assertTrue(schema.contains(PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION));
        assertTrue(schema.contains("\"agentProfile\": {"));
        assertTrue(schema.contains("\"additionalProperties\": false"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = PromptOptimizationRpcContractsTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
