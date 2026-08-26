package com.javaclaw.framework.springai;

import com.javaclaw.util.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTokenEstimatorTest {

    @Test
    void countsStructuredToolCallsResponsesIdsArgumentsAndSchemas() {
        AssistantMessage call = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-123", "function", "web_get_title",
                        "{\"selector\":\"#main\"}")))
                .build();
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-123", "web_get_title", "structured result payload")))
                .build();
        ToolCallback callback = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("web_get_title")
                        .description("Read the current page title")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String input) { return "unused"; }
        };
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("system"), new UserMessage("current"), call, response),
                ToolCallingChatOptions.builder().toolCallbacks(List.of(callback)).build());

        PromptTokenEstimator.Breakdown estimate = PromptTokenEstimator.estimate(prompt);

        assertEquals(1, estimate.toolCount());
        assertTrue(estimate.toolSchemaTokens() > 0);
        assertTrue(estimate.toolResultTokens() >= TokenEstimator.estimate(
                "call-123\nweb_get_title\nstructured result payload"));
        assertTrue(estimate.continuationTokens() >= TokenEstimator.estimate(
                "call-123\nfunction\nweb_get_title\n{\"selector\":\"#main\"}"));
        assertEquals(estimate.systemPromptTokens() + estimate.currentInputTokens()
                        + estimate.continuationTokens() + estimate.toolResultTokens()
                        + estimate.toolSchemaTokens(),
                estimate.totalInputTokens());
    }
}
