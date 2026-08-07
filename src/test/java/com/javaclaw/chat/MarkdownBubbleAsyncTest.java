package com.javaclaw.chat;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务；默认由 MarkdownBubbleDriver 端到端验收")
class MarkdownBubbleAsyncTest {

    private static final long TIMEOUT_SECONDS = 5;
    private ExecutorService executor;
    private MarkdownBubble bubble;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bubble != null) runFx(bubble::dispose);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void thousandStreamingChunksParseOnlyOnceAfterFinish() throws Exception {
        executor = daemonPool(2);
        AtomicInteger renderCount = new AtomicInteger();
        AtomicBoolean renderedOnFxThread = new AtomicBoolean(true);
        bubble = callFx(() -> new MarkdownBubble(520, executor, (markdown, style) -> {
            renderCount.incrementAndGet();
            renderedOnFxThread.set(Platform.isFxApplicationThread());
            return MarkdownParagraphRenderer.render(markdown, style);
        }, 80));

        runFx(() -> {
            for (int i = 0; i < 1_000; i++) bubble.appendText("片段" + i + " ");
        });
        assertEquals(0, renderCount.get());

        runFx(() -> {
            bubble.finish();
            bubble.finish();
        });
        awaitState(MarkdownBubble.State.FINAL_MARKDOWN);

        assertEquals(1, renderCount.get());
        assertFalse(renderedOnFxThread.get());
        assertEquals(callFx(bubble::getText),
                callFx(() -> bubble.getView().getProperties().get("markdownRenderedSource")));
    }

    @Test
    void slowRenderKeepsFxThreadResponsiveAndShowsDelayedHint() throws Exception {
        executor = daemonPool(1);
        CountDownLatch renderStarted = new CountDownLatch(1);
        CountDownLatch releaseRender = new CountDownLatch(1);
        bubble = callFx(() -> new MarkdownBubble(520, executor, (markdown, style) -> {
            renderStarted.countDown();
            awaitUninterruptibly(releaseRender);
            return MarkdownParagraphRenderer.render(markdown, style);
        }, 80));

        runFx(() -> {
            bubble.appendText("**慢速渲染**");
            bubble.finish();
        });
        assertTrue(renderStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // renderer 被阻塞时，FX 队列中的 heartbeat 仍必须及时执行。
        assertTrue(callFx(() -> true));
        Thread.sleep(140);
        Label hint = callFx(() -> findLabel(bubble.getView(), "md-rendering-hint"));
        assertNotNull(hint);
        assertTrue(callFx(hint::isVisible));

        releaseRender.countDown();
        awaitState(MarkdownBubble.State.FINAL_MARKDOWN);
        assertFalse(callFx(hint::isVisible));
    }

    @Test
    void quickRenderNeverFlashesRenderingHint() throws Exception {
        executor = daemonPool(1);
        bubble = callFx(() -> new MarkdownBubble(
                520, executor, MarkdownParagraphRenderer::render, 200));

        runFx(() -> {
            bubble.appendText("快速内容");
            bubble.finish();
        });
        awaitState(MarkdownBubble.State.FINAL_MARKDOWN);
        Thread.sleep(250);

        Label hint = callFx(() -> findLabel(bubble.getView(), "md-rendering-hint"));
        assertNotNull(hint);
        assertFalse(callFx(hint::isVisible));
    }

    @Test
    void staleRenderCannotOverwriteNewerGeneration() throws Exception {
        executor = daemonPool(2);
        AtomicInteger invocation = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        bubble = callFx(() -> new MarkdownBubble(520, executor, (markdown, style) -> {
            if (invocation.incrementAndGet() == 1) {
                firstStarted.countDown();
                awaitUninterruptibly(releaseFirst);
            }
            return MarkdownParagraphRenderer.render(markdown, style);
        }, 80));

        runFx(() -> {
            bubble.appendText("旧结果");
            bubble.finish();
        });
        assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        runFx(() -> {
            bubble.appendText(" + 新结果");
            bubble.finish();
        });
        awaitState(MarkdownBubble.State.FINAL_MARKDOWN);
        String expected = callFx(bubble::getText);

        releaseFirst.countDown();
        Thread.sleep(150);
        assertEquals(expected,
                callFx(() -> bubble.getView().getProperties().get("markdownRenderedSource")));
        assertEquals(2, invocation.get());
    }

    @Test
    void oversizedMessageFallsBackWithoutCallingRenderer() throws Exception {
        executor = daemonPool(1);
        AtomicInteger renderCount = new AtomicInteger();
        bubble = callFx(() -> new MarkdownBubble(520, executor, (markdown, style) -> {
            renderCount.incrementAndGet();
            return MarkdownParagraphRenderer.render(markdown, style);
        }, 80));

        String oversized = "a".repeat(MarkdownBubble.MAX_MARKDOWN_BYTES + 1);
        runFx(() -> bubble.replaceText(oversized));

        assertEquals(MarkdownBubble.State.PLAIN_FALLBACK, callFx(bubble::state));
        assertEquals(0, renderCount.get());
        assertEquals("message-too-large",
                callFx(() -> bubble.getView().getProperties().get("markdownRenderFallback")));
    }

    @Test
    void renderFailureKeepsPlainTextAndReportsFallback() throws Exception {
        executor = daemonPool(1);
        bubble = callFx(() -> new MarkdownBubble(520, executor, (markdown, style) -> {
            throw new IllegalStateException("synthetic render failure");
        }, 0));

        runFx(() -> bubble.replaceText("保留 **原始文本**"));
        awaitState(MarkdownBubble.State.PLAIN_FALLBACK);

        assertEquals("保留 **原始文本**", callFx(bubble::getText));
        assertNotNull(callFx(() ->
                bubble.getView().getProperties().get("markdownRenderFailure")));
    }

    @Test
    void disposeDropsResultFromAlreadyStartedRender() throws Exception {
        executor = daemonPool(1);
        CountDownLatch renderStarted = new CountDownLatch(1);
        CountDownLatch releaseRender = new CountDownLatch(1);
        bubble = callFx(() -> new MarkdownBubble(520, executor, (markdown, style) -> {
            renderStarted.countDown();
            awaitUninterruptibly(releaseRender);
            return MarkdownParagraphRenderer.render(markdown, style);
        }, 80));

        runFx(() -> bubble.replaceText("即将释放"));
        assertTrue(renderStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        runFx(bubble::dispose);
        releaseRender.countDown();
        Thread.sleep(100);

        assertEquals(MarkdownBubble.State.DISPOSED, callFx(bubble::state));
        assertFalse(callFx(() -> bubble.getView().getProperties()
                .containsKey("markdownRenderedSource")));
        bubble = null;
    }

    private void awaitState(MarkdownBubble.State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(bubble::state) == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, callFx(bubble::state));
    }

    private static ExecutorService daemonPool(int threads) {
        AtomicInteger id = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, task -> Thread.ofPlatform()
                .daemon(true)
                .name("markdown-test-" + id.incrementAndGet())
                .unstarted(task));
    }

    private static Label findLabel(Node node, String styleClass) {
        if (node instanceof Label label && label.getStyleClass().contains(styleClass)) return label;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Label found = findLabel(child, styleClass);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void runFx(ThrowingRunnable action) throws Exception {
        callFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "FX 操作超时");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) throw exception;
            if (failure.get() instanceof Error error) throw error;
            throw new RuntimeException(failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
