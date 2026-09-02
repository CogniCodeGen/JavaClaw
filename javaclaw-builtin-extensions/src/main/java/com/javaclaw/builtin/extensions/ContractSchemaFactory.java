package com.javaclaw.builtin.extensions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;

/** 构造内置扩展使用的无执行能力 JSON Schema 2020-12 文档。 */
final class ContractSchemaFactory {
    private ContractSchemaFactory() {}

    static CanonicalPayload document(
            ExtensionPayloadCodec codec,
            String id,
            String title,
            Map<String, Object> properties,
            List<String> required) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("$id", id);
        schema.put("title", title);
        schema.putAll(object(properties, required));
        return codec.encode(schema);
    }

    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.copyOf(properties),
                "required",
                List.copyOf(required),
                "additionalProperties",
                false);
    }

    static Map<String, Object> string() {
        return Map.of("type", "string", "minLength", 1);
    }

    static Map<String, Object> digest() {
        return Map.of("type", "string", "pattern", "^[0-9a-f]{64}$");
    }

    static Map<String, Object> instant() {
        return Map.of("type", "string", "format", "date-time");
    }

    static Map<String, Object> integer(long minimum) {
        return Map.of("type", "integer", "minimum", minimum);
    }

    static Map<String, Object> boundedInteger(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    static Map<String, Object> enumStrings(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    static Map<String, Object> uniqueStrings() {
        return Map.of("type", "array", "items", string(), "uniqueItems", true);
    }
}
