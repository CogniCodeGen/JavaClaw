package com.javaclaw.framework.spi;

import java.time.Duration;
import java.util.Objects;

/** A bounded retry policy result. */
public record RetryDirective(boolean retry, Duration delay) {
    public RetryDirective {
        delay = Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) throw new IllegalArgumentException("retry delay must not be negative");
    }

    public static RetryDirective stop() { return new RetryDirective(false, Duration.ZERO); }
    public static RetryDirective retryAfter(Duration delay) {
        return new RetryDirective(true, delay);
    }
}
