package com.javaclaw.framework.core;

import com.javaclaw.framework.spi.TaskAcceptanceContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;

import java.util.List;
import java.util.Set;

/** 按原执行计划编码事件并记账，所有持久化状态写入仍经唯一引擎。 */
final class EngineRunEvents {
    private final AgentEngine engine;
    EngineRunEvents(AgentEngine engine) { this.engine = engine; }

    RunRequest resolvedTaskRequest(ActiveEngineRun active) {
        TaskContractV3 contract = TaskResultEvaluator.latestContractV3(
                engine.runs.eventsAfter(active.id, 0), engine.json).orElse(null);
        // 以宿主实际持久化合同替换调用方提供的值。
        RunRequest request = active.request.withAttribute(TaskAcceptanceContext.ATTRIBUTE,
                contract == null ? JsonNodeFactory.instance.objectNode() : engine.json.valueToTree(contract));
        if (contract == null || contract.originalRequest().isBlank()) return request;
        JsonNode resolved = JsonNodeFactory.instance.textNode(contract.originalRequest());
        request = request.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE, resolved);
        JsonNode explicit = active.request.attributes().get(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE);
        if (explicit != null && explicit.isTextual() && !explicit.asText().isBlank()) {
            // 后续人类澄清可以收紧或取消此前明确的目标。
            request = request.withAttribute(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE, resolved);
        }
        RunRequest resolvedRequest = request;
        return InteractionHumanAmendment.latest(engine.runs, engine.json, active.id)
                .map(revision -> InteractionHumanAmendment.projectHumanInput(resolvedRequest, revision))
                .orElse(resolvedRequest);
    }

    void appendReasoningEvent(
            ActiveEngineRun active, String type, int version, String producer, JsonNode payload) {
        if (StepEvents.isSettlement(type)) {
            var settled = engine.runs.settleStep(active.id, engine.event(active.request, type, producer, payload,
                    payload.path("causation").asText(null)));
            if (settled.isEmpty()) throw new IllegalStateException("step cannot be settled: " + active.id);
            active.sink.tryEmitNext(settled.get());
            return;
        }
        active.control.throwIfCancelled();
        if ("core.tool.started".equals(type) && "framework.core".equals(producer))
            engine.usage.recordToolCall(active.id, payload.path("invocationId").asText());
        JsonNode encoded = active.plan.encodeEvent(type, version, payload);
        var event = engine.appendTransition(active.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                engine.event(active.request, type, version, producer, encoded, null), null, null);
        event.ifPresent(value -> {
            if (type.equals("core.tool.started")) {
                String fingerprint = payload.path("fingerprint").asText("");
                ApprovedToolInvocation approved = active.pendingApprovedInvocation;
                if (approved != null
                        && approved.challenge().fingerprint().equals(fingerprint)) {
                    active.pendingApprovedInvocation = null;
                }
            }
            active.sink.tryEmitNext(value);
            if (TaskResultEvaluator.isBusinessToolStart(type, producer, payload))
                engine.taskHarness.reviseQuestionContract(active.id, active.request, active.sink::tryEmitNext);
            if (type.equals("core.tool.receipt") && producer.equals("framework.core")
                    && payload.path("tool").asText("").equals("desktop_session_observe")
                    && payload.path("status").asText("").equals("OBSERVED")) {
                engine.taskHarness.reconcileCheckpoints(active.id, active.request,
                        active.control, active.sink::tryEmitNext);
            }
        });
    }

    /** 调用宿主工具前，以同一事务提交调用保留与步骤开始。 */
    void appendToolStartedEvents(ActiveEngineRun active, JsonNode stepStarted,
                                         JsonNode toolStarted) {
        active.control.throwIfCancelled();
        engine.usage.recordToolCall(active.id, toolStarted.path("invocationId").asText());
        JsonNode step = active.plan.encodeEvent("core.step.started", 1, stepStarted);
        JsonNode tool = active.plan.encodeEvent("core.tool.started", 1, toolStarted);
        var appended = engine.appendTransitionBatch(active.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                List.of(engine.event(active.request, "core.step.started", 1, "framework.core", step,
                                stepStarted.path("causation").asText(null)),
                        engine.event(active.request, "core.tool.started", 1, "framework.core", tool,
                                toolStarted.path("causation").asText(null))))
                .orElseThrow(() -> new IllegalStateException(
                        "tool start reservation could not be persisted: " + active.id));
        for (RunEventEnvelope value : appended) active.sink.tryEmitNext(value);
        String fingerprint = toolStarted.path("fingerprint").asText("");
        ApprovedToolInvocation approved = active.pendingApprovedInvocation;
        if (approved != null && approved.challenge().fingerprint().equals(fingerprint)) {
            active.pendingApprovedInvocation = null;
        }
        if (TaskResultEvaluator.isBusinessToolStart("core.tool.started", "framework.core", toolStarted))
            engine.taskHarness.reviseQuestionContract(active.id, active.request, active.sink::tryEmitNext);
    }

}
