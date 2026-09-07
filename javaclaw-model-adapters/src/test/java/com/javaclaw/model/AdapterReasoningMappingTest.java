package com.javaclaw.model;

import com.anthropic.models.messages.OutputConfig;
import com.openai.models.ReasoningEffort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

import com.javaclaw.api.ReasoningPreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterReasoningMappingTest {
    @Test
    void 推理偏好只映射Provider支持的精确选项() {
        assertEquals(ReasoningEffort.XHIGH, AdapterReasoningMapping.openAi(ReasoningPreference.XHIGH));
        assertEquals("minimal", AdapterReasoningMapping.openAiCompatible(ReasoningPreference.MINIMAL));
        var anthropic = AnthropicChatOptions.builder();
        AdapterReasoningMapping.anthropic(anthropic, ReasoningPreference.MAX);
        assertEquals(
                OutputConfig.Effort.MAX,
                anthropic.build().getOutputConfig().effort().orElseThrow());
        assertTrue(anthropic.build().getThinking().isAdaptive());
        var google = GoogleGenAiChatOptions.builder();
        AdapterReasoningMapping.google(google, ReasoningPreference.MEDIUM);
        assertEquals(GoogleGenAiThinkingLevel.MEDIUM, google.build().getThinkingLevel());
    }

    @Test
    void 未支持的偏好不会静默降档且关闭推理不增加预算() {
        assertThrows(IllegalArgumentException.class, () -> AdapterReasoningMapping.openAi(ReasoningPreference.MAX));
        assertThrows(
                IllegalArgumentException.class,
                () -> AdapterReasoningMapping.anthropic(AnthropicChatOptions.builder(), ReasoningPreference.XHIGH));
        assertThrows(
                IllegalArgumentException.class,
                () -> AdapterReasoningMapping.google(GoogleGenAiChatOptions.builder(), ReasoningPreference.MAX));
        var anthropic = AnthropicChatOptions.builder().maxTokens(120);
        AdapterReasoningMapping.anthropic(anthropic, ReasoningPreference.NONE);
        assertTrue(anthropic.build().getThinking().isDisabled());
        assertEquals(120, anthropic.build().getMaxTokens());
        var google = GoogleGenAiChatOptions.builder().maxOutputTokens(120);
        AdapterReasoningMapping.google(google, ReasoningPreference.NONE);
        assertEquals(0, google.build().getThinkingBudget());
        assertEquals(120, google.build().getMaxOutputTokens());
    }
}
