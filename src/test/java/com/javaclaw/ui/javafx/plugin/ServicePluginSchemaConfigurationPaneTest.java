package com.javaclaw.ui.javafx.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServicePluginSchemaConfigurationPaneTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void invalidSavedValuesAreRejectedInsteadOfUsingSchemaDefaults() throws Exception {
        var schema = json.readTree("""
                {"type":"object","properties":{
                  "limit":{"type":"integer","default":3},
                  "enabled":{"type":"boolean","default":true},
                  "labels":{"type":"array","default":[]}
                }}
                """);

        assertThrows(IllegalArgumentException.class, () ->
                ServicePluginSchemaConfigurationPane.existingValues(
                        json, schema, Map.of("limit", "not-a-number")));
        assertThrows(IllegalArgumentException.class, () ->
                ServicePluginSchemaConfigurationPane.existingValues(
                        json, schema, Map.of("enabled", "yes")));
        assertThrows(IllegalArgumentException.class, () ->
                ServicePluginSchemaConfigurationPane.existingValues(
                        json, schema, Map.of("labels", "{}")));

        var existing = ServicePluginSchemaConfigurationPane.existingValues(
                json, schema, Map.of("enabled", "false", "retired", "old"));
        assertFalse(existing.path("enabled").asBoolean());
        assertFalse(existing.has("limit"));
        assertFalse(existing.has("retired"));
        assertEquals(1, existing.size());
    }
}
