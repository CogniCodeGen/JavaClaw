package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

/** A durable turn driven by an existing task orchestrator instead of a model loop. */
public interface ManagedTurn extends RunHandle, AutoCloseable {
    java.util.concurrent.CompletionStage<Void> ready();
    void emit(String type, JsonNode payload);
    void complete(JsonNode output);
    void fail(Throwable failure);
    void pause(String reason);
    void waitingInput(JsonNode context, String reason);
    boolean cancelled();
    /** Acknowledge that the external driver has exited, including after cancellation. */
    @Override default void close() { }
}
