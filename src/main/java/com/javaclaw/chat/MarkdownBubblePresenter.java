package com.javaclaw.chat;

import com.javaclaw.app.UiMotion;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderStyleSnapshot;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderedMarkdown;
import com.javaclaw.platform.desktop.ExternalLinkOpener;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.theme.FontProfile;
import com.javaclaw.ui.javafx.theme.ThemeProfile;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import jfx.incubator.scene.control.richtext.RichTextArea;
import org.fxmisc.richtext.InlineCssTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/** Markdown 气泡的异步排版与视觉状态机；固定节点全部由 FXML 提供。 */
final class MarkdownBubblePresenter {

    private static final Logger log = LoggerFactory.getLogger(MarkdownBubblePresenter.class);

    private final StackPane root;
    private final InlineCssTextArea plainView;
    private final Label renderingHint;
    private final MarkdownRenderingOverlay renderingOverlay;
    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;
    private final MarkdownRenderEngine renderer;
    private final ExternalLinkOpener links;
    private final FontProfile fonts;
    private final ThemeProfile themes;
    private final MarkdownBubbleViewModel viewModel = new MarkdownBubbleViewModel();
    private final PauseTransition renderingHintDelay = new PauseTransition();
    private final ChangeListener<Number> fontRevisionListener =
            (observable, previous, current) -> refreshForAppearanceChange();
    private final ChangeListener<Number> themeRevisionListener =
            (observable, previous, current) -> refreshForAppearanceChange();

    private double prefWidth = 520;
    private long hintDelayMillis = MarkdownBubble.RENDERING_HINT_DELAY_MS;
    private FadeTransition renderingHintFade;
    private TaskHandle<RenderedMarkdown> pendingRender;
    private Animation contentTransition;
    private Node transitionOutgoing;
    private Node transitionIncoming;
    private String transitionSource;
    private Node currentContent;
    private long renderGeneration;
    private String submittedSource;
    private String finalSource;
    private volatile boolean disposed;

    MarkdownBubblePresenter(
            StackPane root,
            InlineCssTextArea plainView,
            Label renderingHint,
            MarkdownRenderingOverlay renderingOverlay,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            MarkdownRenderEngine renderer,
            ExternalLinkOpener links,
            FontProfile fonts,
            ThemeProfile themes) {
        this.root = Objects.requireNonNull(root, "root");
        this.plainView = Objects.requireNonNull(plainView, "plainView");
        this.renderingHint = Objects.requireNonNull(renderingHint, "renderingHint");
        this.renderingOverlay = Objects.requireNonNull(renderingOverlay, "renderingOverlay");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.links = Objects.requireNonNull(links, "links");
        this.fonts = Objects.requireNonNull(fonts, "fonts");
        this.themes = Objects.requireNonNull(themes, "themes");
        configurePlainView();
        currentContent = plainView;
        fonts.revisionProperty().addListener(fontRevisionListener);
        themes.revisionProperty().addListener(themeRevisionListener);
        setState(MarkdownBubble.State.STREAMING_PLAIN);
    }

    void configure(double width, long hintDelay) {
        prefWidth = width;
        hintDelayMillis = Math.max(0, hintDelay);
        root.setPrefWidth(width);
        plainView.setPrefWidth(width);
    }

    void appendText(String chunk) {
        if (chunk == null || chunk.isEmpty() || disposed) return;
        if (state() != MarkdownBubble.State.STREAMING_PLAIN) switchBackToStreamingPlain();
        viewModel.append(chunk);
        plainView.appendText(chunk);
        updatePlainFallbackHeight();
    }

    void replaceText(String text) {
        if (disposed) return;
        invalidatePendingWork();
        viewModel.replace(text);
        plainView.replaceText(viewModel.text());
        updatePlainFallbackHeight();
        showPlainImmediately();
        setState(MarkdownBubble.State.STREAMING_PLAIN);
        finish();
    }

    void refresh() {
        if (disposed) return;
        if (state() == MarkdownBubble.State.STREAMING_PLAIN) finish();
        else submitRender(true);
    }

    void finish() {
        if (!disposed) submitRender(false);
    }

    void finishWith(String text) {
        replaceText(text);
    }

    String text() {
        return viewModel.text();
    }

