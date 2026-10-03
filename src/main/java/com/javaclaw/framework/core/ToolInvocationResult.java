package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.ToolExecutionStatus;

import java.time.Duration;

public record ToolInvocationResult(JsonNode output, Duration duration,
                                   ToolExecutionStatus status, String errorCode,
                                   String displayMessage) {
    public ToolInvocationResult {
        output = output.deepCopy();
        status = status == null ? ToolExecutionStatus.UNKNOWN : status;
        errorCode = errorCode == null ? "" : errorCode;
        displayMessage = displayMessage == null ? "" : displayMessage;
    }
    public ToolInvocationResult(JsonNode output, Duration duration) {
        this(output, duration, ToolExecutionStatus.UNKNOWN, "", "");
    }
    public ToolInvocationResult(JsonNode output, Duration duration, ToolExecutionStatus status) {
        this(output, duration, status, "", "");
    }
}
