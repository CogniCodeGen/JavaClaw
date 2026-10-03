package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.spi.FrameworkTool;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/** Reuses only consecutive identical host observations in one durable provider message. */
final class BatchObservationReuse {
    static final String PHASE = "reuse_batch_observation";
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final ObjectMapper json;

    BatchObservationReuse(ReasoningRequest request, RunStepQuery steps, ObjectMapper json) {
        this.request = request;
        this.steps = steps;
        this.json = json;
    }

    static boolean isAlias(AgentStep step) {
        return step.kind() == AgentStep.Kind.ORCHESTRATION && step.input() != null
                && PHASE.equals(step.input().path("phase").asText());
    }

    ToolInvocationResult reuse(FrameworkTool tool, JsonNode arguments,
            String invocation, StepId modelStep, AgentStep existing) {
        AgentStep source = source(tool, arguments, invocation, modelStep);
        if (source == null) {
            if (existing != null) throw invalid(invocation);
            return null;
        }
        request.control().throwIfCancelled();
        String sourceInvocation = source.input().path("invocationId").asText();
        ObjectNode input = json.createObjectNode().put("phase", PHASE)
                .put("tool", tool.descriptor().name())
                .put("modelStepId", modelStep.value()).put("invocationId", invocation)
                .put("sourceInvocationId", sourceInvocation)
                .put("fingerprint", ToolInvocationFingerprint.create(tool.descriptor().name(), arguments));
        input.set("arguments", arguments);
        ObjectNode output = source.output().deepCopy();
        output.put("durationMillis", 0).put("reusedObservation", true);
        if (existing != null) {
            if (!isAlias(existing) || existing.state() != AgentStep.State.COMPLETED
                    || !input.equals(existing.input()) || !output.equals(existing.output())) {
                throw invalid(invocation);
            }
        } else {
            StepId id = StepId.tool(request.runId(), invocation);
            // A duplicate is provider feedback, not another execution or receipt.
            // Only the original gateway invocation consumes tool budget and performs audit.
            StepEvents.started(request.events(), id, AgentStep.Kind.ORCHESTRATION,
                    input, modelStep.value());
            StepEvents.completed(request.events(), id, output, null);
        }
        return PersistedToolCallCodec.replay(source);
    }

    String evidenceInvocation(String invocation) {
        return steps.step(request.runId(), StepId.tool(request.runId(), invocation))
                .filter(BatchObservationReuse::isAlias)
                .map(step -> step.input().path("sourceInvocationId").asText()).orElse(invocation);
    }

    private AgentStep source(FrameworkTool tool, JsonNode arguments,
            String invocation, StepId modelStep) {
        if (modelStep == null || !eligible(tool) || arguments == null || !arguments.isObject()) return null;
        AgentStep model = steps.step(request.runId(), modelStep).orElse(null);
        if (model == null || model.state() != AgentStep.State.COMPLETED || model.output() == null
                || !model.output().has("message")) return null;
        AssistantMessage assistant = (AssistantMessage) StepMessageCodec.message(model.output().path("message"));
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        int index = -1;
        for (int at = 0; at < calls.size(); at++) {
            if (invocation.equals(PersistedToolCallCodec.invocationId(modelStep, calls.get(at)))) {
                if (index >= 0) return null; // Ambiguous provider IDs are never reusable.
                index = at;
            }
        }
        if (index < 1 || !same(calls.get(index), tool, arguments)) return null;
        int first = index;
        while (first > 0 && same(calls.get(first - 1), tool, arguments)) first--;
        if (first == index) return null;
        String sourceInvocation = PersistedToolCallCodec.invocationId(modelStep, calls.get(first));
        AgentStep source = steps.step(request.runId(), StepId.tool(request.runId(), sourceInvocation)).orElse(null);
        if (source == null || source.kind() != AgentStep.Kind.TOOL
                || source.state() != AgentStep.State.COMPLETED || source.input() == null
                || source.output() == null || !source.output().isObject()
                || !source.output().has("modelOutput")
                || !source.input().path("trustedDesktopTool").asBoolean(false)
                || !tool.descriptor().name().equals(source.input().path("tool").asText())
                || !sourceInvocation.equals(source.input().path("invocationId").asText())
                || !arguments.equals(source.input().path("arguments"))
                || !ToolInvocationFingerprint.create(tool.descriptor().name(), arguments)
                        .equals(source.input().path("fingerprint").asText())) return null;
        return source;
    }

    private boolean same(AssistantMessage.ToolCall call, FrameworkTool tool, JsonNode arguments) {
        if (!tool.descriptor().name().equals(call.name())) return false;
        try {
            // JsonNode object equality normalizes field order while retaining exact values.
            return arguments.equals(json.readTree(call.arguments()));
        } catch (Exception invalid) { return false; }
    }

    private static boolean eligible(FrameworkTool tool) {
        // Provider names, plugin descriptors and marker interfaces cannot confer host trust.
        return SpringAiAnnotatedToolRegistry.isExactHostTool(tool) && tool.descriptor().idempotent()
                && CAPABILITIES.metadataForTool(tool.descriptor().name())
                        .filter(metadata -> metadata.id().equals("desktop.observe")).isPresent();
    }

    private ToolRecoveryRequiredException invalid(String invocation) {
        return new ToolRecoveryRequiredException(StepId.tool(request.runId(), invocation).value(),
                "persisted batch observation reuse does not match its original provider call and receipt");
    }
}
