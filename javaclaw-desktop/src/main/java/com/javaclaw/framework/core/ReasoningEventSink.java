package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

/** The only way the reasoning adapter can add framework run events. */
@FunctionalInterface
public interface ReasoningEventSink {
    void emit(String type, int schemaVersion, String producer, JsonNode payload);

    /** Host implementations persist both starts in one transaction before tool execution. */
    default void toolStarted(JsonNode stepStarted, JsonNode toolStarted) {
        emit("core.step.started", 1, "framework.core", stepStarted);
        emit("core.tool.started", 1, "framework.core", toolStarted);
    }
}
