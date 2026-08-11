package com.javaclaw.chat;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.StackPane;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一条静态消息的 FXML 视图和 Controller 生命周期门面。 */
final class ChatMessageRowView implements AutoCloseable {

    private final ViewHandle<StackPane> handle;
    private final ChatMessageRowController controller;
    private final AtomicBoolean closed = new AtomicBoolean();

    ChatMessageRowView(
            ViewHandle<StackPane> handle, ChatMessageRowController controller) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    StackPane root() {
        return handle.root();
    }

    MarkdownBubble markdownBubble() {
        return controller.markdownBubble();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
