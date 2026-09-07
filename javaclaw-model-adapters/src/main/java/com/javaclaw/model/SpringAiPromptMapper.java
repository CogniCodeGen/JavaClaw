package com.javaclaw.model;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelMessage;

/** 将冻结层映射为 Spring AI Prompt；其统一消息模型没有 developer role，只在此边界按固定顺序合并。 */
final class SpringAiPromptMapper {
    Prompt map(ModelInvocation invocation, ChatOptions options) {
        List<Message> messages = new ArrayList<>();
        String merged = AdapterInstructionMapping.merged(invocation.instructions());
        if (!merged.isBlank()) {
            messages.add(new SystemMessage(merged));
        }
        invocation.messages().stream().map(this::mapMessage).forEach(messages::add);
        return new Prompt(messages, options);
    }

    private Message mapMessage(ModelMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.text());
            case USER -> new UserMessage(message.text());
            case ASSISTANT -> assistant(message);
            case TOOL -> toolResponse(message);
        };
    }

    private AssistantMessage assistant(ModelMessage message) {
        List<AssistantMessage.ToolCall> calls = message.toolCalls().stream()
                .map(call -> new AssistantMessage.ToolCall(
                        call.callId(),
                        "function",
                        call.tool().name(),
                        call.arguments().json()))
                .toList();
        return AssistantMessage.builder()
                .content(message.text())
                .toolCalls(calls)
                .build();
    }

    private ToolResponseMessage toolResponse(ModelMessage message) {
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                message.toolCallId().orElseThrow(), message.toolName().orElseThrow(), message.text());
        return ToolResponseMessage.builder().responses(List.of(response)).build();
    }
}
