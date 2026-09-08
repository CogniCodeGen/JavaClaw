package com.javaclaw.server.preview;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewMarkdownTest {
    @Test
    void 消息链接索引不受图片影响且代码块不产生链接() {
        String source = "![截图](images/a.png) [代码](src/A.java#L27)\n\n```md\n[伪链接](secret)\n```\n";
        assertEquals(List.of("src/A.java#L27"), PreviewMarkdown.messageLinks(source));
        assertEquals(List.of("images/a.png", "src/A.java#L27"), PreviewMarkdown.links(source));
        assertEquals("[伪链接](secret)\n", PreviewMarkdown.fence(source, 0));
    }

    @Test
    void 相对链接解码空格并保留行号且允许根内父目录() {
        var target = PreviewMarkdown.relative("../src/My%20File.java#L27-L35", "docs");
        assertEquals("src/My File.java", target.path());
        assertEquals(27, target.line().orElseThrow());
        assertEquals("docs/中文.md", PreviewMarkdown.relative("中文.md", "docs").path());
    }

    @Test
    void 自动链接与表格使用和Desktop相同的稳定链接索引() {
        String source = "https://example.test\n\n| 列 |\n|---|\n| [代码](src/A.java) |\n";
        assertEquals(List.of("https://example.test", "src/A.java"), PreviewMarkdown.messageLinks(source));
    }

    @Test
    void 拒绝网络本地绝对路径及编码后的越界() {
        for (String href : List.of(
                "file:///etc/passwd",
                "https://a.test/a",
                "//a.test/a",
                "/etc/passwd",
                "%2e%2e/secret",
                "%5c%5chost/share",
                "C:%5csecret",
                "a?query=x",
                "%00")) {
            assertThrows(SecurityException.class, () -> PreviewMarkdown.relative(href, ""), href);
        }
        assertThrows(IllegalArgumentException.class, () -> PreviewMarkdown.fence("plain", 0));
    }
}
