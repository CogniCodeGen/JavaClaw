package com.javaclaw.ui.javafx.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDiscoveryCredentialScopeTest {

    @Test
    void 密钥仅随所属提供商和来源发送() {
        ModelDiscoveryCredentialScope scope = new ModelDiscoveryCredentialScope();
        scope.capture("openai", "https://API.OpenAI.com/v1");

        assertEquals("saved-key", scope.keyFor("OPENAI",
                "https://api.openai.com:443/v1/models", "saved-key"));
        assertEquals("", scope.keyFor("anthropic",
                "https://api.openai.com/v1", "saved-key"));
        assertEquals("", scope.keyFor("openai",
                "https://other.example/v1", "saved-key"));
        assertEquals("", scope.keyFor("openai",
                "http://api.openai.com/v1", "saved-key"));
        assertFalse(scope.requiresNewKey("openai", "https://api.openai.com/other"));
        assertTrue(scope.requiresNewKey("openai", "https://other.example/v1"));
        assertTrue(scope.requiresNewKey("anthropic", "https://api.openai.com/v1"));
        assertFalse(scope.requiresNewKey("openai", ""),
                "编辑中的空地址在模型设置中由保存校验拒绝");

        scope.capture("openai", "https://other.example/v1");
        assertEquals("new-key", scope.keyFor("openai",
                "https://other.example/models", "new-key"));
    }

    @Test
    void 高性能模型密钥只在同提供商同来源继承() {
        assertTrue(ModelDiscoveryCredentialScope.sameEndpoint("openai",
                "https://api.example/v1", "OPENAI", "https://api.example:443/models"));
        assertFalse(ModelDiscoveryCredentialScope.sameEndpoint("openai",
                "https://api.example/v1", "dashscope", "https://api.example/v1"));
        assertFalse(ModelDiscoveryCredentialScope.sameEndpoint("openai",
                "https://other.example/v1", "openai", "https://api.example/v1"));
        assertFalse(ModelDiscoveryCredentialScope.sameEndpoint("openai",
                "", "openai", ""));
    }
}
