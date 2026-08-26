package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.RunStore;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Commits tool completion facts and resolves ambiguous store acknowledgements by invocation. */
final class ToolOutcomeCommitter {
    private final RunStore runs;
    private final CommittedRunEventWriter committedEvents;
    private final RunEventRelay events;

    ToolOutcomeCommitter(
            RunStore runs, CommittedRunEventWriter committedEvents, RunEventRelay events) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.committedEvents = Objects.requireNonNull(committedEvents, "committedEvents");
        this.events = Objects.requireNonNull(events, "events");
    }

    Optional<RunEventEnvelope> append(
            RunId runId, RunEventDraft draft, String invocationId,
            Set<String> committedInvocations, Set<String> uncertainInvocations) {
        if (committedInvocations.contains(invocationId)) {
            return findCompletion(runId, invocationId);
        }
        if (uncertainInvocations.contains(invocationId)) {
            Optional<RunEventEnvelope> existing = findCompletion(runId, invocationId);
            if (existing.isPresent()) {
                confirmExisting(invocationId, existing, committedInvocations, uncertainInvocations);
                return existing;
            }
            uncertainInvocations.remove(invocationId);
        }
        try {
            Optional<RunEventEnvelope> committed = committedEvents.append(runId, draft);
            if (committed.isPresent()) committedInvocations.add(invocationId);
            else uncertainInvocations.add(invocationId);
            return committed.isPresent() ? committed
                    : confirmAfterEmpty(runId, invocationId,
                            committedInvocations, uncertainInvocations);
        } catch (RuntimeException commitFailure) {
            uncertainInvocations.add(invocationId);
            try {
                Optional<RunEventEnvelope> existing = findCompletion(runId, invocationId);
                if (existing.isPresent()) {
                    confirmExisting(
                            invocationId, existing, committedInvocations, uncertainInvocations);
                    return existing;
                }
            } catch (RuntimeException verificationFailure) {
                commitFailure.addSuppressed(verificationFailure);
            }
            throw commitFailure;
        }
    }

    UncertainToolOutcome findUnclosed(RunId runId) {
        LinkedHashMap<String, UncertainToolOutcome> open = new LinkedHashMap<>();
        for (RunEventEnvelope event : runs.eventsAfter(runId, 0)) {
            String invocationId = event.payload().path("invocationId").asText("").strip();
            if (invocationId.isEmpty()) continue;
            if (event.type().equals("core.tool.started")) {
                open.put(invocationId, new UncertainToolOutcome(
                        event.payload().path("tool").asText("unknown"), invocationId));
            } else if (event.type().equals("core.tool.completed")
                    || event.type().equals("core.tool.failed")) {
                open.remove(invocationId);
            }
        }
        return open.values().stream().findFirst().orElse(null);
    }

    private Optional<RunEventEnvelope> confirmAfterEmpty(
            RunId runId, String invocationId,
            Set<String> committedInvocations, Set<String> uncertainInvocations) {
        Optional<RunEventEnvelope> existing = findCompletion(runId, invocationId);
        if (existing.isPresent()) {
            confirmExisting(invocationId, existing, committedInvocations, uncertainInvocations);
        }
        return existing;
    }

    private void confirmExisting(
            String invocationId, Optional<RunEventEnvelope> existing,
            Set<String> committedInvocations, Set<String> uncertainInvocations) {
        uncertainInvocations.remove(invocationId);
        committedInvocations.add(invocationId);
        existing.ifPresent(events::publish);
    }

    private Optional<RunEventEnvelope> findCompletion(RunId runId, String invocationId) {
        return runs.eventsAfter(runId, 0).stream()
                .filter(event -> event.type().equals("core.tool.completed"))
                .filter(event -> event.payload().path("invocationId").asText("")
                        .equals(invocationId))
                .findFirst();
    }

    record UncertainToolOutcome(String tool, String invocationId) { }
}
