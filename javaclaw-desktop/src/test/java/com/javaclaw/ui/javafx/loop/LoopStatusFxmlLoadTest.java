package com.javaclaw.ui.javafx.loop;

import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
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
class LoopStatusFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private LoopStatusView view;

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
    void loadsUpdatesAndDestroysFxmlStateCard() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(LoopStatusViewFactory.class).create(
                new LoopStatus(1, Decision.CONTINUE, "正在验证", 1, 3, 40, 2)));

        Label iteration = find("iterationLabel", Label.class);
        Label badge = find("badge", Label.class);
        HBox criteria = find("criteriaRow", HBox.class);
        ProgressBar progress = find("criteriaBar", ProgressBar.class);
        Label reason = find("reasonLabel", Label.class);
        assertEquals("第 1 轮", callFx(iteration::getText));
        assertEquals("进行中", callFx(badge::getText));
        assertTrue(callFx(() -> badge.getStyleClass().contains("loop-badge-run")));
        assertTrue(callFx(criteria::isManaged));
        assertEquals(1.0 / 3.0, callFx(progress::getProgress));
        assertEquals("正在验证", callFx(reason::getText));

        runFx(() -> view.update(
                new LoopStatus(2, Decision.DONE, "目标已完成", 0, 0, 80, 0)));

        assertEquals("已完成", callFx(badge::getText));
        assertTrue(callFx(() -> badge.getStyleClass().contains("loop-badge-done")));
        assertFalse(callFx(criteria::isManaged));
        assertNotNull(callFx(() -> view.root().getProperties().get("loopStatusView")));

        runFx(view::close);
        assertTrue(view.controller().isClosed());
        view = null;
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(LoopStatusViewFactory.class,
                () -> new LoopStatusViewFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private <N extends Node> N find(String id, Class<N> type) throws Exception {
        return callFx(() -> {
            Node match = findById(view.root(), id);
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
