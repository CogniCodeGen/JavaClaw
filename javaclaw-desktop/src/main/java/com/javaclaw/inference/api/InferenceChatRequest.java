package com.javaclaw.inference.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一次原始模型对话请求。资源边界由档案和网关策略单独限制，不能由 parameters 绕过。 */
public record InferenceChatRequest(
        String requestId,
        UUID profileId,
        List<InferenceMessage> messages,
        List<InferenceTool> tools,
        Map<String, Object> parameters,
        InferenceToolChoice toolChoice,
        boolean parallelToolCalls,
        Duration timeout,
        InferenceRequestPriority priority) {

    public InferenceChatRequest {
        requestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.strip();
        if (profileId == null) throw new IllegalArgumentException("模型档案 ID 不能为空");
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        tools = tools == null ? List.of() : List.copyOf(tools);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        toolChoice = toolChoice == null ? InferenceToolChoice.auto() : toolChoice;
        timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("推理超时必须大于零");
        }
        priority = priority == null ? InferenceRequestPriority.INTERNAL : priority;
    }

    public InferenceChatRequest(String requestId, UUID profileId, List<InferenceMessage> messages,
                                List<InferenceTool> tools, Map<String, Object> parameters,
                                Duration timeout, InferenceRequestPriority priority) {
        this(requestId, profileId, messages, tools, parameters, InferenceToolChoice.auto(), true,
                timeout, priority);
    }

    public InferenceChatRequest(String requestId, UUID profileId, List<InferenceMessage> messages,
                                List<InferenceTool> tools, Map<String, Object> parameters,
                                Duration timeout) {
        this(requestId, profileId, messages, tools, parameters, InferenceToolChoice.auto(), true, timeout,
                InferenceRequestPriority.INTERNAL);
    }
}
