package com.javaclaw.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic framework boundary used to verify product request ownership. */
public final class CapturingLifecycleClient implements AgentClient {
    public final List<RunRequest> requests = new ArrayList<>();
    public final List<RunRequest> coordinators = new ArrayList<>();
    public final Map<String, Turn> turns = new LinkedHashMap<>();
    @Override public boolean supportsManagedTurns() { return true; }
    @Override public ManagedTurn beginTurn(RunRequest request) {
        coordinators.add(request);
        return turns.computeIfAbsent(request.idempotencyKey(), ignored -> new Turn());
    }
    @Override public RunHandle start(RunRequest request) {
        requests.add(request);
        Turn result = new Turn();
        result.complete(JsonNodeFactory.instance.objectNode().put("text", "完成"));
        return result;
    }
    @Override public RunHandle resume(RunId id, ResumeCommand command) {
        return turns.values().stream().filter(turn -> turn.id.equals(id)).findFirst().orElseThrow();
    }
    @Override public boolean cancel(RunId id, CancelReason reason) {
        for (Turn turn : turns.values()) {
            if (turn.id.equals(id)) {
                turn.state = RunState.CANCELLED;
                return turn.done.complete(new RunOutcome(id, RunState.CANCELLED, null, null));
            }
        }
        return false;
    }
    @Override public RunSnapshot get(RunId id) { throw new UnsupportedOperationException(); }

    public static final class Turn implements ManagedTurn {
        public final RunId id = RunId.random();
        public final List<RunEventEnvelope> events = new ArrayList<>();
        public final CompletableFuture<RunOutcome> done = new CompletableFuture<>();
        public RunState state = RunState.RUNNING;
        @Override public RunId id() { return id; }
        @Override public CompletionStage<Void> ready() { return CompletableFuture.completedFuture(null); }
        @Override public Flux<RunEventEnvelope> events(long sequence) {
            return Flux.fromIterable(events).filter(event -> event.sequence() > sequence);
        }
        @Override public CompletionStage<RunOutcome> completion() { return done; }
        @Override public void emit(String type, JsonNode payload) {
            events.add(new RunEventEnvelope(id.value(), events.size() + 1L, Instant.now(), type,
                    1, "test", null, null, payload));
        }
        @Override public void complete(JsonNode output) {
            state = RunState.COMPLETED;
            done.complete(new RunOutcome(id, state, output, null));
        }
        @Override public void fail(Throwable failure) {
            state = RunState.FAILED;
            done.complete(new RunOutcome(id, state, null, failure.toString()));
        }
        @Override public void pause(String reason) { state = RunState.PAUSED; }
        @Override public void waitingInput(JsonNode context, String reason) { state = RunState.WAITING_INPUT; }
        @Override public boolean cancelled() { return state == RunState.CANCELLED; }
        @Override public void close() { }
    }
}
