package com.javaclaw.chat;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderStyleSnapshot;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderedMarkdown;

/** CPU 阶段的 Markdown 解析端口；实现不得创建或访问 JavaFX Node。 */
@FunctionalInterface
public interface MarkdownRenderEngine {

    RenderedMarkdown render(String markdown, RenderStyleSnapshot style);
}
