package com.javaclaw.chat;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单条流式助手消息的类型安全门面。
 *
 * <p>实例受 JavaFX Application Thread 约束。关闭会停止占位动画、释放嵌套 Markdown
 * 气泡并反序销毁本次 FXML 创建的 Controller；关闭幂等，关闭后不得再更新节点。</p>
 */
final class AssistantMessageView implements AutoCloseable {

    private final ViewHandle<HBox> handle;
    private final AssistantMessageController controller;
    private final AtomicBoolean closed = new AtomicBoolean();

    AssistantMessageView(
            ViewHandle<HBox> handle, AssistantMessageController controller) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    HBox root() {
        return handle.root();
    }

    MarkdownBubble reply() {
        return controller.replyBubble();
    }

    VBox toolsHost() {
        return controller.toolResultsBox();
    }

    VBox replyContentHost() {
        return controller.replyContentBox();
    }

    void setRegenerateAction(Runnable action) {
        controller.setRegenerateAction(action);
    }

    void setQuoteAction(Consumer<String> action) {
        controller.setQuoteAction(action);
    }

    void setSaveAction(Consumer<String> action) {
        controller.setSaveAction(action);
    }

    void setDeleteAction(Runnable action) {
        controller.setDeleteAction(action);
    }

    void enableAdoption(Runnable action) {
        controller.enableAdoption(action);
    }

    void revealReply() {
        controller.revealReply();
    }

    void showTools() {
        controller.showTools();
    }

    void hideReplyCard() {
        controller.hideReplyCard();
    }

    void setMetadata(String metadata) {
        controller.setMetadata(metadata);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
