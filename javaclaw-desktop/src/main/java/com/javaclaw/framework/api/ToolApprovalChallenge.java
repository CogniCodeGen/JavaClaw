package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** Stable, serializable description of a tool call that is waiting for approval. */
public record ToolApprovalChallenge(
        String tool,
        JsonNode arguments,
        String fingerprint,
        String kind,
        String description,
        boolean trustedContextRead) {

    public ToolApprovalChallenge(String tool, JsonNode arguments, String fingerprint,
            String kind, String description) {
        this(tool, arguments, fingerprint, kind, description, false);
    }

    public ToolApprovalChallenge {
        tool = requireText(tool, "tool");
        arguments = arguments == null
                ? JsonNodeFactory.instance.objectNode() : arguments.deepCopy();
        fingerprint = requireText(fingerprint, "fingerprint");
        kind = normalize(kind, "CONFIRM");
        description = normalize(description, tool);
    }

    @Override
    public JsonNode arguments() {
        return arguments.deepCopy();
    }

    public ObjectNode toJson() {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("tool", tool);
        value.set("arguments", arguments);
        value.put("fingerprint", fingerprint);
        value.put("kind", kind);
        value.put("description", description);
        value.put("trustedContextRead", trustedContextRead);
        return value;
    }

    /** Decodes only the canonical, fully structured waiting-approval event. */
    public static ToolApprovalChallenge fromEventPayload(JsonNode payload) {
        Objects.requireNonNull(payload, "payload");
        JsonNode candidate = payload.path("approval");
        if (!candidate.isObject()
                || !nonblankText(candidate.path("tool"))
                || !candidate.path("arguments").isObject()
                || !nonblankText(candidate.path("fingerprint"))
                || !nonblankText(candidate.path("kind"))
                || !nonblankText(candidate.path("description"))
                || !candidate.path("trustedContextRead").isBoolean()) {
            throw new IllegalArgumentException("waiting approval event has no valid approval challenge");
        }
        return new ToolApprovalChallenge(
                candidate.path("tool").textValue(),
                candidate.get("arguments"),
                candidate.path("fingerprint").textValue(),
                candidate.path("kind").textValue(),
                candidate.path("description").textValue(),
                candidate.path("trustedContextRead").booleanValue());
    }

    private static boolean nonblankText(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank();
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
