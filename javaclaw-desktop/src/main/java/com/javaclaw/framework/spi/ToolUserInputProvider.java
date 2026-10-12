package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

/** Exact host implementations only. Answers come from this run's durable waiting/resumed pair. */
public interface ToolUserInputProvider {
    default void bindUserInputRun(String runId) { }
    default void prepareUserInputAnswer(JsonNode question, JsonNode answer, long questionSequence, long answerSequence) { }
    ToolUserInputCheckpoint consumeUserInputCheckpoint(String tool);
}
