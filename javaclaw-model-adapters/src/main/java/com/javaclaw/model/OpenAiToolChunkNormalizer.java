package com.javaclaw.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.openai.core.JsonMissing;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta;
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall;

/**
 * 按 choice/index 合并同一个调用的工具续片，正文及 usage 仍逐块透传。
 *
 * <p>状态仅由单次 SDK 订阅的串行回调持有。重复或空白 ID 不创建新调用，迟到的名称可补齐； 参数按到达顺序拼接，身份冲突和不完整结束必须失败，不能猜测名称或执行部分工具。 Spring AI 2.0.0
 * 只收到结束时的完整工具列表，从而不再触发其私有 ChunkMerger 的续片假设。
 */
final class OpenAiToolChunkNormalizer {
    private final Map<Long, Map<Long, ToolState>> pending = new HashMap<>();
    private final Set<Long> completed = new HashSet<>();

    ChatCompletionChunk accept(ChatCompletionChunk chunk) {
        List<Choice> choices = chunk.choices().stream().map(this::choice).toList();
        return chunk.toBuilder().choices(choices).build();
    }

    private Choice choice(Choice choice) {
        List<ToolCall> incoming = choice.delta().toolCalls().orElse(List.of());
        if (!incoming.isEmpty()) {
            if (completed.contains(choice.index())) {
                throw invalid("工具调用结束后仍收到参数");
            }
            Map<Long, ToolState> tools = pending.computeIfAbsent(choice.index(), ignored -> new TreeMap<>());
            for (ToolCall tool : incoming) {
                tools.computeIfAbsent(tool.index(), ignored -> new ToolState(tool))
                        .append(tool);
            }
        }
        Delta.Builder delta = choice.delta().toBuilder().toolCalls(JsonMissing.of());
        if (choice.finishReason().isPresent() && pending.containsKey(choice.index())) {
            if (!Choice.FinishReason.TOOL_CALLS.equals(choice.finishReason().orElseThrow())) {
                throw invalid("工具调用未正常结束");
            }
            delta.toolCalls(complete(pending.get(choice.index())));
            pending.remove(choice.index());
            completed.add(choice.index());
        } else if (Choice.FinishReason.TOOL_CALLS.equals(choice.finishReason().orElse(null))) {
            throw invalid("工具结束标记没有对应调用");
        }
        return choice.toBuilder().delta(delta.build()).build();
    }

    private List<ToolCall> complete(Map<Long, ToolState> states) {
        List<ToolCall> tools = states.values().stream().map(ToolState::complete).toList();
        Set<String> identities = new HashSet<>();
        for (ToolCall tool : tools) {
            if (!identities.add(tool.id().orElseThrow())) {
                throw invalid("不同工具使用了同一个调用 ID");
            }
        }
        return tools;
    }

    void finish() {
        if (!pending.isEmpty()) {
            throw invalid("流结束前未确认完整工具调用");
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("模型返回的工具调用不完整或不一致：" + reason);
    }

    /** 保留 SDK 附加属性，但只在终止帧将完整身份和参数向下游发布。 */
    private static final class ToolState {
        private final ToolCall.Builder tool;
        private final ToolCall.Function.Builder function = ToolCall.Function.builder();
        private final StringBuilder arguments = new StringBuilder();
        private String id;
        private String name;

        private ToolState(ToolCall first) {
            tool = first.toBuilder();
        }

        private void append(ToolCall chunk) {
            if (chunk.type().isPresent()
                    && !ToolCall.Type.FUNCTION.equals(chunk.type().orElseThrow())) {
                throw invalid("不支持该工具类型");
            }
            id = identity(id, chunk.id(), "调用 ID 冲突");
            tool.putAllAdditionalProperties(chunk._additionalProperties());
            chunk.function().ifPresent(value -> {
                name = identity(name, value.name(), "函数名冲突");
                value.arguments().ifPresent(arguments::append);
                function.putAllAdditionalProperties(value._additionalProperties());
            });
        }

        private ToolCall complete() {
            if (id == null || name == null) {
                throw invalid("缺少调用 ID 或函数名");
            }
            return tool.id(id)
                    .function(
                            function.name(name).arguments(arguments.toString()).build())
                    .build();
        }

        private static String identity(String current, Optional<String> delta, String conflict) {
            String value = delta.filter(text -> !text.isBlank()).orElse(current);
            if (current != null && !current.equals(value)) {
                throw invalid(conflict);
            }
            return value;
        }
    }
}
