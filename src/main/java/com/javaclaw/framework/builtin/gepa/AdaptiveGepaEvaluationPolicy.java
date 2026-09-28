package com.javaclaw.framework.builtin.gepa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.*;

import java.time.Duration;
import java.util.List;

/** Rule assessment for every run, escalating only abnormal/tool/long runs to a LIGHT model. */
public final class AdaptiveGepaEvaluationPolicy implements EvaluationPolicy {
    private static final int MAX_EVALUATION_INPUT_CHARACTERS = 12_000;

    @Override
    public String id() {
        return "gepa.evaluate";
    }

    @Override
    public int eventSchemaVersion(JsonNode assessment) {
        return "unavailable".equals(assessment.path("mode").asText()) ? 2 : 1;
    }

    @Override
    public JsonNode evaluate(
            List<RunEventEnvelope> events, JsonNode output, ModelTaskGateway models) {
        JsonNode answer = finalAnswer(output);
        if (answer.toString().length() > MAX_EVALUATION_INPUT_CHARACTERS) {
            ObjectNode assessment = JsonNodeFactory.instance.objectNode();
            assessment.put("mode", "unavailable");
            assessment.put("reason", "answer_too_large");
            assessment.put("summary", "最终答复超过辅助模型评估输入上限；评估未执行");
            return assessment;
        }
        boolean hasTools = events.stream().anyMatch(event -> event.type().startsWith("core.tool."));
        boolean abnormal = events.stream().anyMatch(event -> event.type().endsWith(".failed"));
        boolean longRun = events.size() > 20 || output.toString().length() > 2_000;
        if (!hasTools && !abnormal && !longRun) {
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
        ModelTaskResult result = models.executeInline(new ModelTaskRequest(
                "gepa.evaluate", ModelTier.LIGHT,
                evaluationInput(events, output, answer), List.of(), schema,
                owner, "gepa", Duration.ofSeconds(30), 1, () -> false, false));
        ObjectNode assessment = result.output().deepCopy();
        assessment.put("mode", "model");
        return assessment;
    }

    private static JsonNode finalAnswer(JsonNode output) {
        JsonNode answer = output.path("text");
        if (answer.isMissingNode() || answer.isNull()) answer = output.path("value");
        if (answer.isMissingNode() || answer.isNull()) answer = output;
        return answer;
    }

    private static ObjectNode evaluationInput(
            List<RunEventEnvelope> events, JsonNode output, JsonNode answer) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.set("finalAnswer", answer.deepCopy());
        input.put("toolCalls", events.stream()
                .filter(event -> event.type().equals("core.tool.completed")).count());
        input.put("failedEvents", events.stream()
                .filter(event -> event.type().endsWith(".failed")).count());
        input.put("correctionGuardApplied", output.path("correctionGuardApplied").asBoolean(false));
        return input;
    }
}
