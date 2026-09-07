package com.javaclaw.builtin.contracts;

import java.util.Objects;

/** Coding wire 输入的跨平台语法边界；实际路径和权限仍由服务端核验。 */
final class CodingContractValidation {
    private CodingContractValidation() {}

    static String text(String value, int maximum, String name) {
        String result = content(value, maximum, name).strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }

    static String content(String value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximum || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " contains NUL or exceeds limit");
        }
        return value;
    }

    static String path(String value) {
        String result = content(value, 4096, "path").replace('\\', '/');
        if (result.isBlank() || result.startsWith("/") || result.indexOf(':') >= 0) {
            throw new IllegalArgumentException("path must be relative");
        }
        for (String part : result.split("/", -1)) {
            if (part.equals("..") || part.equalsIgnoreCase(".git") || part.isEmpty()) {
                throw new IllegalArgumentException("path must not escape executionRoot");
            }
        }
        String normalized = java.util.Arrays.stream(result.split("/"))
                .filter(part -> !part.equals("."))
                .collect(java.util.stream.Collectors.joining("/"));
        return normalized.isEmpty() ? "." : normalized;
    }

    static String digest(String value) {
        if (!Objects.requireNonNull(value, "digest").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be a lower-case SHA-256");
        }
        return value;
    }

    static String id(String value) {
        String result = text(value, 240, "id");
        if (!result.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("id contains unsupported characters");
        }
        return result;
    }

    static void range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside allowed range");
        }
    }

    static void nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    static void bytes(int value) {
        range(value, 1, 1_048_576, "maxBytes");
    }

    static void dimensions(int columns, int rows) {
        range(columns, 20, 1000, "columns");
        range(rows, 5, 1000, "rows");
    }
}
