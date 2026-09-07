package com.javaclaw.model;

import java.util.Locale;

import com.anthropic.models.messages.OutputConfig;
import com.openai.models.ReasoningEffort;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

import com.javaclaw.api.ReasoningPreference;

/** 显式映射各 Provider 的推理参数；没有等价选项时在网络调用前拒绝，避免静默改变冻结配置。 */
final class AdapterReasoningMapping {
    private AdapterReasoningMapping() {}

    static ReasoningEffort openAi(ReasoningPreference preference) {
        return switch (preference) {
            case NONE -> ReasoningEffort.NONE;
            case MINIMAL -> ReasoningEffort.MINIMAL;
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
            case XHIGH -> ReasoningEffort.XHIGH;
            case MAX -> throw unsupported("OpenAI", preference);
        };
    }

    static String openAiCompatible(ReasoningPreference preference) {
        openAi(preference);
        return preference.name().toLowerCase(Locale.ROOT);
    }

    static void anthropic(AnthropicChatOptions.Builder builder, ReasoningPreference preference) {
        switch (preference) {
            case NONE -> builder.thinkingDisabled();
            case LOW, MEDIUM, HIGH, MAX ->
                builder.thinkingAdaptive()
                        .effort(OutputConfig.Effort.of(preference.name().toLowerCase(Locale.ROOT)));
            case MINIMAL, XHIGH -> throw unsupported("Anthropic", preference);
        }
    }

    static void google(GoogleGenAiChatOptions.Builder builder, ReasoningPreference preference) {
        switch (preference) {
            case NONE -> builder.thinkingBudget(0);
            case MINIMAL, LOW, MEDIUM, HIGH ->
                builder.thinkingLevel(GoogleGenAiThinkingLevel.valueOf(preference.name()));
            case XHIGH, MAX -> throw unsupported("Google", preference);
        }
    }

    private static IllegalArgumentException unsupported(String provider, ReasoningPreference preference) {
        return new IllegalArgumentException(provider + " 不支持精确推理选项 " + preference + "，请显式选择支持的选项");
    }
}
