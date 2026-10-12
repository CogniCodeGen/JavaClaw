package com.javaclaw.framework.springai;

import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Optional;

/** Reconstructs one host-authored correction without replaying an invalid control batch. */
final class HarnessProtocolRepairContext {
    private static final String CODE = "MODEL_DECISION_MIXED_BATCH";

    private HarnessProtocolRepairContext() { }

    static Optional<RunEventEnvelope> recover(RunId runId, RunStore runs, AgentStep model,
            List<AgentStep> history, AssistantMessage assistant) {
        var boundRepairs = runs.eventsAfter(runId, model.lastSequence()).stream()
                .filter(event -> event.type().equals("core.harness.protocol_repair_requested")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.springai")
                        && model.id().value().equals(
                                event.payload().path("modelStepId").asText("")))
                .toList();
        var repairs = boundRepairs.stream()
                .filter(event -> CODE.equals(event.payload().path("code").asText("")))
                .toList();
        if (repairs.isEmpty()) return Optional.empty();
        long controlCalls = assistant.getToolCalls().stream()
                .filter(call -> HarnessDecisionToolCallback.NAME.equals(call.name())).count();
        if (boundRepairs.size() != 1
                || controlCalls == 0
                || (controlCalls == 1 && assistant.getToolCalls().size() == 1)
                || history.stream().anyMatch(step -> step.startSequence() > model.lastSequence()
                        && (step.kind() == AgentStep.Kind.TOOL
                                || step.kind() == AgentStep.Kind.ORCHESTRATION))) {
            throw new ToolRecoveryRequiredException(model.id().value(),
                    "protocol repair does not match an unexecuted control batch");
        }
        var payload = repairs.getFirst().payload();
        String feedback = payload.path("feedback").asText("");
        if (payload.path("attempt").asInt(-1) != 1
                || feedback.isBlank() || feedback.length() > 2_000) {
            throw new ToolRecoveryRequiredException(model.id().value(),
                    "persisted protocol repair feedback is invalid");
        }
        return Optional.of(repairs.getFirst());
    }
}
