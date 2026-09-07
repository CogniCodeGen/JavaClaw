package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaResourceTest {
    @Test
    void publishesStrictViewSchemaTwoResourceWithoutExecutableNodeTypes() throws IOException {
        try (var stream = ViewSchemaResourceTest.class.getResourceAsStream("/schema/view-schema-v3.schema.json")) {
            assertNotNull(stream);
            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(schema.contains("\"schemaVersion\": {\"const\": 2}"));
            assertTrue(schema.contains("\"graph\""));
            assertTrue(schema.contains("\"ATTACHMENT\""));
            assertTrue(schema.contains("\"STRUCTURED_LIST\""));
            assertTrue(schema.contains("\"TEXT_LIST\""));
            assertTrue(schema.contains("\"maxRows\": {\"type\": \"integer\", \"minimum\": 1, \"maximum\": 100}"));
            assertTrue(schema.contains("\"itemKey\""));
            assertTrue(schema.contains("\"commandBindings\""));
            assertTrue(schema.contains("\"argumentName\""));
            assertTrue(schema.contains("\"maximumBytes\""));
            assertTrue(schema.contains("\"additionalProperties\": false"));
            assertFalse(schema.contains("\"SECRET\""));
            assertFalse(schema.contains("javascript"));
            assertFalse(schema.contains("webview"));
            assertFalse(schema.contains("expression"));
        }
    }
}
