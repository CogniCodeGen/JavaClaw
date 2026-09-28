package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningEventSink;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.ExtensionStateStore;
import com.javaclaw.framework.spi.ExtensionStateView;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.TurnPreparation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Runs side-effecting Turn preparation once, replaying its durable result after resume. */
final class TurnPreparationJournal {
    private TurnPreparationJournal() { }

    static String prepare(ReasoningRequest request, RunStore runs, ExtensionStateStore state) {
        return prepare(request.runId(), request.runRequest(), request.events(),
                request.plan().turnPreparations(), runs, state.view(request.runId()));
    }

    static String prepare(RunId runId, RunRequest runRequest, ReasoningEventSink events,
            List<TurnPreparation> preparations, RunStore runs, ExtensionStateView state) {
        RunStepQuery steps = new RunStepQuery(runs);
        StringBuilder prompt = new StringBuilder();
        for (TurnPreparation preparation : preparations) {
            StepId id = id(runId, preparation);
            AgentStep existing = steps.step(runId, id).orElse(null);
            String contribution;
            if (existing == null) {
                var input = JsonNodeFactory.instance.objectNode()
                        .put("purpose", "turn.preparation")
                        .put("preparationId", preparation.id());
                StepEvents.started(events, id, AgentStep.Kind.ORCHESTRATION, input, null);
                try {
                    contribution = preparation.prepare(runRequest, state);
                } catch (RuntimeException failure) {
                    try { StepEvents.failed(events, id, failure); }
                    catch (RuntimeException journalFailure) { failure.addSuppressed(journalFailure); }
                    throw new ToolRecoveryRequiredException(id.value(),
                            "Turn preparation failed after starting; reconcile before resuming: " + id.value());
                }
                var output = JsonNodeFactory.instance.objectNode()
                        .put("prompt", contribution == null ? "" : contribution);
                try { StepEvents.completed(events, id, output, null); }
                catch (RuntimeException failure) {
                    throw new ToolRecoveryRequiredException(id.value(),
                            "Turn preparation result could not be journaled; reconcile before resuming: "
                                    + id.value());
                }
            } else {
                if (existing.state() != AgentStep.State.COMPLETED
                        || existing.output() == null || !existing.output().has("prompt")) {
                    throw new ToolRecoveryRequiredException(id.value(),
                            "Turn preparation outcome is unknown; reconcile before resuming: " + id.value());
                }
                contribution = existing.output().path("prompt").asText();
            }
            if (redacted(runs, runId, id)) {
                throw new ToolRecoveryRequiredException(id.value(),
                        "Turn preparation result was redacted; reconcile before resuming: " + id.value());
            }
            if (contribution != null && !contribution.isBlank()) {
                if (prompt.length() > 0) prompt.append("\n\n");
                prompt.append(contribution);
            }
        }
        return prompt.toString();
    }

    private static StepId id(RunId runId, TurnPreparation preparation) {
        byte[] source = (runId.value() + ":turn-preparation:" + preparation.id())
                .getBytes(StandardCharsets.UTF_8);
        return new StepId(UUID.nameUUIDFromBytes(source).toString());
    }

    private static boolean redacted(RunStore runs, RunId runId, StepId id) {
        return runs.eventsAfter(runId, 0).stream().anyMatch(event ->
                event.type().equals("core.step.completed")
                        && event.payload().path("stepId").asText().equals(id.value())
                        && event.payload().path("credentialRedacted").asBoolean(false));
    }
}