    int length() {
        return viewModel.length();
    }

    MarkdownBubble.State state() {
        return viewModel.state();
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        renderGeneration++;
        cancelPendingRender();
        renderingHintDelay.stop();
        stopRenderingHintFade();
        discardVisualTransition();
        fonts.revisionProperty().removeListener(fontRevisionListener);
        themes.revisionProperty().removeListener(themeRevisionListener);
        clearDiagnostics();
        disposeRenderedContent(currentContent);
        root.getChildren().clear();
        plainView.replaceText("");
        viewModel.clear();
        submittedSource = null;
        finalSource = null;
        setState(MarkdownBubble.State.DISPOSED);
    }

    private void configurePlainView() {
        plainView.setEditable(false);
        plainView.setWrapText(true);
        plainView.setContextMenu(MarkdownRenderedViewFactory.contextMenu(
                plainView::copy, plainView::selectAll, viewModel::text));
        plainView.totalHeightEstimateProperty().addListener((observable, previous, current) -> {
            if (current != null && current.doubleValue() > 0) {
                setPlainHeight(current.doubleValue() + 4);
            }
        });
        updatePlainFallbackHeight();
    }

    private void updatePlainFallbackHeight() {
        int lines = Math.max(1, plainView.getParagraphs().size());
        double lineHeight = Math.max(20, fonts.chatFontPx() * 1.4);
        setPlainHeight(Math.max(28, lines * lineHeight + 8));
    }

    private void setPlainHeight(double height) {
        double bounded = Math.max(28, height);
        plainView.setPrefHeight(bounded);
        plainView.setMinHeight(bounded);
        plainView.setMaxHeight(bounded);
    }

    private void submitRender(boolean force) {
        String source = viewModel.text();
        if (!force && alreadySubmitted(source)) return;
        settleVisualTransition();
        cancelPendingRender();
        stopRenderingHint(false);

        if (MarkdownBubble.utf8LengthExceedsLimit(source)) {
            renderGeneration++;
            submittedSource = source;
            showPlainImmediately();
            root.getProperties().put("markdownRenderFallback", "message-too-large");
            setState(MarkdownBubble.State.PLAIN_FALLBACK);
            log.info("Markdown 消息超过 {} KiB，保留普通文本（{} 字符）",
                    MarkdownBubble.MAX_MARKDOWN_BYTES / 1024, source.length());
            return;
        }

        clearRenderDiagnostics();
        long generation = ++renderGeneration;
        submittedSource = source;
        RenderStyleSnapshot style = RenderStyleSnapshot.capture(fonts);
        setState(MarkdownBubble.State.RENDERING);
        scheduleRenderingHint(generation);
        try {
            TaskHandle<RenderedMarkdown> handle = tasks.submit(
                    TaskSpec.cpu("markdown-render").withTimeout(java.time.Duration.ofSeconds(30)),
                    context -> renderer.render(source, style));
            pendingRender = handle;
            handle.completion().whenComplete((rendered, failure) -> fx.dispatch(() -> {
                if (failure == null) acceptRendered(handle, generation, source, rendered);
                else if (!(failure instanceof CancellationException)) {
                    acceptRenderFailure(handle, generation, failure);
                }
            }));
        } catch (RejectedExecutionException failure) {
            acceptRenderFailure(null, generation, failure);
        }
    }

    private boolean alreadySubmitted(String source) {
        return state() == MarkdownBubble.State.RENDERING && source.equals(submittedSource)
                || state() == MarkdownBubble.State.FINAL_MARKDOWN && source.equals(finalSource)
                || state() == MarkdownBubble.State.PLAIN_FALLBACK && source.equals(submittedSource);
    }

    private void acceptRendered(
            TaskHandle<RenderedMarkdown> handle,
            long generation,
            String source,
            RenderedMarkdown rendered) {
        if (!isCurrent(generation)) return;
        if (pendingRender == handle) pendingRender = null;
        stopRenderingHint(false);
        RichTextArea nextView;
        try {
            nextView = MarkdownRenderedViewFactory.create(
                    rendered, prefWidth, viewModel::text, links);
        } catch (Throwable failure) {
            acceptRenderFailure(handle, generation, failure);
            return;
        }

        Node outgoing = currentContent;
        transitionOutgoing = outgoing;
        transitionIncoming = nextView;
        transitionSource = source;
        nextView.setOpacity(0);
        int overlayIndex = root.getChildren().indexOf(renderingOverlay);
        root.getChildren().add(Math.max(0, overlayIndex), nextView);
        fx.dispatchLater(() -> startContentTransition(generation, outgoing, nextView));
    }

