package com.javaclaw.framework.springai;

/** A provider response that cannot be accepted as the requested structured model task result. */
final class ModelTaskOutputException extends IllegalStateException {
    enum Reason { INVALID_JSON, SCHEMA_MISMATCH }

    private final Reason reason;

    ModelTaskOutputException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    Reason reason() { return reason; }
}
