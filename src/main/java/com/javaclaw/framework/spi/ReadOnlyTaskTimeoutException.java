package com.javaclaw.framework.spi;

import java.util.concurrent.TimeoutException;

/** The host's logical deadline expired for a confirmed read-only task, not its transport or audit. */
public final class ReadOnlyTaskTimeoutException extends TimeoutException {
    public ReadOnlyTaskTimeoutException(String message) {
        super(message);
    }
}
