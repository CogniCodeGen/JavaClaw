package com.javaclaw.framework.spi;

import java.util.Objects;

/** A tool finished, but its durable terminal outcome could not be committed. */
public final class ToolOutcomeCommitException extends RuntimeException {
    private final String tool;
    private final String invocationId;

    public ToolOutcomeCommitException(
            String tool, String invocationId, String message, Throwable cause) {
        super(message, cause);
        this.tool = required(tool, "tool");
        this.invocationId = required(invocationId, "invocationId");
    }

    public String tool() {
        return tool;
    }

    public String invocationId() {
        return invocationId;
    }

    public boolean sideEffectMayHaveOccurred() {
        return true;
    }

    private static String required(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return checked;
    }
}
