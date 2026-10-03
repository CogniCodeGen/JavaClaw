package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Small versioned wire format shared by run events and non-Agent orchestrators. */
public final class TaskResultJson {
    private TaskResultJson() {}

    public static ObjectNode encode(TaskResult result) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("schemaVersion", 1);
        node.put("outcome", result.outcome().name());
        node.put("stopReason", result.stopReason());
        add(node.putArray("unmetCriteria"), result.unmetCriteria());
        add(node.putArray("evidenceRefs"), result.evidenceRefs());
        add(node.putArray("satisfiedCriteria"), result.satisfiedCriteria());
        return node;
    }

    public static TaskResult decode(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        try {
            TaskOutcome outcome = TaskOutcome.valueOf(node.path("outcome").asText());
            return new TaskResult(outcome, strings(node.path("unmetCriteria")),
                    node.path("stopReason").asText(""), strings(node.path("evidenceRefs")),
                    strings(node.path("satisfiedCriteria")));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static void add(ArrayNode node, List<String> values) {
        values.forEach(node::add);
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText());
        });
        return List.copyOf(values);
    }
}
