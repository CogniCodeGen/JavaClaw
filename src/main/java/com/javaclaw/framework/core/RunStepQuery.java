package com.javaclaw.framework.core;

import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunStore;
import java.util.*;

/** Rebuildable step projection: the run transaction and its outbox remain the only write boundary. */
public final class RunStepQuery implements StepClient {
    private final RunStore runs;
    public RunStepQuery(RunStore runs) { this.runs = Objects.requireNonNull(runs); }

    @Override public List<AgentStep> steps(RunId turnId) {
        RunScope scope = runs.find(turnId).orElseThrow(() ->
                new NoSuchElementException("turn not found: " + turnId)).request().scope();
        if (!runs.readable(scope)) throw new NoSuchElementException("turn is not readable: " + turnId);
        String thread = scope.sessionId();
        LinkedHashMap<StepId, AgentStep> result = new LinkedHashMap<>();
        for (RunEventEnvelope event : runs.eventsAfter(turnId, 0)) {
            if (!event.type().startsWith("core.step.")) continue;
            var payload = event.payload();
            String value = payload.path("stepId").asText("");
            if (value.isBlank()) continue;
            StepId id = new StepId(value);
            AgentStep previous = result.get(id);
            if (event.type().equals("core.step.started")) {
                if (previous != null) continue;
                result.put(id, new AgentStep(id, thread, turnId,
                        AgentStep.Kind.valueOf(payload.path("kind").asText()), AgentStep.State.RUNNING,
                        payload.path("causation").asText(null), payload.get("input"), null, null,
                        null, event.timestamp(), null, event.sequence(), event.sequence()));
            } else if (previous != null && previous.state() == AgentStep.State.RUNNING
                    && (event.type().equals("core.step.completed") || event.type().equals("core.step.failed"))) {
                result.put(id, new AgentStep(id, thread, turnId, previous.kind(),
                        event.type().equals("core.step.completed") ? AgentStep.State.COMPLETED : AgentStep.State.FAILED,
                        previous.causationStepId(), previous.input(), payload.get("output"), payload.get("usage"),
                        payload.path("message").asText(null), previous.startedAt(), event.timestamp(),
                        previous.startSequence(), event.sequence()));
            }
        }
        return List.copyOf(result.values());
    }
    @Override public Optional<AgentStep> step(RunId turnId, StepId stepId) {
        return steps(turnId).stream().filter(step -> step.id().equals(stepId)).findFirst();
    }
}
