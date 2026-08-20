package com.javaclaw.plugins.deliverance;

import java.util.List;
import java.util.Map;

/**
 * Generated-shape DTOs for JavaClaw's canonical protocol/inference-runtime.yaml.
 * Keep this package private to the service plugin; the Host owns independent DTOs.
 */
final class Protocol {
    static final String SOURCE_SHA256 = "f341a0a0793001248e8cfc95a1cf5a3149592012d549b9ffdbcf7a5701be5f01";
    private Protocol() { }

    record StartupConfig(
            String runtimeId,
            String modelPath,
            String modelKind,
            String modelName,
            int contextLength,
            int embeddingDimensions,
            Map<String, Object> loadParameters,
            Map<String, Object> defaultParameters,
            boolean probeMode) {
        StartupConfig {
            loadParameters = loadParameters == null ? Map.of() : Map.copyOf(loadParameters);
            defaultParameters = defaultParameters == null ? Map.of() : Map.copyOf(defaultParameters);
        }
    }

    record Message(String role, String content, String reasoningContent,
                   String toolCallId, List<ToolCall> toolCalls) {
        Message {
            content = content == null ? "" : content;
            reasoningContent = reasoningContent == null ? "" : reasoningContent;
            toolCallId = toolCallId == null ? "" : toolCallId;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    record Tool(String name, String description, Map<String, Object> inputSchema) {
        Tool {
            description = description == null ? "" : description;
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }

    record ToolCall(String id, String name, String argumentsJson) { }
    record ToolChoice(String mode, String toolName) {
        ToolChoice {
            mode = mode == null ? "AUTO" : mode;
            toolName = toolName == null ? "" : toolName;
        }
    }
    record Usage(long promptTokens, long completionTokens) { }

    record ChatRequest(String requestId, List<Message> messages, List<Tool> tools,
                       Map<String, Object> parameters, ToolChoice toolChoice,
                       boolean parallelToolCalls, boolean stream) {
        ChatRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            toolChoice = toolChoice == null ? new ToolChoice("AUTO", "") : toolChoice;
        }
    }

    record ChatResponse(String requestId, String model, String content, String reasoningContent,
                        List<ToolCall> toolCalls, String finishReason, Usage usage,
                        long queueTimeMs, long inferenceTimeMs) { }

    record EmbeddingRequest(String requestId, List<String> input) {
        EmbeddingRequest { input = input == null ? List.of() : List.copyOf(input); }
    }

    record EmbeddingResponse(String requestId, String model, int dimensions,
                             List<float[]> embeddings, Usage usage, long inferenceTimeMs) { }

    record StreamEvent(String requestId, String type, String text, List<ToolCall> toolCalls,
                       Usage usage, ChatResponse response, String errorCode, String errorMessage,
                       long timestampEpochMilli) { }

    record ErrorEnvelope(ErrorBody error) { }
    record ErrorBody(String message, String type, String param, String code, Usage usage) { }
}
