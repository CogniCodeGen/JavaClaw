package com.javaclaw.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.workflow.model.RunStatus;

import java.util.Optional;

/** Stable JSON projection used when graph events cross the conversation adapter boundary. */
public final class GraphEventJson {
    private GraphEventJson() {}

    public static JsonNode encode(GraphEvent event) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("runId", event.runId());
        switch (event) {
            case GraphEvent.RunStarted e -> {
                value.put("type", "run.started");
                value.put("workflowId", e.workflowId());
                value.put("threadId", e.threadId());
            }
            case GraphEvent.NodeStarted e -> {
                value.put("type", "node.started");
                value.put("nodeId", e.nodeId());
                value.put("label", e.label());
                value.put("step", e.step());
            }
            case GraphEvent.NodeCompleted e -> {
                value.put("type", "node.completed");
                value.put("nodeId", e.nodeId());
                value.put("label", e.label());
                value.put("step", e.step());
            }
            case GraphEvent.NodeRetry e -> {
                value.put("type", "node.retry");
                value.put("nodeId", e.nodeId());
                value.put("attempt", e.attempt());
                value.put("message", e.message());
            }
            case GraphEvent.Transition e -> {
                value.put("type", "transition");
                value.put("source", e.source());
                value.put("target", e.target());
                value.put("edgeId", e.edgeId());
            }
            case GraphEvent.Interrupted e -> {
                value.put("type", "interrupted");
                value.put("nodeId", e.nodeId());
                value.put("prompt", e.prompt());
            }
            case GraphEvent.RunFinished e -> {
                value.put("type", "run.finished");
                value.put("status", e.status().name());
                value.put("output", e.output());
                value.put("error", e.error());
            }
        }
        return value;
    }

    public static Optional<GraphEvent.RunFinished> runFinished(JsonNode value) {
        if (value == null || !value.isObject()
                || !"run.finished".equals(value.path("type").asText())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new GraphEvent.RunFinished(
                    value.path("runId").asText(),
                    RunStatus.valueOf(value.path("status").asText()),
                    nullableText(value.get("output")), nullableText(value.get("error"))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
}
