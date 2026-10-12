package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Objects;

/** Bounded handoff data; criterion IDs reference the parent's immutable host-owned contract. */
public record InteractionTask(
        int version,
        String taskId,
        long revision,
        InteractionMode mode,
        String goal,
        JsonNode necessaryData,
        List<String> constraints,
        List<String> acceptanceCriterionIds) {
    public InteractionTask {
        if (version != 1 || revision < 1) {
            throw new IllegalArgumentException("unsupported interaction task version or revision");
        }
        taskId = bounded(taskId, "taskId", 256);
        mode = Objects.requireNonNull(mode, "mode");
        goal = bounded(goal, "goal", 8_000);
        necessaryData = necessaryData == null || necessaryData.isNull()
                ? JsonNodeFactory.instance.objectNode() : necessaryData.deepCopy();
        if (necessaryData.toString().length() > 16_000) {
            throw new IllegalArgumentException("necessaryData exceeds the handoff budget");
        }
        constraints = checked(constraints, "constraints", 16, 512, false);
        acceptanceCriterionIds = checked(acceptanceCriterionIds,
                "acceptanceCriterionIds", 12, 128, true);
    }

    @Override public JsonNode necessaryData() { return necessaryData.deepCopy(); }

    private static List<String> checked(List<String> values, String field,
                                        int count, int length, boolean unique) {
        List<String> result = (values == null ? List.<String>of() : values).stream()
                .map(value -> bounded(value, field, length)).toList();
        if (result.size() > count || unique && result.stream().distinct().count() != result.size()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }

    private static String bounded(String value, String field, int limit) {
        value = Objects.requireNonNull(value, field).strip();
        if (value.isEmpty() || value.length() > limit) {
            throw new IllegalArgumentException(field + " must contain 1 to " + limit + " characters");
        }
        return value;
    }
}
