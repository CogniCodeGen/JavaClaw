package com.javaclaw.framework.api;

import java.util.Objects;

/** A published Agent definition. A null version means the latest published version at compile time. */
public record AgentDefinitionRef(String id, Long version) {
    public AgentDefinitionRef {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty() || (version != null && version < 1)) {
            throw new IllegalArgumentException("invalid agent definition reference");
        }
    }

    public static AgentDefinitionRef latest(String id) {
        return new AgentDefinitionRef(id, null);
    }
}
