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
