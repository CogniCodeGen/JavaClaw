package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.*;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Host-owned projection metadata, never inferred from application text or model output. */
final class HostContextBlock {
    static final String METADATA = "javaclaw.hostContextBlock";

    enum Kind {
        CONTROL, RUNTIME, APPLICATION_IDENTITY, TOOL_MANIFEST, SELECTED_CONTEXT,
        OBSERVATION, TOOL_EXCHANGE, WARNING
    }

    record Metadata(String id, Kind kind, String revision, String scope,
                    boolean required, List<String> evidenceRefs) {
        Metadata {
            Objects.requireNonNull(kind);
            if (id == null || id.isBlank() || scope == null || scope.isBlank()
                    || revision == null || revision.isBlank()) {
                throw new IllegalArgumentException("host context identity is incomplete");
            }
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    private HostContextBlock() { }

    static Metadata metadata(Message message) {
        Object value = message.getMetadata().get(METADATA);
        return value instanceof Metadata metadata ? metadata : null;
    }

    static boolean owned(Message message) { return metadata(message) != null; }
    static boolean required(Message message) {
        Metadata value = metadata(message);
        return value != null && value.required();
    }

    static Message mark(Message message, Metadata value) {
        var metadata = new HashMap<>(message.getMetadata());
        metadata.put(METADATA, value);
        return switch (message) {
            case SystemMessage system -> SystemMessage.builder().text(system.getText()).metadata(metadata).build();
            case UserMessage user -> UserMessage.builder().text(user.getText()).media(user.getMedia())
                    .metadata(metadata).build();
            case AssistantMessage assistant -> AssistantMessage.builder().content(assistant.getText())
                    .toolCalls(assistant.getToolCalls()).media(assistant.getMedia()).properties(metadata).build();
            case ToolResponseMessage response -> ToolResponseMessage.builder().responses(response.getResponses())
                    .metadata(metadata).build();
            default -> throw new IllegalArgumentException("unsupported host context message type");
        };
    }

    /** Only the host's journal codec writes/reads this field; tool JSON is not a message codec. */
    static ObjectNode wire(Metadata value) {
        ObjectNode wire = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1)
                .put("id", value.id()).put("kind", value.kind().name()).put("revision", value.revision())
                .put("scope", value.scope()).put("required", value.required());
        var refs = wire.putArray("evidenceRefs");
        value.evidenceRefs().forEach(refs::add);
        return wire;
    }

    static Metadata fromWire(JsonNode wire) {
        if (!wire.isObject() || wire.path("schemaVersion").asInt() != 1) {
            throw new IllegalArgumentException("invalid host context block schema");
        }
        var refs = new java.util.ArrayList<String>();
        wire.path("evidenceRefs").forEach(ref -> refs.add(ref.asText()));
        return new Metadata(wire.path("id").asText(), Kind.valueOf(wire.path("kind").asText()),
                wire.path("revision").asText(), wire.path("scope").asText(),
                wire.path("required").asBoolean(), refs);
    }
}
