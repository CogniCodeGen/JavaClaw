package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.PendingEffectObservationRequiredException;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.FrameworkTool;

/** 已完成导航的重复请求只产生控制反馈，不重发导航或制造新的效果证据。 */
final class BrowserNavigationFeedback {
    private static final String PHASE = "browser_navigation_already_completed";
    private static final int MAX_FEEDBACKS = 2;
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final ObjectMapper json;

    BrowserNavigationFeedback(ReasoningRequest request, RunStepQuery steps, ObjectMapper json) {
        this.request = request;
        this.steps = steps;
        this.json = json;
    }

    static boolean isFeedback(AgentStep step) {
        return step.kind() == AgentStep.Kind.ORCHESTRATION && step.input() != null
                && PHASE.equals(step.input().path("phase").asText());
    }

    ToolInvocationResult reject(FrameworkTool tool, JsonNode arguments, String invocation,
            StepId modelStep, PendingEffectObservationRequiredException pending) {
        if (!eligible(tool) || modelStep == null
                || pending.reason() != PendingEffectObservationRequiredException.Reason.EFFECT_ALREADY_ATTEMPTED
                || !pending.sourceRunId().isBlank()
                    && !pending.sourceRunId().equals(request.runId().value())
                || !completedSource(pending.invocationId(), arguments)) throw pending;
        long count = steps.steps(request.runId()).stream()
                .filter(BrowserNavigationFeedback::isFeedback).count();
        if (count >= MAX_FEEDBACKS) throw pending;
        request.control().throwIfCancelled();
        ObjectNode input = json.createObjectNode().put("phase", PHASE)
                .put("tool", tool.descriptor().name()).put("invocationId", invocation)
                .put("modelStepId", modelStep.value())
                .put("sourceInvocationId", pending.invocationId())
                .put("fingerprint", ToolInvocationFingerprint.create(tool.descriptor().name(), arguments));
        input.set("arguments", arguments);
        ObjectNode feedback = json.createObjectNode().put("executed", false)
                .put("error", "browser_navigation_already_completed")
                .put("sourceInvocationId", pending.invocationId())
                .put("message", "This navigation already completed earlier in this Run. "
                        + "This duplicate was not dispatched. Read the current page with web_snapshot "
                        + "(interactiveOnly=false) or web_get_text to check the requested result. "
                        + "The earlier navigation is not proof of the current page or its content. "
                        + "Do not repeat navigation to repair an unmet content criterion; "
                        + "report a blocker if observation cannot establish the requested result.");
        ObjectNode output = json.createObjectNode().put("durationMillis", 0)
                .put("status", ToolExecutionStatus.FAILED.name())
                .put("errorCode", "BROWSER_NAVIGATION_ALREADY_COMPLETED")
                .put("displayMessage", "导航已完成；请读取当前页面核验结果");
        output.set("rawOutput", feedback);
        output.set("modelOutput", feedback);
        StepId id = StepId.tool(request.runId(), invocation);
        StepEvents.started(request.events(), id, AgentStep.Kind.ORCHESTRATION, input, modelStep.value());
        StepEvents.completed(request.events(), id, output, null);
        return PersistedToolCallCodec.replay(steps.step(request.runId(), id).orElseThrow());
    }

    ToolInvocationResult replay(FrameworkTool tool, JsonNode arguments, String invocation,
            StepId modelStep, AgentStep existing) {
        JsonNode input = existing.input();
        if (!eligible(tool) || !isFeedback(existing)
                || existing.state() != AgentStep.State.COMPLETED || modelStep == null
                || !modelStep.value().equals(input.path("modelStepId").asText())
                || !invocation.equals(input.path("invocationId").asText())
                || !tool.descriptor().name().equals(input.path("tool").asText())
                || !arguments.equals(input.path("arguments"))
                || !ToolInvocationFingerprint.create(tool.descriptor().name(), arguments)
                    .equals(input.path("fingerprint").asText())
                || !completedSource(input.path("sourceInvocationId").asText(), arguments)
                || existing.output() == null
                || !ToolExecutionStatus.FAILED.name().equals(existing.output().path("status").asText())
                || existing.output().path("modelOutput").path("executed").asBoolean(true)
                || !input.path("sourceInvocationId").asText().equals(
                    existing.output().path("modelOutput").path("sourceInvocationId").asText())) {
            throw new ToolRecoveryRequiredException(existing.id().value(),
                    "persisted browser navigation feedback does not match its completed source");
        }
        return PersistedToolCallCodec.replay(existing);
    }

    private boolean completedSource(String invocation, JsonNode arguments) {
        if (!request.control().isCompletedBrowserNavigation(invocation)) return false;
        AgentStep source = steps.step(request.runId(), StepId.tool(request.runId(), invocation)).orElse(null);
        return source != null && source.kind() == AgentStep.Kind.TOOL
                && source.state() == AgentStep.State.COMPLETED && source.input() != null
                && source.input().path("trustedBrowserNavigation").asBoolean(false)
                && "web_navigate".equals(source.input().path("tool").asText())
                && invocation.equals(source.input().path("invocationId").asText())
                && sameNavigation(source.input().path("arguments"), arguments)
                && source.output() != null
                && ToolExecutionStatus.SUCCEEDED.name().equals(source.output().path("status").asText());
    }

    private static boolean sameNavigation(JsonNode previous, JsonNode current) {
        String target = com.javaclaw.framework.spi.BrowserReceiptProof.canonicalUrl(
                current.path("url").asText());
        return !target.isBlank() && target.equals(
                com.javaclaw.framework.spi.BrowserReceiptProof.canonicalUrl(previous.path("url").asText()));
    }

    private static boolean eligible(FrameworkTool tool) {
        return SpringAiAnnotatedToolRegistry.isExactHostTool(tool)
                && tool.descriptor().name().equals("web_navigate");
    }
}
