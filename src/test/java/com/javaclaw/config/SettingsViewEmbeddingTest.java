package com.javaclaw.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsViewEmbeddingTest {

    @Test
    void 仅连接相关字段完全一致时复用运行时Gateway() {
        var saved = new SettingsView.EmbeddingTestConfig(
                true, "https://example.test/v1", "embed-1", "secret", 1024);
        var sameWithWhitespace = new SettingsView.EmbeddingTestConfig(
                true, " https://example.test/v1 ", " embed-1 ", " secret ", 1024);

        assertTrue(SettingsView.sameEmbeddingConnection(saved, sameWithWhitespace));
        assertFalse(SettingsView.sameEmbeddingConnection(saved,
                new SettingsView.EmbeddingTestConfig(
                        true, "https://new.test/v1", "embed-1", "secret", 1024)));
        assertFalse(SettingsView.sameEmbeddingConnection(saved,
                new SettingsView.EmbeddingTestConfig(
                        true, "https://example.test/v1", "embed-2", "secret", 1024)));
        assertFalse(SettingsView.sameEmbeddingConnection(saved,
                new SettingsView.EmbeddingTestConfig(
                        true, "https://example.test/v1", "embed-1", "new-secret", 1024)));
        assertFalse(SettingsView.sameEmbeddingConnection(saved,
                new SettingsView.EmbeddingTestConfig(
                        true, "https://example.test/v1", "embed-1", "secret", 768)));
        assertFalse(SettingsView.sameEmbeddingConnection(saved,
                new SettingsView.EmbeddingTestConfig(
                        false, "https://example.test/v1", "embed-1", "secret", 1024)));
    }

    @Test
    void 运行时模型未就绪时即使配置一致也直接探测表单() {
        var form = new SettingsView.EmbeddingTestConfig(
                false, "https://example.test/v1", "embed-1", "secret", 1024);

        assertFalse(SettingsView.shouldProbeRuntime(form, form, false));
        assertTrue(SettingsView.shouldProbeRuntime(form, form, true));
    }

    @Test
    void 表单探测请求使用传入地址和密钥且限制三秒() throws Exception {
        var form = new SettingsView.EmbeddingTestConfig(
                true, "http://127.0.0.1:18080/v1",
                "new-embedding-model", "new-key", 3);

        var request = SettingsView.buildEmbeddingProbeRequest(form);

        assertEquals("http://127.0.0.1:18080/v1/embeddings",
                request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer new-key",
                request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json",
                request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(Duration.ofSeconds(3), request.timeout().orElseThrow());
    }
}
