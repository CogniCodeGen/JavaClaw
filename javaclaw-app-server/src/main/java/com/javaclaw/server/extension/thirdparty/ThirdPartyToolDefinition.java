package com.javaclaw.server.extension.thirdparty;

import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.protocol.CanonicalJson;

/** manifest 中不携带平台 revision 的工具描述。 */
record ThirdPartyToolDefinition(
        String name,
        String description,
        CanonicalPayload inputSchema,
        CanonicalPayload outputSchema,
        ToolRisk risk,
        Set<String> tags) {
    ThirdPartyToolDefinition {
        name = text(name, "name");
        description = text(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(risk, "risk");
        tags = Set.copyOf(tags);
    }

    ToolDescriptor descriptor(String extensionId, long revision, CanonicalJson json) {
        requireObjectSchema(inputSchema, "inputSchema", json);
        requireObjectSchema(outputSchema, "outputSchema", json);
        return new ToolDescriptor(
                new ToolIdentity(extensionId, name, revision), description, inputSchema, outputSchema, risk, tags);
    }

    private static void requireObjectSchema(CanonicalPayload schema, String name, CanonicalJson json) {
        if (!"object".equals(json.textField(schema, "type").orElse(null))) {
            throw new IllegalArgumentException(name + " must declare JSON Schema type object");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
