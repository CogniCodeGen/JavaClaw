package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

public record ToolInvocationResult(JsonNode output, Duration duration) {
    public ToolInvocationResult {
        output = output.deepCopy();
    }
}
