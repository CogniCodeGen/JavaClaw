package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

/** The only way the reasoning adapter can add framework run events. */
@FunctionalInterface
public interface ReasoningEventSink {
    void emit(String type, int schemaVersion, String producer, JsonNode payload);
}
