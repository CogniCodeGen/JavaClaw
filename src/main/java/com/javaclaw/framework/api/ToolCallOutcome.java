package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

public record ToolCallOutcome(JsonNode output, Duration duration, List<ToolCallEvent> events) {
    public ToolCallOutcome {
        output = output == null ? null : output.deepCopy();
        events = List.copyOf(events == null ? List.of() : events);
    }
    @Override public JsonNode output() { return output == null ? null : output.deepCopy(); }
}
