package com.javaclaw.framework.api;

import java.util.Objects;

/** A published run profile. A null version means the latest published version at compile time. */
public record RunProfileRef(String id, Long version) {
    public RunProfileRef {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty() || (version != null && version < 1)) {
            throw new IllegalArgumentException("invalid run profile reference");
        }
    }

    public static RunProfileRef latest(String id) {
        return new RunProfileRef(id, null);
    }
}
