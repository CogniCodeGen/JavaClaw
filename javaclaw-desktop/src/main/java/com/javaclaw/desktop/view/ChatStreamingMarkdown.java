package com.javaclaw.desktop.view;

import java.util.Map;
import java.util.regex.Pattern;

import com.javaclaw.desktop.view.ChatSurface.TemporaryMessage;
import com.javaclaw.desktop.web.SafeMarkdown;

/** 单个投影 worker 的流式 Markdown 缓存；作用域切换时清空，不将未解析尾部作为 HTML。 */
final class ChatStreamingMarkdown {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private String activeId = "";
    private String activeHtml = "";
    private String activeText = "";
    private long parsedAt;

    String render(TemporaryMessage message) {
        if (message.incomplete()
                || message.textOffsetUtf16() > 0
                || message.text().length() > 32_768) {
            return "";
        }
        long now = System.nanoTime();
        if (!activeId.equals(message.id()) || now - parsedAt >= 200_000_000L || joinsParsedGrapheme(message.text())) {
            activeId = message.id();
            activeHtml = new SafeMarkdown()
                    .render(message.text(), "active:", Map.of())
                    .html();
            activeText = message.text();
            parsedAt = now;
        }
        if (!message.text().startsWith(activeText)) {
            return "";
        }
        // Markdown 至多每 200ms 解析一次；期间的新字仍以转义原文立即可见，不能显示旧正文等待下一块。
        String suffix = message.text().substring(activeText.length());
        return suffix.isEmpty()
                ? activeHtml
                : activeHtml + "<span class=\"plain\">"
                        + suffix.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</span>";
    }

    /** 新片段若扩展已解析末簇，立即重新解析，避免把代理对、肤色或 ZWJ 组合拆在两个 DOM 区域。 */
    private boolean joinsParsedGrapheme(String text) {
        int boundary = activeText.length();
        if (boundary == 0 || text.length() <= boundary || !text.startsWith(activeText)) {
            return false;
        }
        int next = text.codePointAt(boundary);
        int previous = text.codePointBefore(boundary);
        int category = Character.getType(next);
        boolean continuation = Character.isSurrogate(text.charAt(boundary))
                || next == 0x200D
                || previous == 0x200D
                || category == Character.NON_SPACING_MARK
                || category == Character.COMBINING_SPACING_MARK
                || category == Character.ENCLOSING_MARK;
        if (!continuation) {
            return false;
        }
        var matcher = GRAPHEME.matcher(text);
        while (matcher.find()) {
            if (matcher.end() >= boundary) {
                return matcher.end() > boundary;
            }
        }
        return false;
    }

    String parsedHtml() {
        return activeHtml;
    }

    int parsedLength() {
        return activeText.length();
    }

    void clear() {
        activeId = "";
        activeHtml = "";
        activeText = "";
        parsedAt = 0;
    }
}
