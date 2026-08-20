package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvocationLoggingPrivacyContractTest {

    @Test
    void manifestDeclaresHotLoggingProtocolAndKeepsItDisabledByDefault() throws Exception {
        var root = new ObjectMapper().readTree(Path.of("src/main/resources/plugin.json").toFile());
        assertEquals("0.0.12-7", root.path("version").asText());
        assertEquals(1, root.path("inference").path("protocol").path("major").asInt());
        assertEquals(2, root.path("inference").path("protocol").path("minor").asInt());
        var logging = root.path("configurationSchema").path("properties")
                .path("logging.invocations");
        assertFalse(logging.path("default").asBoolean(true));
        assertTrue(logging.path("x-javaclaw-host-managed").asBoolean(false));
        assertTrue(logging.path("x-javaclaw-hidden").asBoolean(false));
        for (String legacy : new String[]{"maxGenerationResident", "maxEmbeddingResident"}) {
            var field = root.path("configurationSchema").path("properties").path(legacy);
            assertTrue(field.path("deprecated").asBoolean(false), legacy);
            assertTrue(field.path("x-javaclaw-hidden").asBoolean(false), legacy);
        }
        assertFalse(root.path("configurationUi").toString().contains("驻留数量"));
    }

    @Test
    void invocationLogTemplateContainsOnlyApprovedMetadataFields() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/javaclaw/plugins/deliverance/DeliveranceServicePlugin.java"));
        String method = source.substring(source.indexOf("void logInvocation("),
                source.indexOf("private static String logField"));

        for (String field : new String[]{"time=", "source=", "route=", "operation=", "model=",
                "requestId=", "status=", "durationMs=", "promptTokens=", "completionTokens="}) {
            assertTrue(method.contains(field), field);
        }
        for (String forbidden : new String[]{"messages", "content", "reasoning", "Authorization",
                "apiKey", "credential", "headers", "body"}) {
            assertFalse(method.contains(forbidden), forbidden);
        }
    }

    @Test
    void residencyUsesOnlyTheProcessBudgetAndIdleLruEviction() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/javaclaw/plugins/deliverance/DeliveranceServicePlugin.java"));

        assertFalse(source.contains("maxGenerationResident"));
        assertFalse(source.contains("maxEmbeddingResident"));
        assertTrue(source.contains("residentBytes + requestedBytes <= budgetBytes"));
        assertTrue(source.contains("value.active().get() == 0"));
        assertTrue(source.contains("Comparator.comparing(EngineSlot::lastUsed)"));
        assertTrue(source.contains("resource_exhausted: model requires approximately"));
        assertTrue(source.contains("waitForResidentChange(cancellation)"));
    }
}
