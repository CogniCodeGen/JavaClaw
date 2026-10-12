package com.javaclaw.chat;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.List;
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
class ChatSessionFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private ViewHandle<StackPane> handle;

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
    void ownsMessagesUnreadStateAndDetachmentLifecycle() throws Exception {
        ChatSessionController controller = load();
        VBox empty = field(controller, "emptyState", VBox.class);
        Button unread = field(controller, "newMessagesButton", Button.class);
        assertTrue(callFx(empty::isVisible));

        Label nested = new Label("nested");
        HBox row = new HBox(new VBox(nested));
        runFx(() -> controller.addMessage(row));
        assertFalse(callFx(empty::isVisible));
        assertTrue(callFx(() -> controller.removeContaining(nested)));
        assertTrue(callFx(empty::isVisible));

        runFx(() -> {
            controller.viewModel().followTailProperty().set(false);
            controller.addMessage(new Label("one"));
            controller.addMessage(new Label("two"));
        });
        assertEquals(2, controller.viewModel().unreadMessagesProperty().get());
        assertTrue(callFx(unread::isVisible));
        assertEquals("↓ 2 条新消息", callFx(unread::getText));

        runFx(unread::fire);
        assertEquals(0, controller.viewModel().unreadMessagesProperty().get());
        List<javafx.scene.Node> detached = callFx(controller::detachMessages);
        assertEquals(2, detached.size());
        assertTrue(callFx(empty::isVisible));
        runFx(() -> controller.setMessages(detached));
        assertFalse(callFx(empty::isVisible));

        runFx(handle::close);
        assertTrue(controller.isClosed());
        handle = null;
    }

    private ChatSessionController load() throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.refresh();
        URL resource = getClass().getResource("/fxml/chat/chat-session-view.fxml");
        assertNotNull(resource);
        handle = callFx(() -> context.getBean(SpringFxmlLoader.class).load(resource));
        return handle.controller(ChatSessionController.class);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        assertTrue(field.trySetAccessible());
        return type.cast(field.get(target));
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
