package com.javaclaw.framework.spi;

/**
 * A model task exceeded its own bounded request timeout while its owner remained active and
 * within its deadline. Cancellation, owner expiry, transport and audit failures retain their types.
 */
public final class ModelTaskTimeoutException extends IllegalStateException {
    public ModelTaskTimeoutException(ReadOnlyTaskTimeoutException cause) {
        super("model task exceeded its own request timeout", java.util.Objects.requireNonNull(cause, "cause"));
    }
}
