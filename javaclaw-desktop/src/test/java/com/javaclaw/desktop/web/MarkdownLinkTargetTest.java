package com.javaclaw.desktop.web;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLinkTargetTest {
    @Test
    void CommonMark尖括号目标中的空格编码一次且原始目标保持可用于来源校验() {
        var parsed = new SafeMarkdown()
                .render("[文档](<docs/design notes.md#L3>) ![图片](<images/flow chart.png>)", "doc:", Map.of());
        String original = parsed.links().getFirst();
        var target = MarkdownLinkTarget.parse(original);
        assertEquals("docs/design notes.md#L3", original);
        assertEquals("docs/design%20notes.md#L3", target.toString());
        assertEquals("docs/design notes.md", target.getPath());
        assertTrue(MarkdownLinkTarget.relative(original));
        assertTrue(MarkdownLinkTarget.relative(parsed.images().getFirst()));
        assertEquals(target, MarkdownLinkTarget.parse("docs/design%20notes.md#L3"));
        assertEquals("a%2520b.md", MarkdownLinkTarget.parse("a%2520b.md").toString());
    }

    @Test
    void 空格编码不把远程危险协议或锚点变成相对资源() {
        for (String target : List.of(
                "https://example.com/a b",
                "file:///tmp/a b",
                "javascript:alert(1)",
                "//example.com/a b",
                "#section one",
                " ",
                "a%xx")) {
            assertFalse(MarkdownLinkTarget.relative(target), target);
        }
        assertEquals(
                "https://example.com/a%20b",
                MarkdownLinkTarget.parse("https://example.com/a b").toString());
        assertThrows(IllegalArgumentException.class, () -> MarkdownLinkTarget.parse("a%xx"));
    }
}
