package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SpecialChatCardsFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private AutoCloseable view;

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
    void loopDecisionCanBeSubmittedOnlyOnceAndDestroysController() throws Exception {
        prepareContext();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> result = new AtomicReference<>();
        LoopDecisionView decisionView = callFx(() ->
                context.getBean(LoopDecisionFactory.class).create("shell", 3, selected -> {
                    calls.incrementAndGet();
                    result.set(selected);
                }));
        view = decisionView;

        Label prompt = find(decisionView.root(), "promptLabel", Label.class);
        Button continueButton = find(decisionView.root(), "continueButton", Button.class);
        Label resolution = find(decisionView.root(), "resolutionLabel", Label.class);
        assertEquals("检测到工具 [shell] 连续 3 次相似调用，已暂停。是否继续执行？",
                callFx(prompt::getText));

        runFx(() -> {
            continueButton.fire();
            continueButton.fire();
        });

        assertEquals(1, calls.get());
        assertEquals(Boolean.TRUE, result.get());
        assertEquals("已选择继续执行", callFx(resolution::getText));
        assertFalse(callFx(continueButton::isManaged));
        assertNotNull(callFx(() ->
                decisionView.root().getProperties().get("loopDecisionView")));

        runFx(decisionView::close);
        assertTrue(decisionView.controller().isClosed());
        view = null;
    }

    @Test
    void clarificationCardShowsOnlySuppliedSectionsAndDestroysController() throws Exception {
        prepareContext();
        ClarificationCardView card = callFx(() ->
                context.getBean(ClarificationCardFactory.class).create(
                        "JavaClaw", "gpt-5", "10:30", "", "请选择目标工作区"));
        view = card;

        VBox reason = find(card.root(), "reasonSection", VBox.class);
        VBox question = find(card.root(), "questionSection", VBox.class);
        InlineCssTextArea questionText = find(
                card.root(), "questionText", InlineCssTextArea.class);

        assertFalse(callFx(reason::isManaged));
        assertTrue(callFx(question::isManaged));
        assertEquals("请选择目标工作区", callFx(questionText::getText));
        assertNotNull(callFx(() ->
                card.root().getProperties().get("clarificationCardView")));

        runFx(card::close);
        assertTrue(card.controller().isClosed());
        view = null;
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(LoopDecisionFactory.class,
                () -> new LoopDecisionFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(ClarificationCardFactory.class,
                () -> new ClarificationCardFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
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
