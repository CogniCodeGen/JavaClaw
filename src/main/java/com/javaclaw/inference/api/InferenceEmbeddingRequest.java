package com.javaclaw.inference.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 批量嵌入请求。 */
public record InferenceEmbeddingRequest(
        String requestId,
        UUID profileId,
        List<String> input,
        Duration timeout,
        InferenceRequestPriority priority) {

    public InferenceEmbeddingRequest {
        requestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.strip();
        if (profileId == null) throw new IllegalArgumentException("嵌入档案 ID 不能为空");
        input = input == null ? List.of() : List.copyOf(input);
        if (input.isEmpty()) throw new IllegalArgumentException("嵌入输入不能为空");
        timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("嵌入超时必须大于零");
        }
        priority = priority == null ? InferenceRequestPriority.INTERNAL : priority;
    }

    public InferenceEmbeddingRequest(String requestId, UUID profileId, List<String> input,
                                     Duration timeout) {
        this(requestId, profileId, input, timeout, InferenceRequestPriority.INTERNAL);
    }
}
