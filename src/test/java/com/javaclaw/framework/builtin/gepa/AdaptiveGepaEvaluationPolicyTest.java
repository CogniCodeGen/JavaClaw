package com.javaclaw.framework.builtin.gepa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveGepaEvaluationPolicyTest {
    @Test
    void modelReceivesOnlyFinalAnswerAndSignals() {
        RecordingGateway gateway = new RecordingGateway();
        RunId run = RunId.random();
        var output = JsonNodeFactory.instance.objectNode().put("text", "最终答复");
        output.put("unrelatedMetadata", "x".repeat(13_000));
        AdaptiveGepaEvaluationPolicy policy = new AdaptiveGepaEvaluationPolicy();
        JsonNode assessment = policy.evaluate(List.of(event(run, "core.tool.completed")),
                output, gateway);

        JsonNode input = gateway.request.input();
        assertEquals("最终答复", input.path("finalAnswer").asText());
        assertEquals(1, input.path("toolCalls").asInt());
        assertFalse(input.has("unrelatedMetadata"));
        assertEquals("gepa.evaluate", gateway.request.purpose());
        assertEquals(1, policy.eventSchemaVersion(assessment));
    }

    @Test
    void oversizedRequiredAnswerIsUnavailableWithoutAuxiliaryModelCall() {
        RecordingGateway gateway = new RecordingGateway();
        var output = JsonNodeFactory.instance.objectNode().put("text", "x".repeat(12_001));
        AdaptiveGepaEvaluationPolicy policy = new AdaptiveGepaEvaluationPolicy();
        JsonNode assessment = policy.evaluate(
                List.of(event(RunId.random(), "core.tool.completed")), output, gateway);

        assertTrue(gateway.request == null);
        assertEquals("unavailable", assessment.path("mode").asText());
        assertEquals("answer_too_large", assessment.path("reason").asText());
        assertTrue(assessment.path("summary").asText().length() <= 500);
        assertFalse(assessment.has("score"));
        assertFalse(assessment.has("needsRevision"));
        assertEquals(2, policy.eventSchemaVersion(assessment));
    }

    private static RunEventEnvelope event(RunId run, String type) {
        return new RunEventEnvelope(run.value(), 1, Instant.now(), type, 1, "test",
                null, null, JsonNodeFactory.instance.objectNode());
    }

    private static final class RecordingGateway implements ModelTaskGateway {
        private ModelTaskRequest request;

        @Override public CompletionStage<ModelTaskResult> execute(ModelTaskRequest value) {
            request = value;
            JsonNode output = JsonNodeFactory.instance.objectNode().put("score", 1.0)
                    .put("needsRevision", false).put("summary", "ok");
            return CompletableFuture.completedFuture(new ModelTaskResult(output, "test", 1, 1,
                    false, Map.of()));
        }
    }
}
