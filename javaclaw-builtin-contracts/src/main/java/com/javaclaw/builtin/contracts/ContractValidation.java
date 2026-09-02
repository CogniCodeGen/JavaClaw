package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 内置 contracts 的集中不变量校验。 */
final class ContractValidation {
    private ContractValidation() {}

    static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    static long revision(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return value;
    }

    static Instant instant(Instant value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static List<String> textList(List<String> values, String name) {
        List<String> copy = List.copyOf(values);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return copy;
    }

    static Set<String> textSet(Set<String> values, String name) {
        Set<String> copy = Set.copyOf(values);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return copy;
    }

    static int searchLimit(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return value;
    }
}
