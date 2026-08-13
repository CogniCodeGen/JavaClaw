package com.javaclaw.framework.api;

import java.util.Locale;
import java.util.Objects;

/** Product entry that requested a run; it never selects a different runtime. */
public record InvocationSource(String kind, String id) {
    public InvocationSource {
        kind = normalize(kind, "kind").toLowerCase(Locale.ROOT);
        id = normalize(id, "id");
    }

    public static InvocationSource chat() {
        return new InvocationSource("chat", "desktop");
    }

    public static InvocationSource schedule(String taskId) {
        return new InvocationSource("schedule", taskId);
    }

    public static InvocationSource plugin(String pluginId) {
        return new InvocationSource("plugin", pluginId);
    }

    public static InvocationSource workflow(String workflowId) {
        return new InvocationSource("workflow", workflowId);
    }

    public static InvocationSource loop(String loopId) {
        return new InvocationSource("loop", loopId);
    }

    public static InvocationSource sdd(String taskId) {
        return new InvocationSource("sdd", taskId);
    }

    public static InvocationSource subAgent(String parentRunId) {
        return new InvocationSource("subagent", parentRunId);
    }

    private static String normalize(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
