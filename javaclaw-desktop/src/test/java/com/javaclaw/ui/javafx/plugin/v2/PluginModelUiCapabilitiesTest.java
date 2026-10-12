package com.javaclaw.ui.javafx.plugin.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginModelUiCapabilitiesTest {
    @Test void modelCapabilitiesNeverImplyAnHttpServer() {
        assertTrue(PluginModelUiCapabilities.endpoints(details("{\"capabilities\":[\"chat\",\"embeddings\",\"streaming\"]}"),
                new ObjectMapper()).isEmpty());
    }

    @Test void declaredRoutesAreRestrictedToKnownRoutesAndMatchingModelCapabilities() {
        String metadata = """
                {"inference":{"capabilities":["chat"],"endpointCapabilities":{
                  "openai":["models","chat","embeddings","sse","openapi","execute-script"]}}}
                """;
        assertEquals(Map.of("openai", Set.of("models", "chat", "openapi")),
                PluginModelUiCapabilities.endpoints(details(metadata), new ObjectMapper()));
    }

    private static PluginDetails details(String metadata) {
        return new PluginDetails(new PluginSummary("model", "模型", "1", "", "", "a".repeat(64), true,
                true, StartupPolicy.MANUAL, State.READY, 1, Set.of("MODEL"), ""), "{}",
                new ConfigurationSnapshot(1, "{}", "{}", Map.of(), Set.of()), Set.of(), List.of(
                new ContributionInfo("inference", "MODEL", "2.0", "模型", "", "{}", metadata, "{}")));
    }
}
