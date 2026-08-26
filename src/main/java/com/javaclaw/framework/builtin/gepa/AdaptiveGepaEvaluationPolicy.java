package com.javaclaw.framework.builtin.gepa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Rule assessment for every run, escalating only failed, abnormal, or long runs. */
public final class AdaptiveGepaEvaluationPolicy implements EvaluationPolicy {
    @Override
    public String id() {
        return "gepa.evaluate";
    }

    @Override
    public JsonNode evaluate(
            List<RunEventEnvelope> events, JsonNode output, ModelTaskGateway models) {
        boolean abnormal = events.stream().anyMatch(event -> event.type().endsWith(".failed"));
        boolean longRun = events.size() > 80
                || output.toString().length() > 12_000
                || elapsed(events).compareTo(Duration.ofMinutes(5)) > 0;
        if (!abnormal && !longRun) {
            ObjectNode assessment = JsonNodeFactory.instance.objectNode();
            assessment.put("mode", "rules");
            assessment.put("score", output.isNull() ? 0.0 : 1.0);
            assessment.put("needsRevision", output.isNull());
            assessment.put("summary", output.isNull() ? "empty output" : "basic checks passed");
            return assessment;
        }
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("score").put("type", "number").put("minimum", 0).put("maximum", 1);
        properties.putObject("needsRevision").put("type", "boolean");
        properties.putObject("summary").put("type", "string").put("maxLength", 500);
        schema.putArray("required").add("score").add("needsRevision").add("summary");
        RunId owner = new RunId(events.getFirst().runId());
        ModelTaskResult result = models.execute(new ModelTaskRequest(
                "gepa.evaluate", ModelTier.LIGHT,
                JsonNodeFactory.instance.objectNode().set("output", output), schema,
                owner, "gepa", Duration.ofSeconds(30), 0, () -> false, false))
                .toCompletableFuture().join();
        ObjectNode assessment = result.output().deepCopy();
        assessment.put("mode", "model");
        return assessment;
    }

    private static Duration elapsed(List<RunEventEnvelope> events) {
        if (events.size() < 2) return Duration.ZERO;
        Instant first = events.stream().map(RunEventEnvelope::timestamp)
                .min(Instant::compareTo).orElse(Instant.EPOCH);
        Instant last = events.stream().map(RunEventEnvelope::timestamp)
                .max(Instant::compareTo).orElse(first);
        return Duration.between(first, last);
    }
}
