package com.javaclaw.ui.javafx.control;

import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.interaction.InteractionDialogFactory;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class WindowToastFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;

    private AnnotationConfigApplicationContext context;
    private WindowToast toast;
    private Stage stage;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stage != null) runFx(stage::close);
        if (toast != null) runFx(toast::close);
        if (context != null) context.close();
    }

    @Test
    void rendersNotificationAndRestoresPreviousPortRendererOnWindowHide() throws Exception {
        WindowToastFactory factory = createContext();
        FxDispatcher fx = context.getBean(FxDispatcher.class);
        JfxUserInteractionPort port = new JfxUserInteractionPort(
                fx, context.getBean(ImageViewerFactory.class),
                context.getBean(InteractionDialogFactory.class));
        Consumer<String> previous = ignored -> {};
        port.setToastHandler(previous);

        WindowToastController controller = callFx(() -> {
            toast = factory.create();
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(toast.node()), 640, 300));
            toast.bindToPort(stage, port);
            stage.show();
            return toast.controller();
        });

        assertNotSame(previous, port.getToastHandler());
        runFx(() -> port.notify(new ToastRequest("测试", "已保存")));
        Label message = callFx(() -> (Label) toast.node().lookup("#messageLabel"));
        assertEquals("[测试] 已保存", callFx(message::getText));
        assertTrue(callFx(message::isVisible));

        runFx(stage::hide);
        assertSame(previous, port.getToastHandler());
        runFx(toast::close);
        assertTrue(controller.isClosed());
        assertFalse(callFx(message::isVisible));
    }

    private WindowToastFactory createContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(ImageViewerFactory.class,
                () -> new ImageViewerFactory(
                        context.getBean(SpringFxmlLoader.class),
                        context.getBean(FxDispatcher.class)));
        context.registerBean(WindowToastFactory.class,
                () -> new WindowToastFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(InteractionDialogFactory.class,
                () -> new InteractionDialogFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
        return context.getBean(WindowToastFactory.class);
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.call()); }
            catch (Throwable thrown) { failure.set(thrown); }
            finally { completed.countDown(); }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
