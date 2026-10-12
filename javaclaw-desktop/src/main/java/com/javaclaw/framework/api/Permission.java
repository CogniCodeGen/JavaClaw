package com.javaclaw.framework.api;

import java.util.Objects;

/** One authorization grant. Capabilities never imply permissions. */
public record Permission(String value) implements Comparable<Permission> {
    public Permission {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("permission must not be blank");
        }
    }

    @Override
    public int compareTo(Permission other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
