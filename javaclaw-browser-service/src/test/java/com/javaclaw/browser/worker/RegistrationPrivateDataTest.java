package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationPrivateDataTest {
    private static final URI ORIGIN = URI.create("https://docs.example.com");

    @Test
    void 候选上限与字段字节限制不会无界缓存且替换立即退休旧身份() {
        try (RegistrationCredentials credentials = new RegistrationCredentials()) {
            assertFalse(credentials.capture(ORIGIN, "long".repeat(100), "user", "secret"));
            assertFalse(credentials.capture(ORIGIN, "1", "", "secret"));
            assertFalse(credentials.capture(ORIGIN, "1", "user\0secret", "secret"));
            assertFalse(credentials.capture(ORIGIN, "1", "user", "x".repeat(65_536)));
            assertTrue(credentials.capture(ORIGIN, "1", "user", "secret"));
            String original = credentials.descriptions().getFirst().id();
            assertFalse(credentials.capture(ORIGIN, "1", "user", "secret"));
            assertTrue(credentials.capture(ORIGIN, "1", "user", "replacement"));
            assertThrows(IllegalStateException.class, () -> credentials.selected(Optional.of(original), ORIGIN));
            for (int index = 2; index <= 8; index++) {
                assertTrue(credentials.capture(ORIGIN, Integer.toString(index), "user", "password" + index));
            }
            assertFalse(credentials.capture(ORIGIN, "9", "user", "secret"));
            assertEquals(8, credentials.descriptions().size());
            assertEquals(0, credentials.selected(Optional.empty(), ORIGIN).length);
            String id = credentials.descriptions().getFirst().id();
            assertThrows(
                    IllegalStateException.class,
                    () -> credentials.selected(Optional.of(id), URI.create("https://other.example.com")));
            byte[] detached = credentials.selected(Optional.of(id), ORIGIN);
            detached[0] = 0;
            assertEquals(
                    "user\0replacement",
                    new String(credentials.selected(Optional.of(id), ORIGIN), StandardCharsets.UTF_8));
            assertEquals("REDACTED REDACTED", credentials.redact("user replacement"));
            credentials.close();
            assertTrue(credentials.descriptions().isEmpty());
            assertThrows(IllegalStateException.class, () -> credentials.selected(Optional.of(id), ORIGIN));
        }
    }

    @Test
    void 状态过滤只保留最终域可用Cookie和精确Origin并拒绝损坏或超限数据() {
        String state = """
                {"cookies":[{}, {"domain":""}, {"domain":"docs.example.com","name":"host"},
                {"domain":".docs.example.com","name":"domain"}, {"domain":".example.com","name":"parent"},
                {"domain":"ample.com","name":"not-suffix"}, {"domain":"example.com","name":"parent-host"},
                {"domain":"evil-docs.example.com","name":"other"}],
                "origins":[{}, {"origin":"https://docs.example.com","localStorage":[]},
                {"origin":"https://docs.example.com:8443","localStorage":[]}]}
                """;
        String filtered = new String(RegistrationStorage.filter(state, ORIGIN), StandardCharsets.UTF_8);
        assertTrue(filtered.contains("\"host\""));
        assertTrue(filtered.contains("\"domain\""));
        assertTrue(filtered.contains("\"parent\""));
        assertFalse(filtered.contains("not-suffix"));
        assertFalse(filtered.contains("parent-host"));
        assertFalse(filtered.contains("other"));
        assertFalse(filtered.contains("8443"));
        assertThrows(IllegalArgumentException.class, () -> RegistrationStorage.filter("{}", ORIGIN));
        assertThrows(
                IllegalArgumentException.class,
                () -> RegistrationStorage.filter(" ".repeat(BrowserWorkerProtocol.MAXIMUM_STATE_BYTES + 1), ORIGIN));
        String unicode = "{\"cookies\":[{\"domain\":\"docs.example.com\",\"value\":\""
                + "文".repeat(BrowserWorkerProtocol.MAXIMUM_STATE_BYTES / 2) + "\"}],\"origins\":[]}";
        assertThrows(IllegalArgumentException.class, () -> RegistrationStorage.filter(unicode, ORIGIN));
    }
}
