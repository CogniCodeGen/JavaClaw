package com.javaclaw.inference.api;

import java.time.Duration;
import java.util.List;

/** 完整对话结果。 */
public record InferenceChatResponse(
        String requestId,
        String model,
        String content,
        String reasoningContent,
        List<InferenceToolCall> toolCalls,
        FinishReason finishReason,
        InferenceUsage usage,
        Duration queueTime,
        Duration inferenceTime) {

    public InferenceChatResponse {
        requestId = requestId == null ? "" : requestId;
        model = model == null ? "" : model;
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? FinishReason.ERROR : finishReason;
        usage = usage == null ? new InferenceUsage(0, 0) : usage;
        queueTime = queueTime == null ? Duration.ZERO : queueTime;
        inferenceTime = inferenceTime == null ? Duration.ZERO : inferenceTime;
    }

    public enum FinishReason { STOP, LENGTH, TOOL_CALLS, CANCELLED, CONTENT_FILTER, ERROR }
}
