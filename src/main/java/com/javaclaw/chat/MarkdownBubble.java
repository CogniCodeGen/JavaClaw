package com.javaclaw.chat;

import com.javaclaw.app.UiMotion;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.LinkRange;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderStyleSnapshot;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer.RenderedMarkdown;
import com.javaclaw.ui.javafx.theme.FontManager;
import com.javaclaw.ui.javafx.theme.ThemeManager;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;
import org.fxmisc.richtext.InlineCssTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Markdown 消息气泡：流式期间展示可选择的原始文本，消息完成后异步一次性排版。
 *
 * <p>CommonMark 解析和只读模型构建在最多两个共享后台线程上执行；JavaFX Node
 * 只在结果回到 FX 线程后创建。原始文本一直保留到最终视图准备完成，再以 150ms
 * 纯透明度交叉淡入替换，因此慢解析不会清空气泡或阻塞 UI。</p>
 */
public class MarkdownBubble {

    private static final Logger log = LoggerFactory.getLogger(MarkdownBubble.class);

    static final int MAX_MARKDOWN_BYTES = 256 * 1024;
    static final long RENDERING_HINT_DELAY_MS = 80;

    private static final AtomicInteger RENDER_THREAD_ID = new AtomicInteger();
    private static final ThreadPoolExecutor SHARED_RENDER_EXECUTOR = new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            task -> Thread.ofPlatform()
                    .daemon(true)
                    .name("markdown-render-" + RENDER_THREAD_ID.incrementAndGet())
                    .unstarted(task));

    enum State {
        STREAMING_PLAIN,
        RENDERING,
        FINAL_MARKDOWN,
        PLAIN_FALLBACK,
        DISPOSED
    }

    @FunctionalInterface
    interface RenderFunction {
        RenderedMarkdown render(String markdown, RenderStyleSnapshot style);
    }

    private final double prefWidth;
    private final StackPane root = new StackPane();
    private final InlineCssTextArea plainView = new InlineCssTextArea();
    private final Label renderingHint = new Label("正在排版…");
    private final RenderingOverlay renderingOverlay = new RenderingOverlay(renderingHint);
    private final StringBuilder content = new StringBuilder();
    private final ExecutorService renderExecutor;
    private final RenderFunction renderFunction;
    private final long hintDelayMillis;

    private final PauseTransition renderingHintDelay;
    private FadeTransition renderingHintFade;
    private Future<?> pendingRender;
    private Animation contentTransition;
    private Node transitionOutgoing;
    private Node transitionIncoming;
    private String transitionSource;
    private Node currentContent;

    private long renderGeneration;
    private String submittedSource;
    private String finalSource;
    private State state = State.STREAMING_PLAIN;
    private volatile boolean disposed;

    private final ChangeListener<Number> fontRevisionListener =
            (observable, oldValue, newValue) -> refreshForAppearanceChange();
    private final ChangeListener<Number> themeRevisionListener =
            (observable, oldValue, newValue) -> refreshForAppearanceChange();

    /** 创建 Markdown 气泡。 */
    public MarkdownBubble(double prefWidth) {
        this(prefWidth, SHARED_RENDER_EXECUTOR,
                MarkdownParagraphRenderer::render, RENDERING_HINT_DELAY_MS);
    }

    /** 包级注入入口，仅供确定性生命周期测试使用。 */
    MarkdownBubble(
            double prefWidth,
            ExecutorService renderExecutor,
            RenderFunction renderFunction,
            long hintDelayMillis) {
        this.prefWidth = prefWidth;
        this.renderExecutor = Objects.requireNonNull(renderExecutor, "renderExecutor");
        this.renderFunction = Objects.requireNonNull(renderFunction, "renderFunction");
        this.hintDelayMillis = Math.max(0, hintDelayMillis);

        configurePlainView();
        renderingHint.getStyleClass().add("md-rendering-hint");
        renderingHint.setVisible(false);
        renderingHint.setMouseTransparent(true);

        root.setAlignment(Pos.TOP_LEFT);
        root.setPrefWidth(prefWidth);
        root.getStyleClass().add("md-bubble-host");
        root.getChildren().addAll(plainView, renderingOverlay);
        currentContent = plainView;

        renderingHintDelay = new PauseTransition(Duration.millis(this.hintDelayMillis));

        FontManager.revisionProperty().addListener(fontRevisionListener);
        ThemeManager.revisionProperty().addListener(themeRevisionListener);

        root.getProperties().put("markdownBubble", this);
        setState(State.STREAMING_PLAIN);
    }

    /** 获取可加入场景图的稳定根节点。 */
    public Region getView() {
        return root;
    }

    /** 追加一个流式文本片段；此阶段不会调用 CommonMark。 */
    public void appendText(String chunk) {
        if (chunk == null || chunk.isEmpty() || disposed) return;
        if (state != State.STREAMING_PLAIN) switchBackToStreamingPlain();
        content.append(chunk);
        plainView.appendText(chunk);
        updatePlainFallbackHeight();
    }

    /**
     * 替换全部内容并提交终态渲染。历史回放、失败和取消消息均走此入口。
     */
    public void replaceText(String text) {
        if (disposed) return;
        invalidatePendingWork();
        content.setLength(0);
        if (text != null) content.append(text);
        plainView.replaceText(content.toString());
        updatePlainFallbackHeight();
        showPlainImmediately();
        setState(State.STREAMING_PLAIN);
        finish();
    }

    /**
     * 重新排版当前终态内容。若仍处于流式阶段，此调用等价于提交当前消息。
     */
    public void refresh() {
        if (disposed) return;
        if (state == State.STREAMING_PLAIN) {
            finish();
        } else {
            submitRender(true);
        }
    }

    /** 获取原始 Markdown。 */
    public String getText() {
        return content.toString();
    }

    /** 获取原始 Markdown 字符数。 */
    public int getLength() {
        return content.length();
    }

    /**
     * 释放监听器、排队任务和动画。正在解析且无法及时中断的结果会被 generation 丢弃。
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        renderGeneration++;
        cancelPendingRender();
        renderingHintDelay.stop();
        stopRenderingHintFade();
        discardVisualTransition();
        FontManager.revisionProperty().removeListener(fontRevisionListener);
        ThemeManager.revisionProperty().removeListener(themeRevisionListener);
        root.getProperties().remove("markdownBubble");
        root.getProperties().remove("markdownRenderFailure");
        root.getProperties().remove("markdownRenderedSource");
        root.getProperties().remove("markdownRenderFallback");
        root.getProperties().remove("markdownAnimationFailure");
        root.getProperties().remove("markdownTransitionStartedAtNanos");
        root.getProperties().remove("markdownTransitionFinishedAtNanos");
        root.getChildren().clear();
        plainView.replaceText("");
        content.setLength(0);
        submittedSource = null;
        finalSource = null;
        setState(State.DISPOSED);
    }

    /** 正常终态入口；保持包级可见，避免扩大组件的公开 API。 */
    void finish() {
        if (disposed) return;
        submitRender(false);
    }

    /** 替换终态文本并提交渲染；供错误/取消等消息生命周期使用。 */
    void finishWith(String text) {
        replaceText(text);
    }

    State state() {
        return state;
    }

    private void configurePlainView() {
        plainView.setEditable(false);
        plainView.setWrapText(true);
        plainView.setPrefWidth(prefWidth);
        plainView.getStyleClass().addAll("bubble-text-area", "md-plain-view");
        plainView.setContextMenu(createContextMenu(plainView::copy, plainView::selectAll));
        plainView.totalHeightEstimateProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue.doubleValue() > 0) {
                setPlainHeight(newValue.doubleValue() + 4);
            }
        });
        updatePlainFallbackHeight();
    }

    private ContextMenu createContextMenu(Runnable copySelection, Runnable selectAll) {
        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem("复制");
        copy.setOnAction(event -> copySelection.run());
        MenuItem select = new MenuItem("全选");
        select.setOnAction(event -> selectAll.run());
        MenuItem copyRaw = new MenuItem("复制原文 (Markdown)");
        copyRaw.setOnAction(event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(getText());
            Clipboard.getSystemClipboard().setContent(clipboard);
        });
        menu.getItems().addAll(copy, select, new SeparatorMenuItem(), copyRaw);
        return menu;
    }

    private void updatePlainFallbackHeight() {
        int lines = Math.max(1, plainView.getParagraphs().size());
        double estimatedLineHeight = Math.max(20, FontManager.chatFontPx() * 1.4);
        setPlainHeight(Math.max(28, lines * estimatedLineHeight + 8));
    }

    private void setPlainHeight(double height) {
        double bounded = Math.max(28, height);
        plainView.setPrefHeight(bounded);
        plainView.setMinHeight(bounded);
        plainView.setMaxHeight(bounded);
    }

    private void submitRender(boolean force) {
        String source = content.toString();
        if (!force) {
            if (state == State.RENDERING && source.equals(submittedSource)) return;
            if (state == State.FINAL_MARKDOWN && source.equals(finalSource)) return;
            if (state == State.PLAIN_FALLBACK && source.equals(submittedSource)) return;
        }

        settleVisualTransition();
        cancelPendingRender();
        stopRenderingHint(false);

        if (utf8LengthExceedsLimit(source)) {
            renderGeneration++;
            submittedSource = source;
            showPlainImmediately();
            root.getProperties().put("markdownRenderFallback", "message-too-large");
            setState(State.PLAIN_FALLBACK);
            log.info("Markdown 消息超过 {} KiB，保留普通文本（{} 字符）",
                    MAX_MARKDOWN_BYTES / 1024, source.length());
            return;
        }

        root.getProperties().remove("markdownRenderFallback");
        root.getProperties().remove("markdownRenderFailure");
        root.getProperties().remove("markdownAnimationFailure");
        root.getProperties().remove("markdownTransitionStartedAtNanos");
        root.getProperties().remove("markdownTransitionFinishedAtNanos");
        long generation = ++renderGeneration;
        submittedSource = source;
        RenderStyleSnapshot style = RenderStyleSnapshot.capture();
        setState(State.RENDERING);
        scheduleRenderingHint(generation);

        try {
            pendingRender = renderExecutor.submit(() -> {
                try {
                    RenderedMarkdown rendered = renderFunction.render(source, style);
                    postToFx(() -> acceptRendered(generation, source, rendered));
                } catch (Throwable failure) {
                    postToFx(() -> acceptRenderFailure(generation, failure));
                }
            });
        } catch (RejectedExecutionException failure) {
            acceptRenderFailure(generation, failure);
        }
    }

    private void acceptRendered(long generation, String source, RenderedMarkdown rendered) {
        if (!isCurrent(generation)) return;
        pendingRender = null;
        stopRenderingHint(false);

        RichTextArea nextView;
        try {
            nextView = createMarkdownView(rendered);
        } catch (Throwable failure) {
            acceptRenderFailure(generation, failure);
            return;
        }

        Node outgoing = currentContent;
        transitionOutgoing = outgoing;
        transitionIncoming = nextView;
        transitionSource = source;
        nextView.setOpacity(0);
        int overlayIndex = root.getChildren().indexOf(renderingOverlay);
        root.getChildren().add(Math.max(0, overlayIndex), nextView);

        // 让新 RichTextArea 先经历一次 CSS/layout pulse，再开始透明度动画。
        Platform.runLater(() -> startContentTransition(generation, outgoing, nextView));
    }

    private RichTextArea createMarkdownView(RenderedMarkdown rendered) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("RichTextArea 必须在 JavaFX Application Thread 创建");
        }
        RichTextArea view = new RichTextArea(rendered.model());
        view.setEditable(false);
        view.setWrapText(true);
        view.setUseContentHeight(true);
        view.setFocusTraversable(false);
        view.setPrefWidth(prefWidth);
        view.getStyleClass().add("md-bubble");
        view.setContextMenu(createContextMenu(view::copy, view::selectAll));

        List<LinkRange> links = rendered.links();
        view.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY
                    || event.getClickCount() != 1
                    || !event.isStillSincePress()) return;
            LinkRange link = linkAt(view, links, event.getX(), event.getY());
            if (link != null) openLink(link.url());
        });
        view.addEventHandler(MouseEvent.MOUSE_MOVED, event ->
                view.setCursor(linkAt(view, links, event.getX(), event.getY()) != null
                        ? Cursor.HAND : Cursor.TEXT));
        return view;
    }

    private void startContentTransition(
            long generation, Node expectedOutgoing, Node expectedIncoming) {
        if (!isCurrent(generation)
                || transitionOutgoing != expectedOutgoing
                || transitionIncoming != expectedIncoming) return;

        if (!shouldAnimate()) {
            completeVisualTransition(generation, expectedOutgoing, expectedIncoming);
            return;
        }
        try {
            root.getProperties().put("markdownTransitionStartedAtNanos", System.nanoTime());
            contentTransition = UiMotion.crossFade(
                    expectedOutgoing,
                    expectedIncoming,
                    () -> completeVisualTransition(generation, expectedOutgoing, expectedIncoming));
        } catch (Throwable failure) {
            log.warn("Markdown 视图切换动画失败，直接完成替换", failure);
            root.getProperties().put("markdownAnimationFailure", failure.toString());
            completeVisualTransition(generation, expectedOutgoing, expectedIncoming);
        }
    }

    private void completeVisualTransition(
            long generation, Node expectedOutgoing, Node expectedIncoming) {
        if (!isCurrent(generation)
                || transitionOutgoing != expectedOutgoing
                || transitionIncoming != expectedIncoming) return;
        root.getChildren().remove(expectedOutgoing);
        expectedOutgoing.setOpacity(1);
        expectedIncoming.setOpacity(1);
        currentContent = expectedIncoming;
        finalSource = transitionSource;
        root.getProperties().put("markdownRenderedSource", finalSource);
        if (root.getProperties().containsKey("markdownTransitionStartedAtNanos")) {
            root.getProperties().put("markdownTransitionFinishedAtNanos", System.nanoTime());
        }
        clearTransitionReferences();
        setState(State.FINAL_MARKDOWN);
    }

    private void acceptRenderFailure(long generation, Throwable failure) {
        if (!isCurrent(generation)) return;
        pendingRender = null;
        root.getProperties().put("markdownRenderFailure", failure.toString());
        stopRenderingHint(true);
        if (currentContent == plainView) {
            setState(State.PLAIN_FALLBACK);
        } else {
            setState(State.FINAL_MARKDOWN);
        }
        log.error("Markdown 终态渲染失败，保留当前文本视图", failure);
    }

    private void scheduleRenderingHint(long generation) {
        stopRenderingHintFade();
        renderingHintDelay.stop();
        renderingHintDelay.setDuration(Duration.millis(hintDelayMillis));
        renderingHintDelay.setOnFinished(event -> {
            if (!isCurrent(generation) || state != State.RENDERING) return;
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
        if (disposed || state == State.STREAMING_PLAIN) return;
        submitRender(true);
    }

    private void switchBackToStreamingPlain() {
        invalidatePendingWork();
        plainView.replaceText(content.toString());
        updatePlainFallbackHeight();
        showPlainImmediately();
        setState(State.STREAMING_PLAIN);
    }

    private void invalidatePendingWork() {
        renderGeneration++;
        cancelPendingRender();
        renderingHintDelay.stop();
        stopRenderingHint(false);
        discardVisualTransition();
    }

    private void cancelPendingRender() {
        Future<?> task = pendingRender;
        pendingRender = null;
        if (task != null) task.cancel(true);
    }

    private void showPlainImmediately() {
        if (currentContent == plainView && transitionIncoming == null) return;
        discardVisualTransition();
        if (currentContent != null && currentContent != plainView) {
            root.getChildren().remove(currentContent);
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
            transitionOutgoing.setOpacity(1);
        }
        transitionIncoming.setOpacity(1);
        currentContent = transitionIncoming;
        finalSource = transitionSource;
        root.getProperties().put("markdownRenderedSource", finalSource);
        clearTransitionReferences();
        setState(State.FINAL_MARKDOWN);
    }

    private void discardVisualTransition() {
        if (contentTransition != null) contentTransition.stop();
        if (transitionIncoming != null) root.getChildren().remove(transitionIncoming);
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

    private void setState(State next) {
        state = next;
        root.getProperties().put("markdownRenderState", next.name());
    }

    static boolean utf8LengthExceedsLimit(String text) {
        if (text.length() > MAX_MARKDOWN_BYTES) return true;
        if (text.length() <= MAX_MARKDOWN_BYTES / 3) return false;
        return text.getBytes(StandardCharsets.UTF_8).length > MAX_MARKDOWN_BYTES;
    }

    private static LinkRange linkAt(
            RichTextArea view, List<LinkRange> links, double x, double y) {
        if (links.isEmpty()) return null;
        TextPos position = view.getTextPosition(x, y);
        if (position == null) return null;
        for (LinkRange link : links) {
            if (position.index() == link.paragraphIndex()
                    && position.offset() >= link.startOffset()
                    && position.offset() < link.endOffset()) {
                return link;
            }
        }
        return null;
    }

    private static void openLink(String url) {
        Thread.ofVirtual().name("md-link-open").start(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception failure) {
                log.warn("打开链接失败: {}", url, failure);
            }
        });
    }

    /** 零首选尺寸覆盖层：填满 StackPane，但不会参与宿主的首选尺寸计算。 */
    private static final class RenderingOverlay extends Pane {
        private final Label label;

        RenderingOverlay(Label label) {
            this.label = label;
            getChildren().add(label);
            setMouseTransparent(true);
            setPickOnBounds(false);
            setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }

        @Override
        protected double computeMinWidth(double height) {
            return 0;
        }

        @Override
        protected double computeMinHeight(double width) {
            return 0;
        }

        @Override
        protected double computePrefWidth(double height) {
            return 0;
        }

        @Override
        protected double computePrefHeight(double width) {
            return 0;
        }

        @Override
        protected void layoutChildren() {
            double width = snapSizeX(label.prefWidth(-1));
            double height = snapSizeY(label.prefHeight(width));
            label.resizeRelocate(Math.max(0, getWidth() - width), 0, width, height);
        }
    }

    private static void postToFx(Runnable action) {
        try {
            Platform.runLater(action);
        } catch (IllegalStateException toolkitStopped) {
            log.debug("JavaFX 已停止，丢弃 Markdown 后台结果", toolkitStopped);
        }
    }
}
