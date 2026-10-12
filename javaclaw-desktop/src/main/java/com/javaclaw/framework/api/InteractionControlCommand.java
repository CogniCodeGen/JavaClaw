package com.javaclaw.framework.api;

import java.util.Objects;

/** Explicit human control of a delegated interaction task; text is never used to infer the type. */
public record InteractionControlCommand(String commandId, long expectedRevision, Type type, String text,
        long childEventSequence) {
    public static final String ATTRIBUTE = "framework.interaction.command";
    public enum Type { ANSWER, AMEND, CANCEL }

    /** Older callers remain source-compatible; ANSWER without a challenge is rejected by the host. */
    public InteractionControlCommand(String commandId, long expectedRevision, Type type, String text) {
        this(commandId, expectedRevision, type, text, 0);
    }

    /** 显式投影固定控制协议，避免产品层自行创建 JSON 映射器。 */
    public com.fasterxml.jackson.databind.node.ObjectNode toJson() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("commandId", commandId).put("expectedRevision", expectedRevision)
                .put("type", type.name()).put("text", text).put("childEventSequence", childEventSequence);
    }

    public static InteractionControlCommand fromJson(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || !value.isObject() || !value.path("commandId").isTextual()
                || !value.path("type").isTextual() || !value.path("expectedRevision").isIntegralNumber()
                || !value.path("expectedRevision").canConvertToLong()
                || value.hasNonNull("text") && !value.path("text").isTextual()
                || value.has("childEventSequence") && (!value.path("childEventSequence").isIntegralNumber()
                        || !value.path("childEventSequence").canConvertToLong())) {
            throw new IllegalArgumentException("交互控制参数结构无效");
        }
        var fields = value.fieldNames();
        var allowed = java.util.Set.of("commandId", "expectedRevision", "type", "text", "childEventSequence");
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) throw new IllegalArgumentException("交互控制参数含未知字段");
        }
        return new InteractionControlCommand(value.path("commandId").asText(),
                value.path("expectedRevision").longValue(), Type.valueOf(value.path("type").asText()),
                value.path("text").asText(""), value.path("childEventSequence").asLong(0));
    }

    public InteractionControlCommand {
        commandId = Objects.requireNonNull(commandId, "commandId").strip();
        type = Objects.requireNonNull(type, "type");
        text = text == null ? "" : text.strip();
        if (commandId.isEmpty() || commandId.length() > 256 || expectedRevision < 1 || childEventSequence < 0) {
            throw new IllegalArgumentException("invalid interaction command identity or revision");
        }
        if (type != Type.ANSWER && childEventSequence != 0)
            throw new IllegalArgumentException("only interaction answers bind a child challenge");
        if (text.length() > 8_000 || type != Type.CANCEL && text.isEmpty()) {
            throw new IllegalArgumentException("interaction answer or amendment requires 1 to 8000 characters");
        }
    }
}
