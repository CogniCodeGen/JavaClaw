package com.javaclaw.desktop.settings;

import java.net.URI;
import java.util.Objects;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;

/** 为 Provider 表单生成不含凭据的最终请求地址预览。 */
final class ProviderEndpointPreview {
    private ProviderEndpointPreview() {}

    static String describe(ProviderDraft draft) {
        ProviderDraft checked = Objects.requireNonNull(draft, "draft");
        String configured = checked.baseUri().strip();
        try {
            URI uri = URI.create(configured.isEmpty() ? defaultBase(checked) : configured);
            if (!configured.isEmpty()) {
                ProviderEndpointSpec.validateBaseUri(checked.adapter(), checked.authentication(), uri);
            }
            uri = uri.normalize();
            String base = stripTrailingSlash(uri.toString());
            String requestBase = requestBase(checked, base);
            String source = configured.isEmpty() ? "官方默认" : "自定义";
            return source + " API 根地址：" + base + routes(checked, requestBase);
        } catch (IllegalArgumentException invalid) {
            return "地址格式无效：保存时将拒绝该配置。";
        }
    }

    private static String requestBase(ProviderDraft draft, String base) {
        if (draft.adapter() != ProviderAdapter.GOOGLE_GENAI || draft.baseUri().isBlank()) {
            return base;
        }
        String apiVersion =
                draft.apiVersion().isBlank() ? "v1beta" : draft.apiVersion().strip();
        return append(base, apiVersion);
    }

    private static String defaultBase(ProviderDraft draft) {
        return switch (draft.adapter()) {
            case OPENAI_COMPATIBLE, OPENAI_RESPONSES -> "https://api.openai.com/v1";
            case ANTHROPIC -> "https://api.anthropic.com/v1";
            case GOOGLE_GENAI ->
                "https://generativelanguage.googleapis.com/"
                        + (draft.apiVersion().isBlank()
                                ? "v1beta"
                                : draft.apiVersion().strip());
        };
    }

    private static String routes(ProviderDraft draft, String base) {
        return switch (draft.adapter()) {
            case OPENAI_COMPATIBLE ->
                "\n模型目录：" + append(base, "models")
                        + "\n对话：" + append(base, "chat/completions")
                        + "\n向量：" + append(base, "embeddings")
                        + "\nResponses：不支持";
            case ANTHROPIC ->
                "\n模型目录：" + append(base, "models") + "\n对话：" + append(base, "messages") + "\n向量：不支持\nResponses：不支持";
            case GOOGLE_GENAI ->
                "\n模型目录：" + append(base, "models")
                        + "\n对话：" + append(base, "models/{modelId}:streamGenerateContent")
                        + "\n向量：" + append(base, "models/{modelId}:embedContent")
                        + "\nResponses：不支持";
            case OPENAI_RESPONSES ->
                "\n模型目录：" + append(base, "models")
                        + "\n对话：不使用 Chat Completions"
                        + "\n向量：不支持"
                        + "\nResponses：" + append(base, "responses")
                        + "\n原生压缩：" + append(base, "responses/compact");
        };
    }

    private static String append(String base, String path) {
        return stripTrailingSlash(base) + "/" + path;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
