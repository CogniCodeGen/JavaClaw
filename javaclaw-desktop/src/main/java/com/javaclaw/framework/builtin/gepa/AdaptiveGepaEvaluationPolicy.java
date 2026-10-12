package com.javaclaw.framework.builtin.gepa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Rule assessment for every run, escalating only abnormal/tool/long runs to a LIGHT model. */
public final class AdaptiveGepaEvaluationPolicy implements EvaluationPolicy {
    private static final int MAX_EVALUATION_INPUT_CHARACTERS = 12_000;
    private static final Set<String> TOOL_EVENTS = Set.of(
            "core.tool.started", "core.tool.completed", "core.tool.failed",
            "core.tool.receipt", "core.tool.arguments_rejected");
    private static final Set<String> FAILURE_EVENTS = Set.of(
            "core.run.failed", "core.step.failed", "core.tool.failed");

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
            return unavailable("answer_too_large", "最终答复超过辅助模型评估输入上限；评估未执行");
        }
        boolean hasTools = events.stream().anyMatch(event -> TOOL_EVENTS.contains(event.type()));
        boolean abnormal = events.stream().anyMatch(event -> FAILURE_EVENTS.contains(event.type()));
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
        ModelTaskResult result;
        try {
            result = models.executeInline(new ModelTaskRequest(
                    "gepa.evaluate", ModelTier.LIGHT,
                    evaluationInput(events, output, answer), List.of(), schema,
                    owner, "gepa", Duration.ofSeconds(30), 1, () -> false, false));
        } catch (ModelTaskTimeoutException failure) {
            if (failure.getSuppressed().length != 0
                    || failure.getCause().getSuppressed().length != 0
                    || Thread.currentThread().isInterrupted()) throw failure;
            return unavailable("evaluation_timeout",
                    "辅助模型评估达到本次独立时限，结果不可用；本轮未评分");
        } catch (ModelTaskOutputException failure) {
            // Audit failures are attached as suppressed exceptions by the gateway.
            // Preserve those and interruption; only an invalid evaluator result is unavailable.
            if (failure.getSuppressed().length != 0 || Thread.currentThread().isInterrupted()) {
                throw failure;
            }
            return unavailable("structured_output_invalid",
                    "辅助模型评估结果不符合结构化要求，重试后仍未通过校验；本轮评估不可用");
        }
        ObjectNode assessment = result.output().deepCopy();
        assessment.put("mode", "model");
        return assessment;
    }

    private static ObjectNode unavailable(String reason, String summary) {
        ObjectNode assessment = JsonNodeFactory.instance.objectNode();
        assessment.put("mode", "unavailable");
        assessment.put("reason", reason);
        assessment.put("summary", summary);
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
                .filter(event -> FAILURE_EVENTS.contains(event.type())).count());
        input.put("correctionGuardApplied", output.path("correctionGuardApplied").asBoolean(false));
        return input;
    }
}
