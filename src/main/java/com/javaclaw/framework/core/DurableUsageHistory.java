package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.api.RunEventEnvelope;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reconstructs authoritative model usage from a possibly mixed legacy/v2 event stream. */
final class DurableUsageHistory {
    private DurableUsageHistory() { }

    static Snapshot from(List<RunEventEnvelope> source) {
        List<RunEventEnvelope> events = source == null ? List.of() : source.stream()
                .sorted(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).toList();
        long firstPrimaryUsage = firstSequence(events, "core.model.usage");
        long firstTaskUsage = firstSequence(events, "core.model_task.usage");

        Mutable total = new Mutable();
        RunEventEnvelope primaryBaseline = null;
        for (RunEventEnvelope event : events) {
            if (event.type().equals("core.model.completed")
                    && event.sequence() < firstPrimaryUsage) {
                primaryBaseline = event;
            }
        }
        if (primaryBaseline != null) total.add(primaryBaseline.payload(), false);

        for (RunEventEnvelope event : events) {
            switch (event.type()) {
                case "core.model.usage", "core.model_task.usage" ->
                        total.add(event.payload(), true);
                case "core.model_task.completed" -> {
                    if (event.sequence() < firstTaskUsage) total.add(event.payload(), false);
                }
                default -> { }
            }
        }
        return total.snapshot();
    }

    private static long firstSequence(List<RunEventEnvelope> events, String type) {
        return events.stream().filter(event -> event.type().equals(type))
                .mapToLong(RunEventEnvelope::sequence).min().orElse(Long.MAX_VALUE);
    }

    record Snapshot(ModelTokenUsage usage, BigDecimal cost, Set<String> modelCallIds) {
        Snapshot {
            usage = usage == null ? ModelTokenUsage.ZERO : usage;
            cost = cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO);
            modelCallIds = Set.copyOf(modelCallIds == null ? Set.of() : modelCallIds);
        }
    }

    private static final class Mutable {
        private ModelTokenUsage usage = ModelTokenUsage.ZERO;
        private BigDecimal cost = BigDecimal.ZERO;
        private final LinkedHashSet<String> callIds = new LinkedHashSet<>();

        private void add(JsonNode payload, boolean usageFact) {
            long input = nonNegative(payload, "inputTokens", 0);
            long output = nonNegative(payload, "outputTokens", 0);
            long calls = nonNegative(payload, "modelCalls", 1);
            usage = usage.plus(new ModelTokenUsage(
                    input,
                    nonNegative(payload, "cacheReadInputTokens", 0),
                    nonNegative(payload, "cacheWriteInputTokens", 0),
                    output,
                    nonNegative(payload, "reasoningTokens", 0),
                    calls));
            JsonNode costNode = payload.get("estimatedCostCny");
            if (costNode != null && costNode.isNumber()) {
                cost = cost.add(costNode.decimalValue().max(BigDecimal.ZERO));
            }
            if (usageFact) {
                String callId = payload.path("modelCallId").asText("").strip();
                if (!callId.isEmpty()) callIds.add(callId);
            }
        }

        private Snapshot snapshot() {
            return new Snapshot(usage, cost, callIds);
        }

        private static long nonNegative(JsonNode value, String field, long fallback) {
            return Math.max(0, value.path(field).asLong(fallback));
        }
    }
}
