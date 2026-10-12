package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decodes persisted provider tool calls without invoking business tools. */
final class PersistedToolCallCodec {
    static final String UNAVAILABLE_BATCH = "reject_unavailable_tool_batch";
    private PersistedToolCallCodec() { }

    static String invocationId(StepId model, AssistantMessage.ToolCall call) {
        return "model/" + model.value() + "/" + call.id();
    }

    static void validatePersistedToolVisibility(
            RunId runId, AgentStep modelStep, List<AssistantMessage.ToolCall> calls) {
        JsonNode input = modelStep.input();
        if (input == null || !input.path("toolNames").isArray()) {
            throw recoveryRequired(modelStep, "persisted provider tool directory is unavailable");
        }
        Set<String> visible = new HashSet<>();
        input.path("toolNames").forEach(name -> {
            if (name.isTextual()) visible.add(name.asText());
        });
        for (var call : calls) {
            if (visible.contains(call.name())) continue;
            StepId toolStep = StepId.tool(runId, invocationId(modelStep.id(), call));
            throw new ToolRecoveryRequiredException(toolStep.value(),
                    "Persisted model response requested a tool absent from its provider prompt: "
                            + call.name() + "; reconcile step " + toolStep.value());
        }
    }

    static String callFingerprint(AssistantMessage.ToolCall call) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("id", call.id());
        value.put("name", call.name());
        value.put("arguments", call.arguments());
        return ToolInvocationFingerprint.create(UNAVAILABLE_BATCH, value);
    }

    static ToolRecoveryRequiredException recoveryRequired(AgentStep model, String reason) {
        return new ToolRecoveryRequiredException(model.id().value(), reason);
    }
    static ToolInvocationResult replay(AgentStep step) {
        return new ToolInvocationResult(step.output().path("modelOutput"),
                Duration.ofMillis(step.output().path("durationMillis").asLong()),
                ToolExecutionStatus.fromCode(step.output().path("status").asText("")),
                step.output().path("errorCode").asText(""),
                step.output().path("displayMessage").asText(""));
    }
    static JsonNode parse(ObjectMapper json, String value) {
        try { return json.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("invalid persisted tool arguments", failure); }
    }
}
