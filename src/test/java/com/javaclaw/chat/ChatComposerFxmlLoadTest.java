package com.javaclaw.chat;

import com.javaclaw.platform.desktop.ProjectAttachmentPicker;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.File;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ChatComposerFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ViewHandle<VBox> handle;

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
        if (handle != null) runFx(handle::close);
        if (context != null) context.close();
    }

    @Test
    void routesSendStopAndMapsViewModelStateToFxmlNodes() throws Exception {
        ChatComposerController controller = loadComposer();
        AtomicBoolean sent = new AtomicBoolean();
        AtomicBoolean stopped = new AtomicBoolean();
        runFx(() -> {
            controller.setOnSend(() -> sent.set(true));
            controller.setOnStop(() -> stopped.set(true));
            controller.replaceInput("  hello  ");
        });
        Button sendButton = find(handle.root(), "sendButton", Button.class);

        runFx(sendButton::fire);
        assertTrue(sent.get());
        assertEquals("hello", callFx(controller::trimmedInput));

        runFx(() -> {
            controller.setThinkingText("正在测试...");
            controller.setThinkingVisible(true);
            controller.setStreaming(true);
            sendButton.fire();
        });
        assertTrue(stopped.get());
        assertEquals("停止", callFx(sendButton::getText));
        assertTrue(callFx(() -> sendButton.getStyleClass().contains("stop-button")));
        runFx(() -> {
            controller.setStreaming(false);
            controller.setBlocked(true);
        });
        assertTrue(callFx(sendButton::isDisabled));
        assertEquals("发送", callFx(sendButton::getText));
        Label thinking = find(handle.root(), "typingTextLabel", Label.class);
        assertEquals("正在测试...", callFx(thinking::getText));
        assertTrue(callFx(thinking::isVisible));
        runFx(handle::close);
        assertTrue(controller.isClosed());
        handle = null;
    }

    @Test
    void attachmentCellLoadsThroughSpringAndIsDestroyedWithHandle() throws Exception {
        loadComposer();
        URL resource = getClass().getResource("/fxml/chat/attachment-preview-item.fxml");
        assertNotNull(resource);
        ViewHandle<StackPane> item = callFx(() ->
                context.getBean(SpringFxmlLoader.class).load(resource));
        AttachmentPreviewItemController controller =
                item.controller(AttachmentPreviewItemController.class);
        AtomicBoolean removed = new AtomicBoolean();
        runFx(() -> controller.configure(new File("proposal.md"), () -> removed.set(true)));

        Label fileType = find(item.root(), "fileType", Label.class);
        assertEquals("MD", callFx(fileType::getText));
        Button remove = findFirstButton(item.root());
        runFx(remove::fire);
        assertTrue(removed.get());
        runFx(item::close);
        assertTrue(controller.isClosed());
    }

    private ChatComposerController loadComposer() throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(ProjectAttachmentPicker.class, ProjectAttachmentPicker::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.refresh();
        URL resource = getClass().getResource("/fxml/chat/chat-composer.fxml");
        assertNotNull(resource);
        handle = callFx(() -> context.getBean(SpringFxmlLoader.class).load(resource));
        return handle.controller(ChatComposerController.class);
    }

    private static Button findFirstButton(Node node) throws Exception {
        return callFx(() -> {
            if (node instanceof Button button) return button;
            if (node instanceof Parent parent) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    Button found = findButton(child);
                    if (found != null) return found;
                }
            }
            throw new AssertionError("未找到 Button");
        });
    }

    private static Button findButton(Node node) {
        if (node instanceof Button button) return button;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Button found = findButton(child);
                if (found != null) return found;
            }
        }
        return null;
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
        if (id.equals(node.getId()) || id.equals(node.getProperties().get("fx:id"))) return node;
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
