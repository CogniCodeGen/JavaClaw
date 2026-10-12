package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * 每次创建只加载一次气泡 FXML，并把 Controller 生命周期交给返回的门面。
 * 调用受 JavaFX Application Thread 约束；创建失败不会遗留 Controller。
 */
public final class MarkdownBubbleFactory {

    private static final URL TEMPLATE = Objects.requireNonNull(
            MarkdownBubbleFactory.class.getResource("/fxml/chat/markdown-bubble.fxml"),
            "缺少 Markdown 气泡 FXML");

    private final SpringFxmlLoader loader;

    public MarkdownBubbleFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public MarkdownBubble create(double prefWidth) {
        return create(prefWidth, MarkdownBubble.RENDERING_HINT_DELAY_MS);
    }

    MarkdownBubble create(double prefWidth, long hintDelayMillis) {
        ViewHandle<StackPane> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载 Markdown 气泡 FXML 失败", failure);
        }
        try {
            MarkdownBubbleController controller =
                    handle.controller(MarkdownBubbleController.class);
            controller.configure(prefWidth, hintDelayMillis);
            MarkdownBubble bubble = new MarkdownBubble(handle, controller);
            controller.attach(bubble);
            return bubble;
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
