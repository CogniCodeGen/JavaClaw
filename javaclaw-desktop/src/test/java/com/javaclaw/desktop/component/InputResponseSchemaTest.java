package com.javaclaw.desktop.component;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputResponseSchemaTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void composesOnlyDeclaredPrimitiveValuesWithoutScalarCoercion() {
        InputResponseSchema schema = InputResponseSchema.parse(json, schema());

        CanonicalPayload response = schema.response(Map.of(
                "confirmed", true,
                "mode", "safe",
                "ratio", "0.25",
                "retries", "3"));

        assertEquals("{\"confirmed\":true,\"mode\":\"safe\",\"ratio\":0.25,\"retries\":3}", response.json());
        assertEquals(4, schema.fields().size());
    }

    @Test
    void rejectsMissingRequiredInvalidNumbersAndUnsafeSchemas() {
        InputResponseSchema schema = InputResponseSchema.parse(json, schema());

        assertThrows(IllegalArgumentException.class, () -> schema.response(Map.of("mode", "safe", "retries", "x")));
        assertThrows(
                IllegalArgumentException.class,
                () -> schema.response(Map.of("confirmed", true, "mode", "safe", "retries", "1.2")));
        assertThrows(
                IllegalArgumentException.class,
                () -> schema.response(Map.of("confirmed", true, "mode", "safe", "unknown", "value")));
        assertThrows(
                RuntimeException.class,
                () -> InputResponseSchema.parse(
                        json,
                        json.parse(
                                "{\"additionalProperties\":false,\"properties\":{\"password\":{\"type\":\"string\"}},\"type\":\"object\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> InputResponseSchema.parse(
                        json,
                        json.parse(
                                "{\"additionalProperties\":false,\"properties\":{\"mode\":{\"type\":\"string\"}},\"required\":[\"mode\",\"mode\"],\"type\":\"object\"}")));
    }

    private CanonicalPayload schema() {
        return json.parse(
                "{\"additionalProperties\":false,\"properties\":{\"confirmed\":{\"type\":\"boolean\"},\"mode\":{\"title\":\"模式\",\"type\":\"string\"},\"ratio\":{\"type\":\"number\"},\"retries\":{\"type\":\"integer\"}},\"required\":[\"confirmed\",\"mode\"],\"type\":\"object\"}");
    }
}
