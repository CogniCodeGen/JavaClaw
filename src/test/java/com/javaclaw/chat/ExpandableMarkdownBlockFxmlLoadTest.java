package com.javaclaw.chat;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer;
import com.javaclaw.platform.desktop.ExternalLinkOpener;
import com.javaclaw.platform.execution.ExecutionLimits;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ExpandableMarkdownBlockFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ExpandableMarkdownBlockView view;

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
        if (view != null) runFx(view::close);
        if (context != null) context.close();
    }

    @Test
    void subAgentResultRevealsCollapsesAndOwnsMarkdown() throws Exception {
        view = create(ExpandableMarkdownBlockFactory.Variant.SUB_AGENT, false);
        MarkdownBubble bubble = callFx(view::bubble);
        VBox content = find(view.root(), "contentBox", VBox.class);
        Button collapse = find(view.root(), "collapseButton", Button.class);

        runFx(() -> {
            view.bubble().appendText("result");
            view.revealContent();
        });
        assertTrue(callFx(content::isVisible));
        runFx(collapse::fire);
        assertFalse(callFx(content::isManaged));
        assertEquals("▶", callFx(collapse::getText));

        runFx(view::close);
        assertEquals(MarkdownBubble.State.DISPOSED, callFx(bubble::state));
        view = null;
    }

    @Test
    void planVariantKeepsSemanticPseudoClassAndTitle() throws Exception {
        view = create(ExpandableMarkdownBlockFactory.Variant.PLAN_AGENT, true);
        Label title = find(view.root(), "titleLabel", Label.class);

        assertEquals("规划协调者", callFx(title::getText));
        assertTrue(callFx(() -> view.root().getPseudoClassStates()
                .contains(PseudoClass.getPseudoClass("plan-agent"))));
        assertNotNull(callFx(() -> view.root().getProperties()
                .get("expandableMarkdownBlockView")));
    }

    private ExpandableMarkdownBlockView create(
            ExpandableMarkdownBlockFactory.Variant variant,
            boolean initiallyVisible) throws Exception {
        context = new AnnotationConfigApplicationContext();
        ManagedTaskExecutor tasks = new ManagedTaskExecutor(
                new ExecutionLimits(16, 2, 32, 1, 1));
        context.registerBean(ManagedTaskExecutor.class, () -> tasks,
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(MarkdownRenderEngine.class,
                () -> (markdown, style) -> MarkdownParagraphRenderer.render(markdown, style));
        context.registerBean(ExternalLinkOpener.class,
                () -> new ExternalLinkOpener(tasks));
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(MarkdownBubbleFactory.class,
                () -> new MarkdownBubbleFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(ExpandableMarkdownBlockFactory.class,
                () -> new ExpandableMarkdownBlockFactory(
                        context.getBean(SpringFxmlLoader.class)));
        context.refresh();
        return callFx(() -> context.getBean(ExpandableMarkdownBlockFactory.class)
                .create(variant, "规划协调者", initiallyVisible));
    }

    private static <N extends Node> N find(Node root, String id, Class<N> type)
            throws Exception {
        return callFx(() -> {
            Node match = findById(root, id);
            assertTrue(type.isInstance(match), "未找到 #" + id);
            return type.cast(match);
        });
    }

    private static Node findById(Node node, String id) {
        if (id.equals(node.getId())) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node match = findById(child, id);
                if (match != null) return match;
            }
        }
        return null;
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
        assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
