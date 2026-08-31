package com.javaclaw.agent.tool;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 跨 PTY 帧拼接 UTF-8 与凭据；未完成的长行不提前泄露前缀，也不会形成无界缓冲。 */
final class TerminalTextBuffer {
    private static final int MAX_LINE = 32_768;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final SecretRedactor redactor;
    private boolean dropping;
    private boolean truncated;

    TerminalTextBuffer(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    String accept(byte[] bytes) {
        var emitted = new StringBuilder();
        for (byte value : bytes) {
            if (dropping) {
                if (value == '\n') {
                    dropping = false;
                }
                continue;
            }
            if (line.size() >= MAX_LINE) {
                line.reset();
                dropping = value != '\n';
                truncated = true;
                emitted.append("[terminal line truncated]\n");
                continue;
            }
            line.write(value);
            if (value == '\n') {
                emitted.append(flush());
            }
        }
        if (line.size() > 0) {
            String candidate = line.toString(StandardCharsets.UTF_8);
            // 允许无换行的普通 Shell 提示符；秘密提示/赋值和已知凭据前缀必须继续等待完整行。
            if (candidate.matches("(?s).*([>$#%:] |>>> )$")
                    && !candidate.matches("(?is).*(password|secret|token|api.?key).*")
                    && !redactor.endsWithSecretPrefix(candidate)) {
                emitted.append(flush());
            }
        }
        return emitted.toString();
    }

    String finish() {
        return dropping ? "" : flush();
    }

    boolean truncated() {
        return truncated;
    }

    private String flush() {
        String value = line.toString(StandardCharsets.UTF_8);
        line.reset();
        return redactor.text(value);
    }
}
