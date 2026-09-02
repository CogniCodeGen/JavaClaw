package com.javaclaw.model;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.api.CanonicalPayload;

/** 在 Provider 边界规范化不可信 JSON。 */
final class CanonicalJsonCodec {
    private final JsonMapper mapper = ModelJsonMapper.create();

    CanonicalPayload object(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("provider JSON must be an object");
            }
            return new CanonicalPayload(mapper.writeValueAsString(sort(node)));
        } catch (IOException failure) {
            throw new IllegalArgumentException("provider returned invalid JSON", failure);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = object.objectNode();
            object.properties().stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), sort(entry.getValue())));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode sorted = array.arrayNode();
            array.forEach(element -> sorted.add(sort(element)));
            return sorted;
        }
        return node;
    }
}
