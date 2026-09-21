package com.javaclaw.desktop.settings;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPresetTest {
    @Test
    void 预设根地址与现有适配器路径规则一致() {
        List<ExpectedRoutes> expected = List.of(
                new ExpectedRoutes(ProviderPreset.DEEPSEEK, "https://api.deepseek.com/models"),
                new ExpectedRoutes(ProviderPreset.SILICON_FLOW, "https://api.siliconflow.cn/v1/models"),
                new ExpectedRoutes(ProviderPreset.OPENAI, "https://api.openai.com/v1/models"),
                new ExpectedRoutes(ProviderPreset.ANTHROPIC, "https://api.anthropic.com/v1/models"),
                new ExpectedRoutes(ProviderPreset.GEMINI, "https://generativelanguage.googleapis.com/v1beta/models"),
                new ExpectedRoutes(ProviderPreset.OPENROUTER, "https://openrouter.ai/api/v1/models"),
                new ExpectedRoutes(ProviderPreset.OLLAMA, "http://localhost:11434/v1/models"));
        for (ExpectedRoutes entry : expected) {
            ProviderDraft draft = draft(entry.preset(), entry.preset().baseUri());
            draft.toSpec();
            assertTrue(
                    ProviderEndpointPreview.describe(draft).contains(entry.modelsUrl()),
                    entry.preset().label());
            assertEquals(entry.preset(), ProviderPreset.matching(draft));
        }
        assertEquals(ProviderAuthentication.NONE, ProviderPreset.OLLAMA.authentication());
        assertTrue(ProviderPreset.BAILIAN.baseUri().isEmpty(), "百炼预设必须等待明确选择地域");
        assertTrue(ProviderPreset.CUSTOM.baseUri().isEmpty());
    }

    @Test
    void 百炼的显式地域保留兼容目录路径() {
        for (ProviderPreset.BailianRegion region :
                List.of(ProviderPreset.BailianRegion.BEIJING, ProviderPreset.BailianRegion.SINGAPORE)) {
            ProviderDraft draft = draft(ProviderPreset.BAILIAN, region.baseUri());
            assertEquals(ProviderPreset.BAILIAN, ProviderPreset.matching(draft));
            assertEquals(region, ProviderPreset.BailianRegion.matching(region.baseUri()));
            assertTrue(ProviderEndpointPreview.describe(draft).contains(region.baseUri() + "/models"));
        }
        assertTrue(ProviderPreset.BailianRegion.CONSOLE.baseUri().isEmpty());
    }

    @Test
    void 未知网关地址不会被名称猜测为预设服务() {
        ProviderDraft draft = draft(ProviderPreset.ANTHROPIC, "https://private-gateway.example.test/anthropic");
        assertEquals(ProviderAdapter.ANTHROPIC, draft.adapter());
        assertEquals(ProviderPreset.CUSTOM, ProviderPreset.matching(draft));
    }

    private static ProviderDraft draft(ProviderPreset preset, String address) {
        ProviderDraft base = ProviderDraft.empty();
        return new ProviderDraft(
                "",
                preset.label(),
                preset.adapter(),
                address,
                preset.authentication(),
                base.models(),
                base.credential(),
                base.timeoutSeconds(),
                base.maximumRetries(),
                "",
                "",
                preset.apiVersion(),
                base.reasoningSummary(),
                base.lifecycle());
    }

    /**
     * @param preset 待验证预设，不为空
     * @param modelsUrl 按真实适配器规则追加后的模型目录地址，不为空
     */
    private record ExpectedRoutes(ProviderPreset preset, String modelsUrl) {}
}
