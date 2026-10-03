package com.javaclaw.framework.springai;

import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.spi.RunStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Counts unavailable-tool corrections since the last independently receipted business action. */
final class ModelStepRejectionHistory {
    private ModelStepRejectionHistory() { }

    static long count(RunId runId, RunStepQuery steps, RunStore runs, String rejectionPhase) {
        List<AgentStep> history = steps.steps(runId);
        Set<String> completedBusinessInvocations = new HashSet<>();
        for (AgentStep step : history) {
            if (step.kind() != AgentStep.Kind.TOOL || step.state() != AgentStep.State.COMPLETED
                    || step.input() == null || step.output() == null
                    || step.output().path("waitingInput").asBoolean(false)
                    || step.output().path("validationRejected").asBoolean(false)
                    || !step.output().has("rawOutput")
                    || ToolExecutionStatus.fromCode(step.output().path("status").asText(""))
                            != ToolExecutionStatus.SUCCEEDED) continue;
            String invocation = step.input().path("invocationId").asText("");
            if (!step.input().path("tool").asText("").isBlank()
                    && !step.input().path("trustedContextRead").asBoolean(false)
                    && !step.input().path("trustedToolCatalog").asBoolean(false)
                    && !invocation.isBlank()) completedBusinessInvocations.add(invocation);
        }
        long lastProgress = runs.eventsAfter(runId, 0).stream()
                .filter(event -> event.type().equals("core.tool.receipt"))
                .filter(event -> completedBusinessInvocations.contains(
                        event.payload().path("invocationId").asText("")))
                .filter(event -> switch (event.payload().path("status").asText("")) {
                    case "ACCEPTED", "OBSERVED", "VERIFIED" -> true;
                    default -> false;
                })
                .mapToLong(com.javaclaw.framework.api.RunEventEnvelope::sequence)
                .max().orElse(0);
        return history.stream()
                .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                        && step.state() == AgentStep.State.COMPLETED
                        && step.startSequence() > lastProgress
                        && rejectionPhase.equals(step.input().path("phase").asText()))
                .map(step -> step.input().path("modelStepId").asText())
                .distinct().count();
    }
}
