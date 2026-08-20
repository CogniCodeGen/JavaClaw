package com.javaclaw.infrastructure.inference.serviceplugin;

import java.util.List;
import java.util.Map;

/** Host-side DTOs generated in shape from classpath protocol/inference-runtime.yaml. */
final class InferencePluginProtocol {
    static final String SOURCE_SHA256 = "f341a0a0793001248e8cfc95a1cf5a3149592012d549b9ffdbcf7a5701be5f01";
    private InferencePluginProtocol() { }

    record Startup(String runtimeId, String modelPath, String modelKind,
                   String modelName, int contextLength, int embeddingDimensions,
                   Map<String, Object> loadParameters, Map<String, Object> defaultParameters,
                   boolean probeMode) { }
    record Message(String role, String content, String reasoningContent,
                   String toolCallId, List<ToolCall> toolCalls) { }
    record Tool(String name, String description, Map<String, Object> inputSchema) { }
    record ToolCall(String id, String name, String argumentsJson) { }
    record ToolChoice(String mode, String toolName) { }
    record Usage(long promptTokens, long completionTokens) { }
    record ChatRequest(String requestId, List<Message> messages, List<Tool> tools,
                       Map<String, Object> parameters, ToolChoice toolChoice,
                       boolean parallelToolCalls, boolean stream) { }
    record ChatResponse(String requestId, String model, String content, String reasoningContent,
                        List<ToolCall> toolCalls, String finishReason, Usage usage,
                        long queueTimeMs, long inferenceTimeMs) { }
    record EmbeddingRequest(String requestId, List<String> input) { }
    record EmbeddingResponse(String requestId, String model, int dimensions,
                             List<float[]> embeddings, Usage usage, long inferenceTimeMs) { }
    record StreamEvent(String requestId, String type, String text, List<ToolCall> toolCalls,
                       Usage usage, ChatResponse response, String errorCode, String errorMessage,
                       long timestampEpochMilli) { }
    record ErrorEnvelope(ErrorBody error) { }
    record ErrorBody(String message, String type, String param, String code, Usage usage) { }
}
