package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/** Persists the exact, serializable execution plan before a run starts. */
public interface ExecutionPlanStore {
    void save(String planId, long extensionGeneration, JsonNode plan, String checksum);

    Optional<JsonNode> find(String planId);
}
