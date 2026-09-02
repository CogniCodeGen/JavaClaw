package com.javaclaw.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;

final class ModelAdapterTestFixtures {
    static final String ENDPOINT_ID = "test-model";
    static final ToolIdentity TOOL_ID = new ToolIdentity("core", "read_file", 3);
    static final ToolDescriptor TOOL = new ToolDescriptor(
            TOOL_ID,
            "读取工作区文件",
            new CanonicalPayload("{\"properties\":{},\"type\":\"object\"}"),
            new CanonicalPayload("{\"properties\":{},\"type\":\"object\"}"),
            ToolRisk.READ_ONLY,
            Set.of("file"));

    private ModelAdapterTestFixtures() {}

    static ModelInvocation invocation(List<ModelMessage> messages, List<ToolDescriptor> tools) {
        return new ModelInvocation(ENDPOINT_ID, "系统说明", messages, tools, 256);
    }

    static ModelInvocation simpleInvocation() {
        ModelMessage user = new ModelMessage(MessageRole.USER, "问题", List.of(), Optional.empty(), Optional.empty());
        return invocation(List.of(user), List.of());
    }

    static ModelToolCall toolCall(String callId) {
        return new ModelToolCall(callId, TOOL_ID, new CanonicalPayload("{\"path\":\"README.md\"}"));
    }

    static ModelInvocationResult result() {
        return new ModelInvocationResult(
                "完成",
                List.of(),
                new ModelUsage(2, 1, 0, 0),
                Optional.empty(),
                Optional.empty(),
                com.javaclaw.runtime.ModelFinishReason.COMPLETE);
    }

    static ChatResponse response(AssistantMessage output, ChatGenerationMetadata generationMetadata, Usage usage) {
        Generation generation =
                generationMetadata == null ? new Generation(output) : new Generation(output, generationMetadata);
        ChatResponseMetadata metadata =
                ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
