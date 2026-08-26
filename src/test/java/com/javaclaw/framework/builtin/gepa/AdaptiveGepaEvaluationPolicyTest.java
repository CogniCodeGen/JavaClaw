package com.javaclaw.framework.builtin.gepa;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ModelTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdaptiveGepaEvaluationPolicyTest {

    @Test
    void successfulToolUseAloneStaysOnRulesWhileFailureUsesOneLightCall() {
        AdaptiveGepaEvaluationPolicy policy = new AdaptiveGepaEvaluationPolicy();
        ModelTaskGateway forbidden = request -> CompletableFuture.failedFuture(
                new AssertionError("successful tool use must not call a model"));
        var normal = policy.evaluate(List.of(event("core.tool.completed")),
                JsonNodeFactory.instance.objectNode().put("text", "done"), forbidden);
        assertEquals("rules", normal.path("mode").asText());

        var routineMultiStep = policy.evaluate(IntStream.range(0, 30)
                        .mapToObj(index -> event("core.tool.completed", index + 1,
                                Instant.parse("2026-08-20T00:00:00Z").plusSeconds(index)))
                        .toList(),
                JsonNodeFactory.instance.objectNode().put("text", "four-step task done"),
                forbidden);
        assertEquals("rules", routineMultiStep.path("mode").asText());

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ModelTaskRequest> captured = new AtomicReference<>();
        ModelTaskGateway fake = request -> {
            calls.incrementAndGet();
            captured.set(request);
            var output = JsonNodeFactory.instance.objectNode();
            output.put("score", 0.4);
            output.put("needsRevision", true);
            output.put("summary", "tool failed");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    output, "light", 50, 10, false, Map.of()));
        };
        var abnormal = policy.evaluate(List.of(event("core.tool.failed")),
                JsonNodeFactory.instance.objectNode().put("text", "partial"), fake);

        assertEquals("model", abnormal.path("mode").asText());
        assertEquals(1, calls.get());
        assertEquals(ModelTier.LIGHT, captured.get().tier());
        assertEquals(0, captured.get().maxRetries());
        assertFalse(captured.get().cacheAllowed());
    }

    private static RunEventEnvelope event(String type) {
        return event(type, 1, Instant.parse("2026-08-20T00:00:00Z"));
    }

    private static RunEventEnvelope event(String type, long sequence, Instant timestamp) {
        return new RunEventEnvelope("run-gepa", sequence, timestamp,
                type, 1, "test", null, null, JsonNodeFactory.instance.objectNode());
    }
}
