package com.javaclaw.framework.spi;

/** A provider response was received but its durable usage fact could not be committed. */
public final class ModelUsageCommitException extends RuntimeException {
    public ModelUsageCommitException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModelUsageCommitException(String message) {
        super(message);
    }
}
