package com.javaclaw.framework.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Functional capability identifier. It is deliberately independent from authorization. */
public record CapabilityId(String value) implements Comparable<CapabilityId> {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+");

    public CapabilityId {
        value = Objects.requireNonNull(value, "value").trim();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("capability id must be namespaced: " + value);
        }
    }

    @Override
    public int compareTo(CapabilityId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
