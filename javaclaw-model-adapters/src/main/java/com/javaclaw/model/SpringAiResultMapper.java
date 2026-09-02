package com.javaclaw.model;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;

/** 把 Spring AI 最终响应映射为 Harness 契约。 */
final class SpringAiResultMapper {
    private final CanonicalJsonCodec json;

    SpringAiResultMapper(CanonicalJsonCodec json) {
        this.json = json;
    }

    ModelInvocationResult map(ModelInvocation invocation, ChatResponse response) {
        AssistantMessage output = requireOutput(response);
        Map<String, ToolDescriptor> tools = toolIndex(invocation.tools());
        List<ModelToolCall> calls =
                output.getToolCalls().stream().map(call -> mapCall(call, tools)).toList();
        String text = visibleText(output);
        Optional<String> reasoning = metadataText(output, "thoughts");
        ModelUsage usage = mapUsage(response.getMetadata().getUsage());
        ModelFinishReason reason = calls.isEmpty() ? finishReason(response) : ModelFinishReason.TOOL_CALLS;
        return new ModelInvocationResult(text, calls, usage, reasoning, Optional.empty(), reason);
    }

    private AssistantMessage requireOutput(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Spring AI 未返回模型结果");
        }
        return response.getResult().getOutput();
    }

    private Map<String, ToolDescriptor> toolIndex(List<ToolDescriptor> descriptors) {
        Map<String, ToolDescriptor> index = new HashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            ToolDescriptor previous = index.put(descriptor.identity().name(), descriptor);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate tool name: " + descriptor.identity().name());
            }
        }
        return Map.copyOf(index);
    }

    private ModelToolCall mapCall(AssistantMessage.ToolCall call, Map<String, ToolDescriptor> tools) {
        ToolDescriptor descriptor = tools.get(call.name());
        if (descriptor == null) {
            throw new IllegalStateException("模型调用了冻结目录之外的工具: " + call.name());
        }
        String arguments = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
        return new ModelToolCall(call.id(), descriptor.identity(), json.object(arguments));
    }

    private String visibleText(AssistantMessage output) {
        return metadataText(output, "outputWithoutThoughts").orElseGet(() -> value(output.getText()));
    }

    private Optional<String> metadataText(AssistantMessage output, String key) {
        Object value = output.getMetadata().get(key);
        if (!(value instanceof String text) || text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    private ModelUsage mapUsage(Usage usage) {
        if (usage == null) {
            return ModelUsage.zero();
        }
        long input = nonNegative(usage.getPromptTokens());
        long output = nonNegative(usage.getCompletionTokens());
        long cached = Math.min(input, nonNegative(usage.getCacheReadInputTokens()));
        return new ModelUsage(input, output, 0, cached);
    }

    private ModelFinishReason finishReason(ChatResponse response) {
        ChatGenerationMetadata metadata = response.getResult().getMetadata();
        if (metadata == null || metadata == ChatGenerationMetadata.NULL) {
            return ModelFinishReason.COMPLETE;
        }
        if (!metadata.getContentFilters().isEmpty()) {
            return ModelFinishReason.CONTENT_FILTER;
        }
        String reason = value(metadata.getFinishReason()).toLowerCase(Locale.ROOT);
        if (reason.contains("length") || reason.contains("max_token")) {
            return ModelFinishReason.LENGTH;
        }
        if (reason.contains("filter") || reason.contains("safety")) {
            return ModelFinishReason.CONTENT_FILTER;
        }
        if (reason.isEmpty() || reason.contains("stop") || reason.contains("end_turn")) {
            return ModelFinishReason.COMPLETE;
        }
        return ModelFinishReason.OTHER;
    }

    private static long nonNegative(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
