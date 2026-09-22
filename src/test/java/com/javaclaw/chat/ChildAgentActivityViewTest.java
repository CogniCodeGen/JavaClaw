package com.javaclaw.chat;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer;
import com.javaclaw.platform.desktop.ExternalLinkOpener;
import com.javaclaw.platform.execution.ExecutionLimits;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.testsupport.FxmlTestBeans;
import javafx.application.Platform;
import javafx.scene.control.Button;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ChildAgentActivityViewTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ChildAgentActivityView view;

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
    void groupsAgentsAndCollapsesAfterCompletion() throws Exception {
        view = createView();
        runFx(() -> {
            view.append("编程专家", ChatStreamRenderer.ChunkKind.REPLY, "实现建议");
            view.append("知识专家", ChatStreamRenderer.ChunkKind.RESULT, "参考资料");
            view.finish(DeliveryState.COMPLETE);
        });

        assertEquals(2, callFx(() -> view.root().lookupAll(
                ".expandable-markdown-block").size()));
        assertFalse(callFx(() -> view.expandedProperty().get()));
        Thread.sleep(400);
        assertFalse(callFx(() -> view.root().lookup(
                ".child-agent-activity-entries").isManaged()));
    }

    @Test
    void completedActivityCanBeReopened() throws Exception {
        view = createView();
        runFx(() -> {
            view.append("编程专家", ChatStreamRenderer.ChunkKind.REPLY, "实现建议");
            view.finish(DeliveryState.COMPLETE);
        });
        Thread.sleep(400);
        Button toggle = callFx(() -> (Button) view.root().lookup(".bubble-icon-btn"));
        runFx(toggle::fire);

        assertTrue(callFx(() -> view.expandedProperty().get()));
        assertTrue(callFx(() -> view.root().lookup(
                ".child-agent-activity-entries").isManaged()));
    }

    private ChildAgentActivityView createView() throws Exception {
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
        FxmlTestBeans.register(context);
        context.refresh();
        return callFx(() -> new ChildAgentActivityView(
                context.getBean(ExpandableMarkdownBlockFactory.class)));
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
