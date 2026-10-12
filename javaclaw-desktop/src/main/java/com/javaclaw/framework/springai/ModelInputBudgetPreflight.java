package com.javaclaw.framework.springai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Deliberately weak provider-independent prompt floor for budget admission.
 * One token per sixteen text characters is only a heuristic, not a tokenizer
 * guarantee. It catches clearly unaffordable calls while avoiding rejection
 * near the limit based on a precise-looking but model-inaccurate estimate.
 * Provider usage remains authoritative and is charged after the response.
 */
final class ModelInputBudgetPreflight {
    private static final long CHARACTERS_PER_TOKEN = 16;
    private static final long MEDIA_TOKEN_FLOOR = 64;

    private ModelInputBudgetPreflight() { }

    static long approximatePromptFloor(Prompt prompt) {
        long characters = 0;
        long media = 0;
        for (Message message : prompt.getInstructions()) {
            characters += 24 + length(message.getText());
            if (message instanceof AssistantMessage assistant) {
                for (var call : assistant.getToolCalls()) {
                    characters += length(call.id()) + length(call.name()) + length(call.arguments());
                }
            }
            if (message instanceof ToolResponseMessage response) {
                for (var value : response.getResponses()) {
                    characters += length(value.id()) + length(value.name()) + length(value.responseData());
                }
            }
            if (message instanceof UserMessage user) media += user.getMedia().size();
        }
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            for (var callback : options.getToolCallbacks()) {
                characters += SpringAiToolCatalog.schemaCharacters(callback);
            }
        }
        return Math.max(1, (characters + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN
                + MEDIA_TOKEN_FLOOR * media);
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }
}
