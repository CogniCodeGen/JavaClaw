package com.javaclaw.sdk;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.JsonDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementDocumentsTest {
    @Test
    void embeddedDefinitionsStayStructuredAndUnknownSecurityFieldsAreRejected() {
        var value = ManagementDocuments.automation(new JsonDocument("""
                {"kind":"LOOP","name":"验收","workspaceId":"workspace","profileId":"profile_loop",
                "prompt":"验证结果","definition":{"maxIterations":25},"revision":0}
                """));
        assertEquals("{\"maxIterations\":25}", value.definition().canonicalJson());
        assertEquals(0, value.revision());
        assertThrows(IllegalArgumentException.class, () -> ManagementDocuments.automation(new JsonDocument("""
                {"name":"无权扩展","workingDirectory":"/","sandboxPolicy":{"mode":"HOST_FULL_ACCESS"}}
                """)));
    }

    @Test
    void duplicateTrailingAndCoercedValuesFailWithoutEchoingTheInput() {
        for (String input : new String[] {
            "{\"revision\":\"1\"}",
            "{\"revision\":1,\"revision\":2}",
            "{} {}",
            "{\"token\":\"synthetic-private-secret\"}"
        }) {
            var failure = assertThrows(
                    IllegalArgumentException.class, () -> ManagementDocuments.profile(new JsonDocument(input)));
            assertFalse(failure.getMessage().contains("synthetic-private-secret"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ManagementDocuments.stringMap(new JsonDocument("{\"timeout\":10}")));
        assertEquals(
                "x",
                ManagementDocuments.stringMap(new JsonDocument("{\"model\":\"x\"}"))
                        .get("model"));
    }
}
