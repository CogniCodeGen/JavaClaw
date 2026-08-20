package com.javaclaw.plugins.deliverance;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared failure-safe backend loader used by generation and embedding models. */
final class BackendFallbackLoader {
    private BackendFallbackLoader() { }

    static <B extends AutoCloseable, T> Loaded<B, T> load(
            boolean allowFallback,
            Supplier<B> primaryBackend,
            Supplier<B> fallbackBackend,
            Function<B, T> modelLoader) {
        B primary = Objects.requireNonNull(primaryBackend.get(), "primary backend");
        try {
            return new Loaded<>(Objects.requireNonNull(modelLoader.apply(primary), "loaded model"), primary);
        } catch (RuntimeException | LinkageError primaryFailure) {
            close(primary, primaryFailure);
            if (!allowFallback) throw primaryFailure;
            B fallback = Objects.requireNonNull(fallbackBackend.get(), "fallback backend");
            try {
                return new Loaded<>(Objects.requireNonNull(modelLoader.apply(fallback), "loaded model"), fallback);
            } catch (RuntimeException | LinkageError fallbackFailure) {
                close(fallback, fallbackFailure);
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private static void close(AutoCloseable value, Throwable failure) {
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    record Loaded<B, T>(T model, B backend) { }
}
