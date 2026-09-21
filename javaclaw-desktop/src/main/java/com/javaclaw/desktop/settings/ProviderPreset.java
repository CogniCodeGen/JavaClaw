package com.javaclaw.desktop.settings;

import java.util.Arrays;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;

/** 仅用于填写连接草稿的只读预设；不保存服务、不发起请求，也不声明模型能力。 */
enum ProviderPreset {
    BAILIAN("阿里云百炼", ProviderAdapter.OPENAI_COMPATIBLE, "", ProviderAuthentication.API_KEY),
    DEEPSEEK("DeepSeek", ProviderAdapter.OPENAI_COMPATIBLE, "https://api.deepseek.com", ProviderAuthentication.API_KEY),
    SILICON_FLOW(
            "硅基流动", ProviderAdapter.OPENAI_COMPATIBLE, "https://api.siliconflow.cn/v1", ProviderAuthentication.API_KEY),
    OPENAI("OpenAI", ProviderAdapter.OPENAI_COMPATIBLE, "https://api.openai.com/v1", ProviderAuthentication.API_KEY),
    ANTHROPIC("Anthropic", ProviderAdapter.ANTHROPIC, "https://api.anthropic.com", ProviderAuthentication.API_KEY),
    GEMINI(
            "Gemini",
            ProviderAdapter.GOOGLE_GENAI,
            "https://generativelanguage.googleapis.com",
            ProviderAuthentication.API_KEY),
    OPENROUTER(
            "OpenRouter",
            ProviderAdapter.OPENAI_COMPATIBLE,
            "https://openrouter.ai/api/v1",
            ProviderAuthentication.API_KEY),
    OLLAMA("Ollama", ProviderAdapter.OPENAI_COMPATIBLE, "http://localhost:11434/v1", ProviderAuthentication.NONE),
    CUSTOM("自定义服务", ProviderAdapter.OPENAI_COMPATIBLE, "", ProviderAuthentication.API_KEY);

    private final String label;
    private final ProviderAdapter adapter;
    private final String baseUri;
    private final ProviderAuthentication authentication;

    ProviderPreset(String label, ProviderAdapter adapter, String baseUri, ProviderAuthentication authentication) {
        this.label = label;
        this.adapter = adapter;
        this.baseUri = baseUri;
        this.authentication = authentication;
    }

    String label() {
        return label;
    }

    ProviderAdapter adapter() {
        return adapter;
    }

    String baseUri() {
        return baseUri;
    }

    ProviderAuthentication authentication() {
        return authentication;
    }

    String apiVersion() {
        return this == GEMINI ? "v1beta" : "";
    }

    static ProviderPreset matching(ProviderDraft draft) {
        String address = draft.baseUri().strip().replaceFirst("/$", "");
        if (address.isEmpty() && !draft.id().isEmpty()) {
            return switch (draft.adapter()) {
                case OPENAI_COMPATIBLE, OPENAI_RESPONSES -> OPENAI;
                case ANTHROPIC -> ANTHROPIC;
                case GOOGLE_GENAI -> GEMINI;
            };
        }
        if (draft.adapter() == ProviderAdapter.OPENAI_COMPATIBLE
                && BailianRegion.matching(address) != BailianRegion.CONSOLE) {
            return BAILIAN;
        }
        return Arrays.stream(values())
                .filter(value -> value != BAILIAN && value != CUSTOM)
                .filter(value ->
                        value.matchesAdapter(draft.adapter()) && value.authentication == draft.authentication())
                .filter(value -> value.baseUri.equals(address))
                .findFirst()
                .orElse(CUSTOM);
    }

    private boolean matchesAdapter(ProviderAdapter value) {
        return adapter == value || this == OPENAI && value == ProviderAdapter.OPENAI_RESPONSES;
    }

    /** 百炼地域与密钥相互绑定；其他地域及业务空间只接受控制台提供的完整地址。 */
    enum BailianRegion {
        BEIJING("中国内地（北京）", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        SINGAPORE("国际（新加坡）", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
        CONSOLE("其他地域 / 业务空间：填写控制台完整 Base URL", "");

        private final String label;
        private final String baseUri;

        BailianRegion(String label, String baseUri) {
            this.label = label;
            this.baseUri = baseUri;
        }

        String label() {
            return label;
        }

        String baseUri() {
            return baseUri;
        }

        static BailianRegion matching(String address) {
            return Arrays.stream(values())
                    .filter(value -> !value.baseUri.isEmpty() && value.baseUri.equals(address))
                    .findFirst()
                    .orElse(CONSOLE);
        }
    }
}
