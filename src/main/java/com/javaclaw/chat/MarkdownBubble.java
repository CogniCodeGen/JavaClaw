package com.javaclaw.chat;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 FXML Markdown 气泡加载的类型安全门面。
 *
 * <p>实例和其 Node 受 JavaFX Application Thread 约束。流式追加不解析 Markdown；
 * {@link #finish()} 后 CPU 解析由全局托管执行器完成。{@link #dispose()} 会取消当前任务、
 * 丢弃迟到结果并销毁 Controller，且可重复调用。</p>
 */
public final class MarkdownBubble implements AutoCloseable {

    static final int MAX_MARKDOWN_BYTES = 256 * 1024;
    static final long RENDERING_HINT_DELAY_MS = 80;

    enum State {
        STREAMING_PLAIN,
        RENDERING,
        FINAL_MARKDOWN,
        PLAIN_FALLBACK,
        DISPOSED
    }

    private final ViewHandle<StackPane> handle;
    private final MarkdownBubbleController controller;
    private final AtomicBoolean disposed = new AtomicBoolean();

    MarkdownBubble(
            ViewHandle<StackPane> handle, MarkdownBubbleController controller) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    public Region getView() {
        return handle.root();
    }

    public void appendText(String chunk) {
        controller.appendText(chunk);
    }

    public void replaceText(String text) {
        controller.replaceText(text);
    }

    public void refresh() {
        controller.refresh();
    }

    public String getText() {
        return controller.text();
    }

    public int getLength() {
        return controller.length();
    }

    void finish() {
        controller.finish();
    }

    void finishWith(String text) {
        controller.finishWith(text);
    }

    State state() {
        return controller.state();
    }

    public void dispose() {
        close();
    }

    @Override
    public void close() {
        if (disposed.compareAndSet(false, true)) {
            handle.close();
        }
    }

    static boolean utf8LengthExceedsLimit(String text) {
        if (text.length() > MAX_MARKDOWN_BYTES) return true;
        if (text.length() <= MAX_MARKDOWN_BYTES / 3) return false;
        return text.getBytes(StandardCharsets.UTF_8).length > MAX_MARKDOWN_BYTES;
    }
}
