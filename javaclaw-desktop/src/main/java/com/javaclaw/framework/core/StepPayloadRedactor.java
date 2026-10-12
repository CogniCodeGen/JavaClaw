package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.util.SensitiveDataRedactor;
import java.util.Locale;

/** Applies the existing credential policy while preserving the shape of step evidence. */
final class StepPayloadRedactor {
    private StepPayloadRedactor() { }
    static JsonNode redact(JsonNode value) {
        if (value == null) return null;
        if (value.isTextual()) return JsonNodeFactory.instance.textNode(SensitiveDataRedactor.redactText(value.asText()));
        if (value.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(redact(item)));
            return result;
        }
        if (value.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            value.fields().forEachRemaining(field -> result.set(field.getKey(),
                    sensitive(field.getKey()) ? JsonNodeFactory.instance.textNode("<redacted>") : redact(field.getValue())));
            return result;
        }
        return value.deepCopy();
    }
    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password") || normalized.contains("passwd") || normalized.contains("secret")
                || normalized.contains("apikey") || normalized.equals("authorization")
                || normalized.equals("cookie") || normalized.equals("cookies")
                || normalized.equals("token") || normalized.equals("accesstoken") || normalized.equals("refreshtoken");
    }
}
