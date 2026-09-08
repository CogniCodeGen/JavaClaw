package com.javaclaw.desktop.web;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeMarkdownTest {
    @Test
    void 原始HTML远程资源及危险链接只产生受限文本和动作() {
        var result = new SafeMarkdown()
                .render(
                        "<script>window.pwned=true</script>\n\n"
                                + "[危险](javascript:alert) ![远程](https://example.invalid/pixel) [本地](src/Main.java#L8)",
                        "item:link:",
                        Map.of());
        assertFalse(result.html().contains("<script>"));
        assertFalse(result.html().contains("href="));
        assertFalse(result.html().contains("src="));
        assertTrue(result.html().contains("&lt;script&gt;"));
        assertTrue(result.html().contains("data-link=\"item:link:1\""));
        assertEquals(2, result.links().size());
        assertEquals(1, result.images().size());
    }

    @Test
    void 链接编号不受图片影响且围栏保持源码() {
        var result = new SafeMarkdown()
                .render(
                        "![a](a.png) [源](src/A.java)\n\n```java\nif (a < b) {}\n```",
                        "m:",
                        Map.of("a.png", "data:image/png;base64,YQ=="));
        assertEquals(java.util.List.of("src/A.java"), result.links());
        assertEquals(java.util.List.of("java"), result.fences());
        assertTrue(result.html().contains("data-link=\"m:0\""));
        assertTrue(result.html().contains("a &lt; b"));
        assertTrue(result.html().contains("data:image/png;base64,YQ=="));
    }
}
