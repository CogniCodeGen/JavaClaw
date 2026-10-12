package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.javaclaw.framework.api.ToolExecutionStatus;

import java.util.Objects;

/** Typed invocation result. Human-readable text is never an input to status decisions. */
public record ToolExecutionResultV1(
        ToolExecutionStatus status,
        JsonNode data,
        String errorCode,
        String displayMessage) {

    public ToolExecutionResultV1 {
        status = Objects.requireNonNull(status, "status");
        data = data == null ? NullNode.getInstance() : data.deepCopy();
        errorCode = errorCode == null ? "" : errorCode;
        displayMessage = displayMessage == null ? "" : displayMessage;
    }

    public static ToolExecutionResultV1 success(JsonNode data) {
        return new ToolExecutionResultV1(ToolExecutionStatus.SUCCEEDED, data, "", "");
    }

    @Override public JsonNode data() { return data.deepCopy(); }
}
