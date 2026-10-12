package com.javaclaw.chat.markdown;

import jfx.incubator.scene.control.richtext.model.RichParagraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParagraphRendererTest {

    private static final MarkdownParagraphRenderer.RenderStyleSnapshot STYLE =
            new MarkdownParagraphRenderer.RenderStyleSnapshot(
                    14.5, 1.65, "\"System\", sans-serif", "\"SF Mono\", monospace");

    @Test
    void rendersOnBackgroundThreadWithoutEagerlyCreatingRegions() throws Exception {
        String markdown = """
                # 标题

                ```java
                int answer = 42;
                ```

                | 名称 | 值 |
                |---|---|
                | answer | 42 |
                """;

        MarkdownParagraphRenderer.RenderedMarkdown rendered = CompletableFuture
                .supplyAsync(() -> MarkdownParagraphRenderer.render(markdown, STYLE))
                .get(3, TimeUnit.SECONDS);

        assertNotNull(rendered.model());
        List<RichParagraph> regionParagraphs = new ArrayList<>();
        for (int i = 0; i < rendered.model().size(); i++) {
            RichParagraph paragraph = rendered.model().getParagraph(i);
            if (paragraph.getParagraphRegion() != null) regionParagraphs.add(paragraph);
        }
        assertEquals(2, regionParagraphs.size(), "代码块和表格应保持为延迟 Region supplier");
    }

    @Test
    void preservesTextLinksAndGfmExtensions() {
        String markdown = """
                普通文本、**粗体**、~~删除线~~；访问 https://example.com/path 获取详情。

                [显式链接](https://javaclaw.example/docs)
                """;

        MarkdownParagraphRenderer.RenderedMarkdown rendered =
                MarkdownParagraphRenderer.render(markdown, STYLE);

        String plainText = plainText(rendered);
        assertTrue(plainText.contains("普通文本、粗体、删除线"));
        assertTrue(plainText.contains("https://example.com/path"));
        assertTrue(plainText.contains("显式链接"));
        assertTrue(rendered.links().stream()
                .anyMatch(link -> link.url().equals("https://example.com/path")));
        assertTrue(rendered.links().stream()
                .anyMatch(link -> link.url().equals("https://javaclaw.example/docs")));
    }

    @Test
    void parsesGfmTableWithoutBlankLineAfterParagraph() {
        String markdown = """
                表格如下：
                | A | B |
                |---|---|
                | 1 | 2 |
                """;

        MarkdownParagraphRenderer.RenderedMarkdown rendered =
                MarkdownParagraphRenderer.render(markdown, STYLE);

        assertTrue(rendered.model().size() >= 2);
        assertTrue(hasRegionParagraph(rendered));
    }

    @Test
    void emptyMarkdownStillProducesValidReadOnlyModel() {
        MarkdownParagraphRenderer.RenderedMarkdown rendered =
                MarkdownParagraphRenderer.render("", STYLE);

        assertEquals(1, rendered.model().size());
        assertEquals("", rendered.model().getPlainText(0));
        assertFalse(hasRegionParagraph(rendered));
        assertTrue(rendered.links().isEmpty());
    }

    private static String plainText(MarkdownParagraphRenderer.RenderedMarkdown rendered) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rendered.model().size(); i++) {
            if (i > 0) text.append('\n');
            text.append(rendered.model().getPlainText(i));
        }
        return text.toString();
    }

    private static boolean hasRegionParagraph(
            MarkdownParagraphRenderer.RenderedMarkdown rendered) {
        for (int i = 0; i < rendered.model().size(); i++) {
            if (rendered.model().getParagraph(i).getParagraphRegion() != null) return true;
        }
        return false;
    }
}
