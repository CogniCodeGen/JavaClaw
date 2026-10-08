package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Objects;

/** Host-assembled child outcome. Summaries and business data do not grant acceptance evidence. */
public record InteractionResult(
        int version,
        String taskId,
        long revision,
        String childRunId,
        RunState state,
        List<String> satisfiedCriterionIds,
        List<String> unmetCriterionIds,
        String summary,
        String errorCode,
        List<String> evidenceRefs,
        List<JsonNode> unknownEffects,
        JsonNode data,
        List<String> resultRefs) {
    public InteractionResult {
        if (version != 1 || revision < 1) {
            throw new IllegalArgumentException("unsupported interaction result version or revision");
        }
        taskId = required(taskId, "taskId", 256);
        childRunId = required(childRunId, "childRunId", 256);
        state = Objects.requireNonNull(state, "state");
        satisfiedCriterionIds = strings(satisfiedCriterionIds, "satisfiedCriterionIds", 12, 128);
        unmetCriterionIds = strings(unmetCriterionIds, "unmetCriterionIds", 12, 128);
        if (satisfiedCriterionIds.stream().anyMatch(unmetCriterionIds::contains)) {
            throw new IllegalArgumentException("a criterion cannot be both satisfied and unmet");
        }
        summary = optional(summary, "summary", 2_000);
        errorCode = optional(errorCode, "errorCode", 128);
        evidenceRefs = strings(evidenceRefs, "evidenceRefs", 32, 512);
        unknownEffects = (unknownEffects == null ? List.<JsonNode>of() : unknownEffects)
                .stream().<JsonNode>map(value -> Objects.requireNonNull(value, "unknownEffects").deepCopy())
                .toList();
        if (unknownEffects.size() > 32
                || unknownEffects.stream().mapToInt(value -> value.toString().length()).sum() > 8_000) {
            throw new IllegalArgumentException("unknownEffects exceeds the result budget");
        }
        data = data == null || data.isNull() ? JsonNodeFactory.instance.objectNode() : data.deepCopy();
        if (data.toString().length() > 16_000) {
            throw new IllegalArgumentException("data exceeds the result budget");
        }
        resultRefs = strings(resultRefs, "resultRefs", 16, 512);
    }

    @Override public List<JsonNode> unknownEffects() {
        return unknownEffects.stream().<JsonNode>map(JsonNode::deepCopy).toList();
    }
    @Override public JsonNode data() { return data.deepCopy(); }

    private static List<String> strings(List<String> values, String field, int count, int limit) {
        List<String> result = (values == null ? List.<String>of() : values).stream()
                .map(value -> required(value, field, limit)).toList();
        if (result.size() > count || result.stream().distinct().count() != result.size()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }

    private static String required(String value, String field, int limit) {
        value = Objects.requireNonNull(value, field).strip();
        if (value.isEmpty() || value.length() > limit) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static String optional(String value, String field, int limit) {
        value = value == null ? "" : value.strip();
        if (value.length() > limit) throw new IllegalArgumentException("invalid " + field);
        return value;
    }
}
