package com.javaclaw.application.settings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 内置提供商元数据；不包含任何工作区状态或 SDK 类型。 */
public final class DefaultModelProviderCatalog implements ModelProviderCatalog {
    public static final String DELIVERANCE = "deliverance";
    private static final Set<Capability> CLOUD_CHAT = Set.of(
            Capability.CHAT, Capability.THINKING, Capability.TOOLS, Capability.STREAMING);
    private static final Set<Field> CLOUD_FIELDS = Set.of(Field.BASE_URL, Field.MODEL_NAME, Field.API_KEY);
    private static final List<Provider> PROVIDERS = List.of(
            new Provider("dashscope", "DashScope", "阿里云百炼 OpenAI 兼容 API",
                    Set.of("dashscope", "aliyun", "百炼"), CLOUD_FIELDS,
                    union(CLOUD_CHAT, Capability.EMBEDDING),
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen-turbo", "text-embedding-v3", 1024, false),
            new Provider("openai", "OpenAI", "OpenAI 及兼容 API", Set.of("openai"), CLOUD_FIELDS,
                    union(CLOUD_CHAT, Capability.EMBEDDING), "https://api.openai.com/v1",
                    "gpt-4o-mini", "text-embedding-3-small", 1024, false),
            new Provider("anthropic", "Anthropic", "Anthropic Claude API", Set.of("anthropic", "claude"),
                    CLOUD_FIELDS, CLOUD_CHAT, "https://api.anthropic.com",
                    "claude-haiku-4-5-20251001", "", 0, false),
            new Provider("gemini", "Gemini", "Google Gemini API",
                    Set.of("gemini", "google", "google-genai"), CLOUD_FIELDS, CLOUD_CHAT,
                    "https://generativelanguage.googleapis.com", "gemini-2.5-flash", "", 0, false),
            new Provider("ollama", "Ollama", "外部 Ollama 服务", Set.of("ollama"), CLOUD_FIELDS,
                    union(CLOUD_CHAT, Capability.EMBEDDING), "http://127.0.0.1:11434",
                    "qwen3:8b", "nomic-embed-text", 768, false),
            new Provider(DELIVERANCE, "Deliverance（本地托管）", "JavaClaw 管理的隔离本地推理运行时",
                    Set.of("deliverance", "local-deliverance", "本地推理"), Set.of(Field.MANAGED_PROFILE),
                    Set.of(Capability.CHAT, Capability.EMBEDDING, Capability.THINKING,
                            Capability.TOOLS, Capability.STREAMING), "内部服务插件 Socket（不可编辑）",
                    "", "", 0, true));

    @Override public List<Provider> providers() { return PROVIDERS; }

    @Override
    public Optional<Provider> find(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return PROVIDERS.stream().filter(provider -> provider.id().equals(normalized)
                || provider.displayName().toLowerCase(Locale.ROOT).equals(normalized)
                || provider.legacyNames().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(normalized)))
                .findFirst();
    }

    @Override
    public String normalizeId(String value) {
        return find(value).map(Provider::id).orElseGet(() ->
                value == null ? "" : value.strip().toLowerCase(Locale.ROOT));
    }

    private static Set<Capability> union(Set<Capability> values, Capability extra) {
        var result = java.util.EnumSet.copyOf(values);
        result.add(extra);
        return Set.copyOf(result);
    }
}
