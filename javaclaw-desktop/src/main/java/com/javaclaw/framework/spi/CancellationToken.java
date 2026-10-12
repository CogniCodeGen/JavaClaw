package com.javaclaw.framework.spi;

/** Cooperative cancellation shared by models, tools and extension tasks. */
@FunctionalInterface
public interface CancellationToken {
    boolean cancelled();

    /** Remaining owner budget; tokens without a deadline are effectively unbounded. */
    default java.time.Duration remaining() {
        return java.time.Duration.ofNanos(Long.MAX_VALUE);
    }

    /** Registers an eager cancellation callback when the token supports notifications. */
    default CancellationRegistration onCancel(Runnable callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        if (cancelled()) callback.run();
        return () -> { };
    }

    default void throwIfCancelled() {
        if (cancelled()) {
            throw new RunCancelledException();
        }
    }

    @FunctionalInterface
    interface CancellationRegistration extends AutoCloseable {
        @Override void close();
    }
}
