package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.*;
import reactor.core.publisher.Flux;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Product orchestrators may drive a turn, but only the engine owns state transitions. */
final class ManagedTurnAdapter implements ManagedTurn {
    private final RunHandle handle;
    private final CompletionStage<Void> ready;
    private final BiConsumer<String, JsonNode> events;
    private final Consumer<ReasoningResult> result;
    private final Consumer<Throwable> failure;
    private final BooleanSupplier cancelled;
    private final Runnable finished;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    ManagedTurnAdapter(RunHandle handle, CompletionStage<Void> ready, BiConsumer<String, JsonNode> events,
                       Consumer<ReasoningResult> result, Consumer<Throwable> failure, BooleanSupplier cancelled, Runnable finished) {
        this.handle = handle; this.ready = ready; this.events = events;
        this.result = result; this.failure = failure; this.cancelled = cancelled;
        this.finished = finished;
    }
    @Override public RunId id() { return handle.id(); }
    @Override public Flux<RunEventEnvelope> events(long after) { return handle.events(after); }
    @Override public CompletionStage<RunOutcome> completion() { return handle.completion(); }
    @Override public CompletionStage<Void> ready() { return ready; }
    @Override public void emit(String type, JsonNode payload) {
        if (!type.startsWith("core.")) throw new IllegalArgumentException("managed event must use the core namespace");
        events.accept(type, payload);
    }
    @Override public void complete(JsonNode output) { finish(() -> result.accept(ReasoningResult.completed(output))); }
    @Override public void fail(Throwable error) { finish(() -> failure.accept(error)); }
    @Override public void pause(String reason) { finish(() -> result.accept(new ReasoningResult(RunState.PAUSED, null, reason))); }
    @Override public void waitingInput(JsonNode context, String reason) { finish(() -> result.accept(ReasoningResult.waitingForInput(context, reason))); }
    @Override public boolean cancelled() { return cancelled.getAsBoolean(); }
    private void finish(Runnable action) {
        if (closed.get()) return;
        try { action.run(); } finally { close(); }
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) finished.run(); }
}