    private void acceptRenderFailure(
            TaskHandle<RenderedMarkdown> handle, long generation, Throwable failure) {
        if (!isCurrent(generation)) return;
        if (handle == null || pendingRender == handle) pendingRender = null;
        root.getProperties().put("markdownRenderFailure", failure.toString());
        stopRenderingHint(true);
        setState(currentContent == plainView
                ? MarkdownBubble.State.PLAIN_FALLBACK
                : MarkdownBubble.State.FINAL_MARKDOWN);
        log.error("Markdown 终态渲染失败，保留当前文本视图", failure);
    }

    private void startContentTransition(
            long generation, Node expectedOutgoing, Node expectedIncoming) {
        if (!matchesTransition(generation, expectedOutgoing, expectedIncoming)) return;
        if (!shouldAnimate()) {
            completeVisualTransition(generation, expectedOutgoing, expectedIncoming);
            return;
        }
        try {
            root.getProperties().put("markdownTransitionStartedAtNanos", System.nanoTime());
            contentTransition = UiMotion.crossFade(expectedOutgoing, expectedIncoming,
                    () -> completeVisualTransition(
                            generation, expectedOutgoing, expectedIncoming));
        } catch (Throwable failure) {
            log.warn("Markdown 视图切换动画失败，直接完成替换", failure);
            root.getProperties().put("markdownAnimationFailure", failure.toString());
            completeVisualTransition(generation, expectedOutgoing, expectedIncoming);
        }
    }

    private void completeVisualTransition(
            long generation, Node expectedOutgoing, Node expectedIncoming) {
        if (!matchesTransition(generation, expectedOutgoing, expectedIncoming)) return;
        root.getChildren().remove(expectedOutgoing);
        disposeRenderedContent(expectedOutgoing);
        expectedOutgoing.setOpacity(1);
        expectedIncoming.setOpacity(1);
        currentContent = expectedIncoming;
        finalSource = transitionSource;
        root.getProperties().put("markdownRenderedSource", finalSource);
        if (root.getProperties().containsKey("markdownTransitionStartedAtNanos")) {
            root.getProperties().put("markdownTransitionFinishedAtNanos", System.nanoTime());
        }
        clearTransitionReferences();
        setState(MarkdownBubble.State.FINAL_MARKDOWN);
    }

    private boolean matchesTransition(long generation, Node outgoing, Node incoming) {
        return isCurrent(generation)
                && transitionOutgoing == outgoing
                && transitionIncoming == incoming;
    }

    private void scheduleRenderingHint(long generation) {
        stopRenderingHintFade();
        renderingHintDelay.stop();
        renderingHintDelay.setDuration(Duration.millis(hintDelayMillis));
        renderingHintDelay.setOnFinished(event -> {
            if (!isCurrent(generation) || state() != MarkdownBubble.State.RENDERING) return;
            renderingHint.setOpacity(1);
            renderingHint.setVisible(true);
            renderingOverlay.requestLayout();
        });
        renderingHintDelay.playFromStart();
    }

    private void stopRenderingHint(boolean animated) {
        renderingHintDelay.stop();
        stopRenderingHintFade();
        if (!renderingHint.isVisible()) return;
        if (!animated) {
            renderingHint.setVisible(false);
            renderingHint.setOpacity(1);
            return;
        }
        renderingHintFade = new FadeTransition(Duration.millis(100), renderingHint);
        renderingHintFade.setFromValue(renderingHint.getOpacity());
        renderingHintFade.setToValue(0);
        renderingHintFade.setOnFinished(event -> {
            renderingHint.setVisible(false);
            renderingHint.setOpacity(1);
            renderingHintFade = null;
        });
        renderingHintFade.play();
    }

    private void stopRenderingHintFade() {
        if (renderingHintFade == null) return;
        renderingHintFade.stop();
        renderingHintFade = null;
        renderingHint.setOpacity(1);
    }

