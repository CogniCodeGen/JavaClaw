package com.javaclaw.chat;

import com.javaclaw.platform.desktop.ExternalLinkOpener;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.theme.FontProfile;
import com.javaclaw.ui.javafx.theme.ThemeProfile;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.fxmisc.richtext.InlineCssTextArea;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：只接收静态节点并把气泡 API 转交给排版状态机。 */
public final class MarkdownBubbleController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private InlineCssTextArea plainView;
    @FXML private Label renderingHint;
    @FXML private MarkdownRenderingOverlay renderingOverlay;

    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;
    private final MarkdownRenderEngine renderer;
    private final ExternalLinkOpener links;
    private final FontProfile fonts;
    private final ThemeProfile themes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private MarkdownBubblePresenter presenter;

    @Autowired
    public MarkdownBubbleController(
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            MarkdownRenderEngine renderer,
            ExternalLinkOpener links,
            FontProfile fonts,
            ThemeProfile themes) {
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.fx = java.util.Objects.requireNonNull(fx, "fx");
        this.renderer = java.util.Objects.requireNonNull(renderer, "renderer");
        this.links = java.util.Objects.requireNonNull(links, "links");
        this.fonts = java.util.Objects.requireNonNull(fonts, "fonts");
        this.themes = java.util.Objects.requireNonNull(themes, "themes");
    }

    @FXML
    private void initialize() {
        presenter = new MarkdownBubblePresenter(
                root, plainView, renderingHint, renderingOverlay,
                tasks, fx, renderer, links, fonts, themes);
    }

    void configure(double prefWidth, long hintDelayMillis) {
        presenter.configure(prefWidth, hintDelayMillis);
    }

    void attach(MarkdownBubble bubble) {
        root.getProperties().put("markdownBubble", bubble);
    }

    StackPane root() {
        return root;
    }

    void appendText(String chunk) {
        presenter.appendText(chunk);
    }

    void replaceText(String text) {
        presenter.replaceText(text);
    }

    void refresh() {
        presenter.refresh();
    }

    void finish() {
        presenter.finish();
    }

    void finishWith(String text) {
        presenter.finishWith(text);
    }

    String text() {
        return presenter.text();
    }

    int length() {
        return presenter.length();
    }

    MarkdownBubble.State state() {
        return presenter.state();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (presenter != null) presenter.dispose();
    }

    boolean isClosed() {
        return closed.get();
    }
}
