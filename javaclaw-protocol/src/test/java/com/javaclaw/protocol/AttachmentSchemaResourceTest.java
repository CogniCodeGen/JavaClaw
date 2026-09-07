package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentSchemaResourceTest {
    @Test
    void catalogUsesChunkedUploadAndPublishesHardWireLimits() throws IOException {
        String methods = resource("/schema/methods-v3.json");
        String schema = resource("/schema/attachment-v3.schema.json");

        assertTrue(methods.contains("attachment/upload/begin"));
        assertTrue(methods.contains("attachment/upload/chunk"));
        assertTrue(methods.contains("attachment/upload/complete"));
        assertTrue(methods.contains("attachment/upload/abort"));
        assertFalse(methods.contains("attachment/create"));
        assertTrue(schema.contains("\"maxLength\": 349528"));
        assertTrue(schema.contains("\"maximum\": 67108864"));
        assertTrue(schema.contains("\"scope\""));
        assertTrue(schema.contains("\"WORKSPACE\""));
        assertTrue(schema.contains("\"GLOBAL\""));
    }

    private static String resource(String name) throws IOException {
        try (var input = AttachmentSchemaResourceTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
