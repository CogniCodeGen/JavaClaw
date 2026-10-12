package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunState;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Host-only identity of an outstanding question or an explicit recoverable pause. */
final class InteractionAnswerChallenge {
    private InteractionAnswerChallenge() { }

    static Optional<RunEventEnvelope> current(RunId childId, RunState state, List<RunEventEnvelope> events) {
        if (state != RunState.WAITING_INPUT && state != RunState.PAUSED) return Optional.empty();
        RunEventEnvelope question = null;
        RunEventEnvelope pause = null;
        for (RunEventEnvelope event : events.stream().filter(event -> event.runId().equals(childId.value())
                && event.schemaVersion() == 1 && event.producer().equals("framework.core"))
                .sorted(Comparator.comparingLong(RunEventEnvelope::sequence)).toList()) {
            if (event.type().equals("core.run.waiting_input")) {
                question = event;
                pause = null;
            } else if (event.type().equals("core.run.paused")) {
                // Process shutdown preserves an unanswered question; it creates no new one.
                if (!event.payload().path("reason").asText().equals("KERNEL_SHUTDOWN")) {
                    question = null;
                    pause = event;
                }
            } else if (Set.of("core.run.resumed", "core.run.waiting_approval", "core.run.waiting_child",
                    "core.run.waiting_event", "core.run.completed", "core.run.failed", "core.run.cancelled")
                    .contains(event.type())) {
                question = null;
                pause = null;
            }
            // core.run.recovered_paused preserves the original outstanding challenge.
        }
        return Optional.ofNullable(question != null ? question : state == RunState.PAUSED ? pause : null);
    }
}
