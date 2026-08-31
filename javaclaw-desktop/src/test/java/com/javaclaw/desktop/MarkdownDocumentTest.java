package com.javaclaw.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownDocumentTest {
    @Test
    void rendersExistingMarkdownSemanticsWithoutExecutingHtmlOrFetchingImages() {
        var blocks = MarkdownDocument.parse("""
                # 结果

                **已验证**，*有依据*，~~旧说法~~，`inline`。

                3. 第一项
                4. 第二项

                | 能力 | 状态 |
                | --- | --- |
                | 编译 | 通过 |

                ```java
                System.out.println("不会执行");
                ```

                ![外部](https://example.test/secret.png)

                <script>fetch('https://example.test')</script>
                """);
        assertEquals("heading", blocks.getFirst().kind());
        assertTrue(blocks.stream()
                .anyMatch(block ->
                        block.kind().equals("table") && block.children().size() == 2));
        assertTrue(blocks.stream()
                .anyMatch(
                        block -> block.kind().equals("code") && block.language().equals("java")));
        assertTrue(blocks.stream()
                .anyMatch(block -> block.kind().equals("list") && block.marker().equals("3.")));
        assertTrue(blocks.stream()
                .flatMap(block -> block.spans().stream())
                .anyMatch(span -> span.text().contains("不自动加载")));
        assertTrue(blocks.stream()
                .flatMap(block -> block.spans().stream())
                .anyMatch(span -> span.text().contains("<script>")));
        assertTrue(blocks.stream().flatMap(block -> block.spans().stream()).allMatch(span -> span.link() == null));
    }

    @Test
    void dangerousLinksAndOverComplexDocumentsStayPlainAndBounded() {
        for (String value : java.util.List.of(
                "file:///etc/passwd",
                "javascript:alert(1)",
                "data:text/html,x",
                "https://user:secret@example.test",
                "http://example.test",
                "https://example.test/\n")) {
            assertNull(MarkdownDocument.safeLink(value));
        }
        assertNotNull(MarkdownDocument.safeLink("https://example.test/reference#part"));
        var deep = MarkdownDocument.parse("> ".repeat(100) + "嵌套");
        assertEquals(1, deep.size());
        assertTrue(deep.getFirst().spans().getFirst().text().contains("过于复杂"));
        var longDocument = MarkdownDocument.parse("a".repeat(MarkdownDocument.MAXIMUM_CHARACTERS + 1));
        assertTrue(longDocument.getFirst().spans().getFirst().text().length() < 5_000);
        var spans = MarkdownDocument.parse("[伪装](javascript:alert) [真实](https://example.test)")
                .getFirst()
                .spans();
        assertFalse(spans.isEmpty());
        assertNull(spans.getFirst().link());
        assertTrue(spans.stream().anyMatch(span -> span.link() != null));
    }
}
