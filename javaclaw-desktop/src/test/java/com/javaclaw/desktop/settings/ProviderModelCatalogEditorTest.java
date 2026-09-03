package com.javaclaw.desktop.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderModelCatalogEditorTest {
    @Test
    void 手工添加时空显示名称默认使用真实模型Id() {
        assertEquals("gpt-test", ProviderModelCatalogEditor.effectiveDisplayName("  gpt-test  ", "  "));
    }

    @Test
    void 手工添加时保留用户填写的显示名称() {
        assertEquals("本地开发模型", ProviderModelCatalogEditor.effectiveDisplayName("gpt-test", " 本地开发模型 "));
    }
}
