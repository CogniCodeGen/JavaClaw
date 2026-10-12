package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Explicit handoff from an execution turn to its long-lived thread. */
public record TurnResult(String response, List<JsonNode> facts, List<JsonNode> decisions,
                         List<JsonNode> artifacts, List<JsonNode> pending, List<String> sourceSteps,
                         Usage directUsage, Usage aggregateUsage) {
    public TurnResult(String response, List<JsonNode> facts, List<JsonNode> decisions,
                      List<JsonNode> artifacts, List<JsonNode> pending, List<String> sourceSteps) {
        this(response, facts, decisions, artifacts, pending, sourceSteps, Usage.ZERO, Usage.ZERO);
    }
    public TurnResult {
        response = response == null ? "" : response;
        directUsage = directUsage == null ? Usage.ZERO : directUsage;
        aggregateUsage = aggregateUsage == null ? Usage.ZERO : aggregateUsage;
        facts = copy(facts); decisions = copy(decisions); artifacts = copy(artifacts); pending = copy(pending);
        sourceSteps = sourceSteps == null ? List.of() : List.copyOf(sourceSteps);
    }
    public record Usage(long inputTokens, long outputTokens, java.math.BigDecimal estimatedCostCny) {
        public static final Usage ZERO = new Usage(0, 0, java.math.BigDecimal.ZERO);
    }
    private static List<JsonNode> copy(List<JsonNode> values) {
        return values == null ? List.of() : values.stream().<JsonNode>map(JsonNode::deepCopy).toList();
    }
}
