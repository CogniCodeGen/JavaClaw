package com.javaclaw.service.api;

/** Idempotent service or endpoint registration handle. */
@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override
    void close();
}
