package com.javaclaw.chat;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次可折叠 Markdown 结果 FXML 加载及其嵌套 Controller 生命周期。 */
final class ExpandableMarkdownBlockView implements AutoCloseable {

    private final ViewHandle<VBox> handle;
    private final ExpandableMarkdownBlockController controller;
    private final AtomicBoolean closed = new AtomicBoolean();

    ExpandableMarkdownBlockView(
            ViewHandle<VBox> handle,
            ExpandableMarkdownBlockController controller) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    VBox root() {
        return handle.root();
    }

    VBox contentHost() {
        return controller.contentBox();
    }

    MarkdownBubble bubble() {
        return controller.bubble();
    }

    void revealContent() {
        controller.revealContent();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
