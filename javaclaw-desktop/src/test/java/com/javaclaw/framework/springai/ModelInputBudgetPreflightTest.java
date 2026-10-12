package com.javaclaw.framework.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInputBudgetPreflightTest {
    @Test void countsLargeToolResultsBeforeProviderDispatch() {
        var response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "desktop_session_observe",
                        "x".repeat(32_000)))).build();
        Prompt prompt = new Prompt(List.of(new SystemMessage("instructions"), response));

        assertTrue(ModelInputBudgetPreflight.approximatePromptFloor(prompt) > 1_724);
    }
}
