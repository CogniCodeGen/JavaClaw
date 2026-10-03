package com.javaclaw.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataRedactorTest {

    @Test
    void redactsPasswordFromStructuredToolInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "internal");
        input.put("password", "super-secret");

        String redacted = SensitiveDataRedactor.redactToolInput("site_credential_save", input);

        assertTrue(redacted.contains("<redacted>"), redacted);
        assertFalse(redacted.contains("super-secret"), redacted);
    }

    @Test
    void hidesEntireMcpConfigBecauseSecretsMayUseArbitraryKeys() {
        Map<String, Object> input = Map.of(
                "name", "remote",
                "config_json", "{\"url\":\"https://example.com/mcp\","
                        + "\"headers\":{\"Authorization\":\"Bearer abc\"},"
                        + "\"env\":{\"INTERNAL_VALUE\":\"opaque-secret\"}}");

        String redacted = SensitiveDataRedactor.redactToolInput("mcp_server_add", input);

        assertTrue(redacted.contains("<redacted-config-json>"), redacted);
        assertTrue(redacted.contains("remote"), redacted);
        assertFalse(redacted.contains("https://example.com/mcp"), redacted);
        assertFalse(redacted.contains("Bearer abc"), redacted);
        assertFalse(redacted.contains("opaque-secret"), redacted);
    }

    @Test
    void detectsRealCredentialsWithoutFlaggingPlaceholdersOrCodeAccessors() {
        assertTrue(SensitiveDataRedactor.containsLikelyCredential("密码：RealSecret-2026!"));
        assertTrue(SensitiveDataRedactor.containsLikelyCredential(
                "{\"password\":\"RealSecret-2026!\"}"));
        assertTrue(SensitiveDataRedactor.containsLikelyCredential(
                "-----BEGIN PRIVATE KEY-----\nopaque"));
        assertTrue(SensitiveDataRedactor.containsLikelyCredential(
                "我的密码不是 oldSecret123，而是 newSecret456"));
        assertTrue(SensitiveDataRedactor.containsLikelyCredential(
                "Authorization: Bearer opaque-token-value"));
        assertTrue(SensitiveDataRedactor.containsLikelyCredential(
                "https://example.com/mcp?access_token=opaque-token-value"));

        assertFalse(SensitiveDataRedactor.containsLikelyCredential("password = config.getPassword()"));
        assertFalse(SensitiveDataRedactor.containsLikelyCredential("password: <redacted>"));
        assertFalse(SensitiveDataRedactor.containsLikelyCredential("严禁写入密码、令牌或 Cookie"));
    }

    @Test
    void genericLogTextIsFullyHiddenWhenItContainsASecret() {
        String redacted = SensitiveDataRedactor.redactText(
                "请求失败：https://example.com/mcp?token=opaque-token-value");

        assertEquals("<敏感内容已隐藏>", redacted);
        assertFalse(redacted.contains("opaque-token-value"));
        assertTrue(SensitiveDataRedactor.redactTextWithStatus(
                "请求失败：https://example.com/mcp?token=opaque-token-value").redacted());
        assertFalse(SensitiveDataRedactor.redactTextWithStatus("<敏感内容已隐藏>").redacted());
    }

    @Test
    void preservesDesktopSessionErrorsButStillHidesStandaloneSessionSecrets() {
        String windowError = "[desktop_session_open][失败] 无法打开桌面会话: "
                + "Target window is closed, minimized, or not shareable";

        assertFalse(SensitiveDataRedactor.containsLikelyCredential(windowError));
        assertEquals(windowError, SensitiveDataRedactor.redactText(windowError));
        assertFalse(SensitiveDataRedactor.containsLikelyCredential("桌面会话不是可用状态"));

        assertTrue(SensitiveDataRedactor.containsLikelyCredential("会话: opaque-session-12345"));
        assertEquals("<敏感内容已隐藏>", SensitiveDataRedactor.redactText("会话: opaque-session-12345"));
        assertTrue(SensitiveDataRedactor.containsLikelyCredential("\"会话\":\"opaque-session-12345\""));
        assertTrue(SensitiveDataRedactor.containsLikelyCredential("会话不是 oldSecret123"));
    }
}
