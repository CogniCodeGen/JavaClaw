package com.javaclaw.chat;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/** 单个 Markdown 气泡的原始内容与渲染状态，不持有 Node 或服务。 */
final class MarkdownBubbleViewModel {

    private final StringBuilder content = new StringBuilder();
    private final ObjectProperty<MarkdownBubble.State> state =
            new SimpleObjectProperty<>(MarkdownBubble.State.STREAMING_PLAIN);

    void append(String chunk) {
        content.append(chunk);
    }

    void replace(String text) {
        content.setLength(0);
        if (text != null) content.append(text);
    }

    void clear() {
        content.setLength(0);
    }

    String text() {
        return content.toString();
    }

    int length() {
        return content.length();
    }

    MarkdownBubble.State state() {
        return state.get();
    }

    void setState(MarkdownBubble.State value) {
        state.set(value);
    }
}
