package com.javaclaw.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChineseOutputGuardTest {

    @Test
    void blocksEnglishNaturalLanguage() {
        assertEquals(ChineseOutputGuard.blockedMessage(),
                ChineseOutputGuard.enforceUserVisibleReply(
                        "This is an English answer that should not be shown to the user."));
    }

    @Test
    void keepsChineseReplyWithCodeAndProductNames() {
        String reply = "已完成 MCP 配置，示例：\n```json\n{\"enabled\": true}\n```";
        assertEquals(reply, ChineseOutputGuard.enforceUserVisibleReply(reply));
    }

    @Test
    void keepsCodeOnlyArtifactButStillRedactsCredentials() {
        String code = "```java\npublic final class Demo {}\n```";
        assertEquals(code, ChineseOutputGuard.enforceUserVisibleReply(code));
        assertNotEquals("密码：real-secret-value",
                ChineseOutputGuard.enforceUserVisibleReply("密码：real-secret-value"));
    }
}
