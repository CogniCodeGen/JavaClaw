package com.javaclaw.framework.spi;

/**
 * A provider response that cannot be accepted as the requested structured model task result.
 * Transport, cancellation, budget and infrastructure failures must retain their own types.
 */
public final class ModelTaskOutputException extends IllegalStateException {
    public enum Reason { INVALID_JSON, SCHEMA_MISMATCH }

    private final Reason reason;

    public ModelTaskOutputException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}
