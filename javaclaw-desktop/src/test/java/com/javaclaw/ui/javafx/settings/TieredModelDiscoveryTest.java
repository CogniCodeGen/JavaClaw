package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TieredModelDiscoveryTest {

    @Test
    void 分级密钥与高性能回退只发送到所属提供商和来源() {
        TieredModelDiscovery discovery = new TieredModelDiscovery();
        discovery.setHighModel(new ModelSettings("openai", "https://api.example/v1",
                "high-model", "high-key", false, 4096, "HTTP_1_1",
                10, 120, 30, 30, 15, 15, 5, 0.92, 4.0, 2));

        discovery.capture(false, "openai", "");
        assertFalse(discovery.requiresNewKey(false, "openai", ""));
        assertTrue(discovery.requiresNewKey(false, "openai", "https://other.example"));
        assertTrue(discovery.requiresNewKey(false, "anthropic", ""));
        assertEquals("normal-key", discovery.request(false, "openai", "", "normal-key").apiKey());
        assertEquals("https://api.example/v1",
                discovery.request(false, "openai", "", "normal-key").baseUrl());
        assertEquals("", discovery.request(false, "anthropic",
                "https://api.anthropic.com", "normal-key").apiKey());
        assertEquals("", discovery.ownKeyFor(false, "anthropic",
                "https://api.anthropic.com", "normal-key"));
        assertEquals("high-key", discovery.request(true, "openai", "", "").apiKey());
        assertEquals("", discovery.request(true, "openai", "https://other.example", "").apiKey());
        assertEquals("", discovery.request(true, "dashscope",
                "https://api.example/v1", "").apiKey());

        discovery.capture(false, "anthropic", "https://api.anthropic.com");
        assertEquals("anthropic-key", discovery.request(false, "anthropic",
                "https://api.anthropic.com/v1", "anthropic-key").apiKey());
    }
}
