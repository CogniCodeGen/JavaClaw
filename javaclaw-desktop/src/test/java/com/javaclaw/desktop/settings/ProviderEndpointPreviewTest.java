package com.javaclaw.desktop.settings;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEndpointPreviewTest {
    @Test
    void openAi兼容地址展示模型对话与向量最终路径() {
        ProviderDraft draft = ProviderDraft.empty();

        String preview = ProviderEndpointPreview.describe(draft);

        assertTrue(preview.contains("https://api.openai.com/v1/models"));
        assertTrue(preview.contains("https://api.openai.com/v1/chat/completions"));
        assertTrue(preview.contains("https://api.openai.com/v1/embeddings"));
    }

    @Test
    void responses地址明确区分响应与原生压缩路径() {
        ProviderDraft empty = ProviderDraft.empty();
        ProviderDraft draft = new ProviderDraft(
                empty.id(),
                empty.displayName(),
                ProviderAdapter.OPENAI_RESPONSES,
                "https://gateway.example.test/v1/",
                empty.authentication(),
                empty.models(),
                empty.credential(),
                empty.timeoutSeconds(),
                empty.maximumRetries(),
                empty.organization(),
                empty.project(),
                empty.apiVersion(),
                empty.reasoningSummary(),
                empty.lifecycle());

        String preview = ProviderEndpointPreview.describe(draft);

        assertTrue(preview.contains("https://gateway.example.test/v1/responses"));
        assertTrue(preview.contains("https://gateway.example.test/v1/responses/compact"));
        assertTrue(preview.contains("向量：不支持"));
    }

    @Test
    void 已包含资源路径的地址不会生成重复误导预览() {
        ProviderDraft empty = ProviderDraft.empty();
        ProviderDraft draft = new ProviderDraft(
                empty.id(),
                empty.displayName(),
                empty.adapter(),
                "https://gateway.example.test/v1/chat/completions",
                empty.authentication(),
                empty.models(),
                empty.credential(),
                empty.timeoutSeconds(),
                empty.maximumRetries(),
                empty.organization(),
                empty.project(),
                empty.apiVersion(),
                empty.reasoningSummary(),
                empty.lifecycle());

        assertTrue(ProviderEndpointPreview.describe(draft).contains("保存时将拒绝"));
    }

    @Test
    void Google自定义根地址会在最终路径加入所选Api版本() {
        ProviderDraft draft = draft(ProviderAdapter.GOOGLE_GENAI, "https://gateway.example.test/gemini", "v1");

        String preview = ProviderEndpointPreview.describe(draft);

        assertTrue(preview.contains("https://gateway.example.test/gemini/v1/models"));
        assertTrue(preview.contains("https://gateway.example.test/gemini/v1/models/{modelId}:embedContent"));
    }

    @Test
    void Google默认与自定义地址都明确补全Api版本() {
        String officialDefault = ProviderEndpointPreview.describe(draft(ProviderAdapter.GOOGLE_GENAI, "", ""));
        String officialV1 = ProviderEndpointPreview.describe(draft(ProviderAdapter.GOOGLE_GENAI, "", "v1"));
        String customDefault = ProviderEndpointPreview.describe(
                draft(ProviderAdapter.GOOGLE_GENAI, "https://gateway.example.test/gemini", ""));

        assertTrue(officialDefault.contains("generativelanguage.googleapis.com/v1beta/models"));
        assertTrue(officialV1.contains("generativelanguage.googleapis.com/v1/models"));
        assertTrue(customDefault.contains("gateway.example.test/gemini/v1beta/models"));
    }

    @Test
    void Anthropic默认地址只展示其支持的对话路径() {
        String preview = ProviderEndpointPreview.describe(draft(ProviderAdapter.ANTHROPIC, "", ""));

        assertTrue(preview.contains("https://api.anthropic.com/v1/messages"));
        assertTrue(preview.contains("向量：不支持"));
        assertTrue(preview.contains("Responses：不支持"));
    }

    private static ProviderDraft draft(ProviderAdapter adapter, String baseUri, String apiVersion) {
        ProviderDraft empty = ProviderDraft.empty();
        return new ProviderDraft(
                empty.id(),
                empty.displayName(),
                adapter,
                baseUri,
                empty.authentication(),
                empty.models(),
                empty.credential(),
                empty.timeoutSeconds(),
                empty.maximumRetries(),
                empty.organization(),
                empty.project(),
                apiVersion,
                empty.reasoningSummary(),
                empty.lifecycle());
    }
}
