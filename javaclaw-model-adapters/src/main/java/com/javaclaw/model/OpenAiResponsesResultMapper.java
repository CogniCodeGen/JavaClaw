package com.javaclaw.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;

/** 将 Responses 最终对象映射为统一模型结果。 */
final class OpenAiResponsesResultMapper {
    private final CanonicalJsonCodec json;
    private final ProviderStateCodec states;

    OpenAiResponsesResultMapper(CanonicalJsonCodec json, ProviderStateCodec states) {
        this.json = json;
        this.states = states;
    }

    ModelInvocationResult map(
            ModelInvocation invocation, Response response, String streamedText, String streamedSummary) {
        Map<String, ToolDescriptor> tools = tools(invocation.tools());
        List<ModelToolCall> calls = response.output().stream()
                .filter(ResponseOutputItem::isFunctionCall)
                .map(ResponseOutputItem::asFunctionCall)
                .map(call -> new ModelToolCall(
                        call.callId(), requireTool(tools, call.name()).identity(), json.object(call.arguments())))
                .toList();
        String text = outputText(response);
        if (text.isEmpty()) {
            text = streamedText;
        }
        String summary = reasoningSummary(response);
        if (summary.isEmpty()) {
            summary = streamedSummary;
        }
        ModelUsage usage = usage(response.usage().orElse(null));
        ModelFinishReason reason = finishReason(response, calls);
        return new ModelInvocationResult(
                text,
                calls,
                usage,
                summary.isEmpty() ? Optional.empty() : Optional.of(summary),
                Optional.of(states.response(response, invocation.systemInstruction())),
                reason);
    }

    private Map<String, ToolDescriptor> tools(List<ToolDescriptor> descriptors) {
        Map<String, ToolDescriptor> result = new HashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            if (result.put(descriptor.identity().name(), descriptor) != null) {
                throw new IllegalArgumentException(
                        "duplicate tool name: " + descriptor.identity().name());
            }
        }
        return Map.copyOf(result);
    }

    private ToolDescriptor requireTool(Map<String, ToolDescriptor> tools, String name) {
        ToolDescriptor descriptor = tools.get(name);
        if (descriptor == null) {
            throw new IllegalStateException("模型调用了冻结目录之外的工具: " + name);
        }
        return descriptor;
    }

    private String outputText(Response response) {
        StringBuilder text = new StringBuilder();
        response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .map(ResponseOutputItem::asMessage)
                .flatMap(message -> message.content().stream())
                .filter(content -> content.isOutputText())
                .map(content -> content.asOutputText().text())
                .forEach(text::append);
        return text.toString();
    }

    private String reasoningSummary(Response response) {
        StringBuilder summary = new StringBuilder();
        response.output().stream()
                .filter(ResponseOutputItem::isReasoning)
                .map(ResponseOutputItem::asReasoning)
                .flatMap(reasoning -> reasoning.summary().stream())
                .map(item -> item.text())
                .forEach(summary::append);
        return summary.toString();
    }

    private boolean hasRefusal(Response response) {
        return response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .map(ResponseOutputItem::asMessage)
                .flatMap(message -> message.content().stream())
                .anyMatch(content -> content.isRefusal());
    }

    private ModelFinishReason finishReason(Response response, List<ModelToolCall> calls) {
        if (!calls.isEmpty()) {
            return ModelFinishReason.TOOL_CALLS;
        }
        if (hasRefusal(response)) {
            return ModelFinishReason.CONTENT_FILTER;
        }
        Optional<ResponseStatus> status = response.status();
        if (status.isEmpty() || status.orElseThrow().equals(ResponseStatus.COMPLETED)) {
            return ModelFinishReason.COMPLETE;
        }
        if (status.orElseThrow().equals(ResponseStatus.INCOMPLETE)) {
            return ModelFinishReason.LENGTH;
        }
        return ModelFinishReason.OTHER;
    }

    private ModelUsage usage(ResponseUsage usage) {
        if (usage == null) {
            return ModelUsage.zero();
        }
        long reasoning = usage.outputTokensDetails().reasoningTokens();
        long visibleOutput = Math.max(0, usage.outputTokens() - reasoning);
        long cached = Math.min(usage.inputTokens(), usage.inputTokensDetails().cachedTokens());
        return new ModelUsage(usage.inputTokens(), visibleOutput, reasoning, cached);
    }
}
