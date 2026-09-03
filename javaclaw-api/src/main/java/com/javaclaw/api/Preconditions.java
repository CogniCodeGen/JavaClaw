package com.javaclaw.api;

import java.util.Objects;

final class Preconditions {
    private Preconditions() {}

    static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    static String boundedText(String value, String name, int maximumLength) {
        String normalized = text(value, name);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + maximumLength);
        }
        return normalized;
    }

    static String identifier(String value, String name) {
        String normalized = text(value, name);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    static String digest(String value, String name) {
        String normalized = text(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return normalized;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static long positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
