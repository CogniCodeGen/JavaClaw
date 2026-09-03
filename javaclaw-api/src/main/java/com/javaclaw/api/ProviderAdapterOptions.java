package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Provider Adapter 的强类型非敏感选项。
 *
 * <p>每个实现只暴露对应厂商支持的字段，调用方不能构造携带跨厂商字段的宽配置。Wire 边界仍由 Protocol codec 统一编码为带 {@code adapter} 判别字段的稳定对象。
 */
public sealed interface ProviderAdapterOptions
        permits ProviderAdapterOptions.OpenAiCompatible,
                ProviderAdapterOptions.Anthropic,
                ProviderAdapterOptions.GoogleGenAi,
                ProviderAdapterOptions.OpenAiResponses {
    /**
     * 返回选项所属的 Adapter。
     *
     * @return 与具体选项类型唯一对应的 Adapter
     */
    ProviderAdapter adapter();

    /**
     * 创建指定 Adapter 的默认选项。
     *
     * @param adapter Adapter
     * @return 不覆盖厂商默认值的对应强类型选项
     */
    static ProviderAdapterOptions defaults(ProviderAdapter adapter) {
        return switch (Objects.requireNonNull(adapter, "adapter")) {
            case OPENAI_COMPATIBLE -> new OpenAiCompatible(Optional.empty(), Optional.empty());
            case ANTHROPIC -> new Anthropic();
            case GOOGLE_GENAI -> new GoogleGenAi(Optional.empty());
            case OPENAI_RESPONSES ->
                new OpenAiResponses(Optional.empty(), Optional.empty(), ProviderReasoningSummary.AUTO);
        };
    }

    /**
     * OpenAI-compatible Chat Completions 与 Embeddings 的高级选项。
     *
     * @param organization OpenAI organization；不使用时为空
     * @param project OpenAI project；不使用时为空
     */
    record OpenAiCompatible(Optional<String> organization, Optional<String> project) implements ProviderAdapterOptions {
        /** 规范化 OpenAI-compatible 可选文本。 */
        public OpenAiCompatible {
            organization = text(organization, "organization");
            project = text(project, "project");
        }

        @Override
        public ProviderAdapter adapter() {
            return ProviderAdapter.OPENAI_COMPATIBLE;
        }
    }

    /** Anthropic Messages 选项；当前完全采用厂商 SDK 默认值。 */
    record Anthropic() implements ProviderAdapterOptions {
        /** 创建完全采用 Anthropic SDK 默认值的选项。 */
        public Anthropic {}

        @Override
        public ProviderAdapter adapter() {
            return ProviderAdapter.ANTHROPIC;
        }
    }

    /**
     * Google Gen AI 的高级选项。
     *
     * @param apiVersion Google API version；使用 SDK 默认值时为空
     */
    record GoogleGenAi(Optional<String> apiVersion) implements ProviderAdapterOptions {
        /** 规范化并校验 API version 是安全的单个路径段。 */
        public GoogleGenAi {
            apiVersion = text(apiVersion, "apiVersion");
            apiVersion.ifPresent(ProviderAdapterOptions::requireSafeApiVersion);
        }

        @Override
        public ProviderAdapter adapter() {
            return ProviderAdapter.GOOGLE_GENAI;
        }
    }

    /**
     * OpenAI Responses 原生端点的高级选项。
     *
     * @param organization OpenAI organization；不使用时为空
     * @param project OpenAI project；不使用时为空
     * @param reasoningSummary reasoning summary 模式
     */
    record OpenAiResponses(
            Optional<String> organization, Optional<String> project, ProviderReasoningSummary reasoningSummary)
            implements ProviderAdapterOptions {
        /** 规范化 OpenAI 字段并要求明确的 reasoning summary 模式。 */
        public OpenAiResponses {
            organization = text(organization, "organization");
            project = text(project, "project");
            Objects.requireNonNull(reasoningSummary, "reasoningSummary");
        }

        @Override
        public ProviderAdapter adapter() {
            return ProviderAdapter.OPENAI_RESPONSES;
        }
    }

    private static Optional<String> text(Optional<String> value, String name) {
        Optional<String> checked = Objects.requireNonNull(value, name);
        return checked.map(entry -> Preconditions.boundedText(entry, name, 1_000));
    }

    private static void requireSafeApiVersion(String value) {
        if (!value.matches("v[0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("apiVersion must be one safe version path segment");
        }
    }
}
