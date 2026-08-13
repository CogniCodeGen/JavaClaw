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
        String description) {

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
        return value;
    }

    /**
     * Decodes the canonical event shape and the two shapes emitted by early 3.0 builds.
     * This keeps already-persisted waiting runs resumable after upgrading.
     */
    public static ToolApprovalChallenge fromEventPayload(JsonNode payload) {
        Objects.requireNonNull(payload, "payload");
        JsonNode candidate = object(payload.path("approval"));
        if (candidate == null) {
            JsonNode output = object(payload.path("output"));
            if (output != null) {
                candidate = object(output.path("approval"));
                if (candidate == null && hasChallenge(output)) candidate = output;
            }
        }
        if (candidate == null && hasChallenge(payload)) candidate = payload;
        if (candidate == null) {
            throw new IllegalArgumentException("waiting approval event has no approval challenge");
        }
        String description = candidate.path("description").asText("");
        if (description.isBlank()) description = payload.path("reason").asText("");
        return new ToolApprovalChallenge(
                candidate.path("tool").asText(""),
                candidate.path("arguments"),
                candidate.path("fingerprint").asText(""),
                candidate.path("kind").asText("CONFIRM"),
                description);
    }

    private static boolean hasChallenge(JsonNode value) {
        return value != null && value.isObject()
                && value.path("tool").isTextual()
                && value.path("fingerprint").isTextual();
    }

    private static JsonNode object(JsonNode value) {
        return value != null && value.isObject() ? value : null;
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
