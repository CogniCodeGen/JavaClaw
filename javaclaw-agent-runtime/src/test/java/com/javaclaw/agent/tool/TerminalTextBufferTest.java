package com.javaclaw.agent.tool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalTextBufferTest {
    @Test
    void splitUtf8AndSecretsNeverLeakAnUnredactedDelta() {
        var buffer = new TerminalTextBuffer(new SecretRedactor(Map.of("API_TOKEN", "split-secret-value")));
        byte[] text = "中文 split-secret-value password=hidden\n".getBytes(StandardCharsets.UTF_8);
        var output = new StringBuilder();
        for (byte value : text) {
            output.append(buffer.accept(new byte[] {value}));
        }
        assertEquals("中文 [REDACTED] password=[REDACTED]\n", output.toString());
        assertFalse(output.toString().contains("\uFFFD"));
    }

    @Test
    void overlongUnterminatedLineIsDiscardedAsOneUnit() {
        var buffer = new TerminalTextBuffer(new SecretRedactor(Map.of()));
        byte[] longLine = new byte[100_000];
        Arrays.fill(longLine, (byte) 's');
        assertEquals("[terminal line truncated]\n", buffer.accept(longLine));
        assertEquals("", buffer.finish());
        assertEquals("next\n", buffer.accept("\nnext\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(buffer.truncated());
    }

    @Test
    void ordinaryPromptStreamsWithoutNewlineButSecretPromptWaitsForRedaction() {
        var buffer = new TerminalTextBuffer(new SecretRedactor(Map.of()));
        assertEquals("workspace $ ", buffer.accept("workspace $ ".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", buffer.accept("password: ".getBytes(StandardCharsets.UTF_8)));
        assertEquals("password=[REDACTED]\n", buffer.accept("value\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", buffer.accept("最后一行".getBytes(StandardCharsets.UTF_8)));
        assertEquals("最后一行", buffer.finish());
    }
}
