package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

public record ToolCallOutcome(JsonNode output, Duration duration, List<ToolCallEvent> events,
                              ToolExecutionStatus status, String errorCode,
                              String displayMessage) {
    public ToolCallOutcome {
        output = output == null ? null : output.deepCopy();
        events = List.copyOf(events == null ? List.of() : events);
        status = status == null ? ToolExecutionStatus.UNKNOWN : status;
        errorCode = errorCode == null ? "" : errorCode;
        displayMessage = displayMessage == null ? "" : displayMessage;
    }
    public ToolCallOutcome(JsonNode output, Duration duration, List<ToolCallEvent> events) {
        this(output, duration, events, ToolExecutionStatus.UNKNOWN, "", "");
    }
    public ToolCallOutcome(JsonNode output, Duration duration, List<ToolCallEvent> events,
                           ToolExecutionStatus status) {
        this(output, duration, events, status, "", "");
    }
    @Override public JsonNode output() { return output == null ? null : output.deepCopy(); }
}