    private void refreshForAppearanceChange() {
        if (!disposed && state() != MarkdownBubble.State.STREAMING_PLAIN) submitRender(true);
    }

    private void switchBackToStreamingPlain() {
        invalidatePendingWork();
        plainView.replaceText(viewModel.text());
        updatePlainFallbackHeight();
        showPlainImmediately();
        setState(MarkdownBubble.State.STREAMING_PLAIN);
    }

    private void invalidatePendingWork() {
        renderGeneration++;
        cancelPendingRender();
        renderingHintDelay.stop();
        stopRenderingHint(false);
        discardVisualTransition();
    }

    private void cancelPendingRender() {
        TaskHandle<RenderedMarkdown> handle = pendingRender;
        pendingRender = null;
        if (handle != null) handle.cancel();
    }

    private void showPlainImmediately() {
        if (currentContent == plainView && transitionIncoming == null) return;
        discardVisualTransition();
        if (currentContent != null && currentContent != plainView) {
            root.getChildren().remove(currentContent);
            disposeRenderedContent(currentContent);
        }
        if (!root.getChildren().contains(plainView)) {
            int overlayIndex = root.getChildren().indexOf(renderingOverlay);
            root.getChildren().add(Math.max(0, overlayIndex), plainView);
        }
        plainView.setOpacity(1);
        currentContent = plainView;
    }

    private void settleVisualTransition() {
        if (transitionIncoming == null) return;
        if (contentTransition != null) contentTransition.stop();
        if (transitionOutgoing != null) {
            root.getChildren().remove(transitionOutgoing);
            disposeRenderedContent(transitionOutgoing);
            transitionOutgoing.setOpacity(1);
        }
        transitionIncoming.setOpacity(1);
        currentContent = transitionIncoming;
        finalSource = transitionSource;
        root.getProperties().put("markdownRenderedSource", finalSource);
        clearTransitionReferences();
        setState(MarkdownBubble.State.FINAL_MARKDOWN);
    }

    private void discardVisualTransition() {
        if (contentTransition != null) contentTransition.stop();
        if (transitionIncoming != null) {
            root.getChildren().remove(transitionIncoming);
            disposeRenderedContent(transitionIncoming);
        }
        if (transitionOutgoing != null) {
            transitionOutgoing.setOpacity(1);
            currentContent = transitionOutgoing;
        }
        clearTransitionReferences();
    }

    private void clearTransitionReferences() {
        contentTransition = null;
        transitionOutgoing = null;
        transitionIncoming = null;
        transitionSource = null;
    }

    private boolean shouldAnimate() {
        Scene scene = root.getScene();
        if (scene == null || !isTreeVisible(root)) return false;
        Bounds bounds = root.localToScene(root.getBoundsInLocal());
        return bounds != null && bounds.intersects(0, 0, scene.getWidth(), scene.getHeight());
    }

    private static boolean isTreeVisible(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible() || current.getOpacity() <= 0) return false;
        }
        return true;
    }

    private boolean isCurrent(long generation) {
        return !disposed && generation == renderGeneration;
    }

    private void disposeRenderedContent(Node content) {
        if (content != null && content != plainView) {
            MarkdownRenderedViewFactory.dispose(content);
        }
    }

    private void setState(MarkdownBubble.State next) {
        viewModel.setState(next);
        root.getProperties().put("markdownRenderState", next.name());
    }

    private void clearRenderDiagnostics() {
        root.getProperties().remove("markdownRenderFallback");
        root.getProperties().remove("markdownRenderFailure");
        root.getProperties().remove("markdownAnimationFailure");
        root.getProperties().remove("markdownTransitionStartedAtNanos");
        root.getProperties().remove("markdownTransitionFinishedAtNanos");
    }

    private void clearDiagnostics() {
        root.getProperties().remove("markdownBubble");
        root.getProperties().remove("markdownRenderFailure");
        root.getProperties().remove("markdownRenderedSource");
        root.getProperties().remove("markdownRenderFallback");
        root.getProperties().remove("markdownAnimationFailure");
        root.getProperties().remove("markdownTransitionStartedAtNanos");
        root.getProperties().remove("markdownTransitionFinishedAtNanos");
    }
}
