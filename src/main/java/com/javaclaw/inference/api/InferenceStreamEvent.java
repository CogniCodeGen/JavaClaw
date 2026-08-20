package com.javaclaw.inference.api;

import java.time.Instant;
import java.util.List;

/** 流式事件。一个请求最多有一个 COMPLETE、CANCELLED 或 ERROR 终态。 */
public record InferenceStreamEvent(
        String requestId,
        Type type,
        String text,
        List<InferenceToolCall> toolCalls,
        InferenceUsage usage,
        InferenceChatResponse response,
        String errorCode,
        String errorMessage,
        Instant timestamp) {

    public InferenceStreamEvent {
        requestId = requestId == null ? "" : requestId;
        if (type == null) throw new IllegalArgumentException("流事件类型不能为空");
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public enum Type { STARTED, CONTENT_DELTA, REASONING_DELTA, TOOL_CALL_DELTA, USAGE, COMPLETE, CANCELLED, ERROR }

    public boolean terminal() {
        return type == Type.COMPLETE || type == Type.CANCELLED || type == Type.ERROR;
    }
}
