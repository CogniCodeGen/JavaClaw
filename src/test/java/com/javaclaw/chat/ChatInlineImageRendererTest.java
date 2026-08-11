package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatInlineImageRendererTest {

    @Test
    void extractsSupportedAbsoluteImagePathsInEncounterOrder() {
        assertEquals(List.of("/workspace/result.png", "/workspace/结果.WEBP"),
                ChatInlineImageRenderer.extractPaths(
                        "查看 /workspace/result.png，以及 /workspace/结果.WEBP。"));
    }

    @Test
    void ignoresRelativePathsAndUnsupportedExtensions() {
        assertEquals(List.of(), ChatInlineImageRenderer.extractPaths(
                "relative/image.png /workspace/report.pdf /workspace/no-extension"));
    }
}
