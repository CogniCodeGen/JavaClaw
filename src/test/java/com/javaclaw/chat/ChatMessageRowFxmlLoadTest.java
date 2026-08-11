package com.javaclaw.chat;

import com.javaclaw.chat.markdown.MarkdownParagraphRenderer;
import com.javaclaw.platform.desktop.ExternalLinkOpener;
import com.javaclaw.platform.execution.ExecutionLimits;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import org.fxmisc.richtext.InlineCssTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ChatMessageRowFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ChatMessageRowView view;

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
    void userVariantShowsPlainTextAndAttachments() throws Exception {
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, "hello",
                List.of(new File("notes.md")));
        view = create(ChatMessageRowFactory.Variant.USER, message);

        HBox normal = find("normalRow", HBox.class);
        InlineCssTextArea plain = find("plainTextArea", InlineCssTextArea.class);
        FlowPane attachments = find("attachmentFlow", FlowPane.class);
        Label author = find("authorLabel", Label.class);
        assertTrue(callFx(normal::isManaged));
        assertEquals("hello", callFx(plain::getText));
        assertEquals("You", callFx(author::getText));
        assertEquals(1, callFx(() -> attachments.getChildren().size()));
        assertNull(callFx(view::markdownBubble));
    }

    @Test
    void assistantVariantOwnsMarkdownLifecycle() throws Exception {
        view = create(ChatMessageRowFactory.Variant.ASSISTANT,
                new ChatMessage(ChatMessage.Role.ASSISTANT, "**answer**"));
        MarkdownBubble bubble = callFx(view::markdownBubble);

        assertNotNull(bubble);
        assertEquals("**answer**", callFx(bubble::getText));
        runFx(view::close);
        assertEquals(MarkdownBubble.State.DISPOSED, callFx(bubble::state));
        view = null;
    }

    @Test
    void systemVariantUsesCenteredBranch() throws Exception {
        view = create(ChatMessageRowFactory.Variant.SYSTEM,
                new ChatMessage(ChatMessage.Role.SYSTEM, "warning"));

        HBox system = find("systemRow", HBox.class);
        HBox normal = find("normalRow", HBox.class);
        InlineCssTextArea text = find("systemTextArea", InlineCssTextArea.class);
        assertTrue(callFx(system::isManaged));
        assertFalse(callFx(normal::isManaged));
        assertEquals("warning", callFx(text::getText));
    }

    private ChatMessageRowView create(
            ChatMessageRowFactory.Variant variant, ChatMessage message) throws Exception {
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
        context.registerBean(ChatMessageRowFactory.class,
                () -> new ChatMessageRowFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
        return callFx(() -> context.getBean(ChatMessageRowFactory.class).create(
                variant, message, "JavaClaw", "gpt-5", "1.2s",
                List.of(), (image, file) -> { }));
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
