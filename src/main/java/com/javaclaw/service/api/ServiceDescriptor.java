package com.javaclaw.service.api;

import java.util.Set;

public record ServiceDescriptor(String id, Set<String> operations, boolean streaming) {
    public ServiceDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("service id is required");
        operations = operations == null ? Set.of() : Set.copyOf(operations);
    }
}
