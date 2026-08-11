package com.javaclaw.ui.javafx.image;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class ImageViewerFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDirectory;
    private AnnotationConfigApplicationContext context;
    private ImageViewerView view;

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
        if (view != null) runFx(view::close);
        if (context != null) context.close();
    }

    @Test
    void opensFitsZoomsHandlesShortcutAndOwnsControllerLifecycle() throws Exception {
        Path image = tempDirectory.resolve("large-preview.png");
        assertTrue(ImageIO.write(
                new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB),
                "png", image.toFile()));
        ImageViewerFactory factory = createContext();

        ImageViewerController controller = callFx(() -> {
            view = factory.open(null, image);
            return view.controller();
        });
        awaitFx(() -> controller.scale() > 0 && controller.scale() < 1.0);

        assertEquals("图片查看 — large-preview.png", callFx(() -> view.stage().getTitle()));
        assertEquals(900.0, callFx(() -> view.stage().getScene().getWidth()));
        assertEquals(680.0, callFx(() -> view.stage().getScene().getHeight()));
        double fitted = callFx(controller::scale);

        runFx(() -> button("zoomInButton").fire());
        assertTrue(callFx(controller::scale) > fitted);
        runFx(() -> view.stage().getScene().getRoot().fireEvent(new KeyEvent(
                KeyEvent.KEY_PRESSED, "0", "0", KeyCode.DIGIT0,
                false, false, false, false)));
        assertEquals(fitted, callFx(controller::scale), 0.0001);

        runFx(() -> button("closeButton").fire());
        awaitFx(controller::isClosed);
        assertFalse(callFx(() -> view.stage().isShowing()));
    }

    @Test
    void invalidFileDoesNotCreateWindow() throws Exception {
        ImageViewerFactory factory = createContext();
        assertNull(callFx(() -> factory.open(null, tempDirectory.resolve("missing.png"))));
    }

    private ImageViewerFactory createContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(ImageViewerFactory.class,
                () -> new ImageViewerFactory(
                        context.getBean(SpringFxmlLoader.class),
                        context.getBean(FxDispatcher.class)));
        context.refresh();
        return context.getBean(ImageViewerFactory.class);
    }

    private Button button(String id) {
        return (Button) view.stage().getScene().lookup("#" + id);
    }

    private static void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition), "等待 JavaFX 状态超时");
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
