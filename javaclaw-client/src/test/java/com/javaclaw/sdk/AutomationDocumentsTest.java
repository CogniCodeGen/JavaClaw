package com.javaclaw.sdk;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.AutomationDefinitionInfo;
import com.javaclaw.sdk.model.JsonDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationDocumentsTest {
    @Test
    void roundTripsFiniteInheritanceBudgetsAndStructuredNodeOutputReferences() {
        var definition = new AutomationDefinitionInfo(
                0,
                10,
                20_000,
                600,
                3,
                "保留 UI",
                List.of(new AutomationDefinitionInfo.Criterion(
                        "verify",
                        "退出码通过",
                        "TOOL",
                        "command",
                        new JsonDocument("{\"command\":\"test\"}"),
                        "exitCode",
                        "0")),
                List.of(new AutomationDefinitionInfo.Node(
                        "write",
                        "TOOL",
                        "end",
                        "",
                        1,
                        Map.of("tool", "file_write", "arguments", "{\"content\":{\"$output\":\"agent\"}}"))),
                Map.of());
        assertEquals(definition, AutomationDocuments.read(AutomationDocuments.write(definition)));
        assertEquals(
                0,
                AutomationDocuments.read(AutomationDocuments.write(definition)).maxIterations());
    }

    @Test
    void rejectsNonObjectDefinitionsAndMalformedToolArguments() {
        assertThrows(IllegalArgumentException.class, () -> AutomationDocuments.read(new JsonDocument("[]")));
        var invalid = new AutomationDefinitionInfo(
                2,
                4,
                1000,
                60,
                2,
                "",
                List.of(),
                List.of(new AutomationDefinitionInfo.Node(
                        "tool", "TOOL", "", "", 1, Map.of("arguments", "{\"broken\""))),
                Map.of());
        assertThrows(IllegalArgumentException.class, () -> AutomationDocuments.write(invalid));
    }
}
