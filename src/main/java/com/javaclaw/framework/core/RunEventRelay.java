package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import reactor.core.publisher.Sinks;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local bridge from committed run events to the replay sink owned by an active run.
 * Durable storage and the outbox remain the source of truth; this relay only closes the live
 * delivery gap for producers that append outside {@link AgentEngine}.
 */
public final class RunEventRelay {
    private final ConcurrentHashMap<String, Target> active =
            new ConcurrentHashMap<>();

    void attach(RunId runId, Sinks.Many<RunEventEnvelope> sink) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sink, "sink");
        Target target = new Target(sink);
        Target existing = active.putIfAbsent(runId.value(), target);
        if (existing != null && existing.sink != sink) {
            throw new IllegalStateException("run event relay already attached: " + runId);
        }
    }

    void detach(RunId runId, Sinks.Many<RunEventEnvelope> sink) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sink, "sink");
        active.computeIfPresent(runId.value(), (ignored, target) ->
                target.sink == sink ? null : target);
    }

    void publish(RunEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        Target target = active.get(event.runId());
        if (target == null) return;
        synchronized (target) {
            if (active.get(event.runId()) == target) target.sink.tryEmitNext(event);
        }
    }

    void complete(RunId runId, Sinks.Many<RunEventEnvelope> sink) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sink, "sink");
        Target target = active.get(runId.value());
        if (target == null || target.sink != sink) return;
        synchronized (target) {
            if (active.get(runId.value()) == target) target.sink.tryEmitComplete();
        }
    }

    int activeRunCount() {
        return active.size();
    }

    private static final class Target {
        private final Sinks.Many<RunEventEnvelope> sink;

        private Target(Sinks.Many<RunEventEnvelope> sink) {
            this.sink = sink;
        }
    }
}
