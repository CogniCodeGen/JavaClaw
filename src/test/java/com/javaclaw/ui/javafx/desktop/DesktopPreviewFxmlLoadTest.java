package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.platform.fx.FxDispatcher;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real display regression checks for the floating FXML and the three window modes. */
@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class DesktopPreviewFxmlLoadTest {
    private static final long TIMEOUT_SECONDS = 5;
    private static final Path SNAPSHOTS = Path.of("target", "desktop-preview-qa");

    private Stage stage;
    private VBox root;
    private DesktopPreviewController controller;
    private final AtomicReference<PixelSize> requestedSize = new AtomicReference<>();
    private final AtomicInteger stopRequests = new AtomicInteger();
    private final AtomicInteger takeoverRequests = new AtomicInteger();

    @BeforeAll
    static void startToolkit() throws Exception {
        // Match Launcher: register AWT with AppKit before JavaFX starts on macOS.
        java.awt.Toolkit.getDefaultToolkit();
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
    }

    @Test
    void threeModesPreserveLiveImageAndRestoreBoundsWithOutputPixelSizes() throws Exception {
        open();
        Rectangle2D normal = callFx(this::bounds);
        Rectangle2D screen = callFx(() -> Screen.getScreensForRectangle(
                normal.getMinX(), normal.getMinY(), normal.getWidth(), normal.getHeight())
                .getFirst().getVisualBounds());
        double normalImageWidth = callFx(() -> image().getBoundsInLocal().getWidth());
        WritableImage first = callFx(() -> (WritableImage) image().getImage());
        assertImageFitsViewport();
        snapshot("normal.png");

        runFx(() -> button("minimize").fire());
        awaitSize(DesktopPreviewChrome.MINI_WIDTH, DesktopPreviewChrome.MINI_HEIGHT);
        assertTrue(callFx(() -> root.getPseudoClassStates().contains(PseudoClass.getPseudoClass("mini"))));
        assertFalse(callFx(() -> node("details").isManaged()));
        assertFalse(callFx(() -> node("subtitle").isVisible()));
        assertFalse(callFx(() -> node("resizeGrip").isVisible()));
        assertTrue(callFx(stage::isShowing));
        assertFalse(callFx(stage::isIconified));
        assertSame(first, callFx(() -> image().getImage()));
        assertTrue(callFx(() -> image().getBoundsInLocal().getWidth()) < normalImageWidth);
        assertImageFitsViewport();
        snapshot("mini.png");

        // Frame replacement remains visible while the window is a floating mini preview.
        WritableImage second = callFx(() -> fixtureImage("Live frame 2"));
        runFx(() -> controller.image(second));
        assertSame(second, callFx(() -> image().getImage()));
        runFx(() -> button("minimize").fire());
        awaitSize(normal.getWidth(), normal.getHeight());
        assertBounds(normal);
        assertTrue(callFx(() -> node("details").isManaged()));
        assertTrue(callFx(() -> node("resizeGrip").isVisible()));
        assertImageFitsViewport();

        runFx(() -> button("maximize").fire());
        awaitSize(screen.getWidth(), screen.getHeight());
        assertBounds(screen);
        assertTrue(callFx(() -> root.getPseudoClassStates().contains(
                PseudoClass.getPseudoClass("maximized"))));
        assertTrue(callFx(() -> image().getBoundsInLocal().getWidth()) > normalImageWidth);
        assertImageFitsViewport();
        snapshot("maximized.png");

        runFx(() -> button("minimize").fire());
        awaitSize(DesktopPreviewChrome.MINI_WIDTH, DesktopPreviewChrome.MINI_HEIGHT);
        runFx(() -> button("minimize").fire());
        awaitSize(screen.getWidth(), screen.getHeight());
        assertBounds(screen);
        assertSame(second, callFx(() -> image().getImage()));

        runFx(() -> root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                false, false, false, false)));
        awaitSize(normal.getWidth(), normal.getHeight());
        assertBounds(normal);
        assertImageFitsViewport();
    }

    @Test
    void themeTokensTargetsStatusAndServiceCallbacksResolveInTheLoadedFxml() throws Exception {
        open();
        assertEquals("LM Studio", callFx(() -> label("title").getText()));
        assertEquals("桌面会话 · 实时预览", callFx(() -> label("subtitle").getText()));
        runFx(() -> controller.target("LM Studio", "Models · Local library"));
        assertEquals("Models · Local library", callFx(() -> label("subtitle").getText()));

        assertEquals(Color.WHITE, callFx(() -> card().getBackground().getFills().getFirst().getFill()));
        assertEquals(Color.web("#27251F"), callFx(() -> label("title").getTextFill()));
        assertEquals(Color.web("#1F7E54"), callFx(() -> label("stateBadge").getTextFill()));
        assertEquals(12.0, callFx(() -> button("takeover").getFont().getSize()), 0.01);

        runFx(() -> {
            root.getStyleClass().add("theme-midnight");
            root.applyCss();
            root.layout();
        });
        assertEquals(Color.web("#1E1C16"), callFx(() -> card().getBackground().getFills().getFirst().getFill()));
        assertEquals(Color.web("#F3F1EB"), callFx(() -> label("title").getTextFill()));
        snapshot("midnight.png");

        runFx(() -> {
            controller.status("实时 · 窗口代次 3");
            controller.action("最近操作：滚动 · 已验证");
            controller.state("需前台接管", false, true);
            controller.image(null);
            root.applyCss();
        });
        assertEquals("实时 · 窗口代次 3", callFx(() -> label("status").getText()));
        assertEquals("最近操作：滚动 · 已验证", callFx(() -> label("actionStatus").getText()));
        assertTrue(callFx(() -> node("empty").isVisible()));
        assertEquals("等待桌面权限或前台接管", callFx(() -> label("empty").getText()));
        assertEquals(Color.web("#F59E0B"), callFx(() -> label("stateBadge").getTextFill()));
        runFx(() -> controller.state("已暂停", false, false));
        assertEquals("会话已暂停", callFx(() -> label("empty").getText()));

        runFx(() -> {
            controller.takeoverDisabled(true);
            button("takeover").fire();
        });
        assertEquals(0, takeoverRequests.get());
        runFx(() -> {
            controller.takeoverDisabled(false);
            button("takeover").fire();
            for (var node : root.lookupAll(".button")) {
                Button action = (Button) node;
                if ("停止".equals(action.getText()) || "×".equals(action.getText())) action.fire();
            }
        });
        assertEquals(1, takeoverRequests.get());
        assertEquals(2, stopRequests.get());
    }

    private void open() throws Exception {
        runFx(() -> {
            controller = new DesktopPreviewController();
            root = controller.load();
            stage = new Stage(StageStyle.TRANSPARENT);
            stage.setAlwaysOnTop(true);
            Scene scene = new Scene(root, DesktopPreviewChrome.NORMAL_WIDTH,
                    DesktopPreviewChrome.NORMAL_HEIGHT, Color.TRANSPARENT);
            scene.getStylesheets().add(getClass().getResource("/css/chat.css").toExternalForm());
            scene.getStylesheets().add(getClass().getResource("/css/desktop-preview.css").toExternalForm());
            stage.setScene(scene);
            controller.configure(stage, stopRequests::incrementAndGet, takeoverRequests::incrementAndGet,
                    (width, height) -> requestedSize.set(new PixelSize(width, height)));
            controller.target("LM Studio", "LM Studio");
            controller.state("实时", true, false);
            controller.status("实时 · 窗口代次 3");
            controller.action("最近操作：打开模型列表 · 已验证");
            controller.image(fixtureImage("Local model library"));
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            stage.setX(screen.getMinX() + 32);
            stage.setY(screen.getMinY() + 32);
            stage.show();
        });
        awaitSize(DesktopPreviewChrome.NORMAL_WIDTH, DesktopPreviewChrome.NORMAL_HEIGHT);
    }

    private void awaitSize(double width, double height) throws Exception {
        awaitFx(() -> {
            root.applyCss();
            root.layout();
            return Math.abs(stage.getWidth() - width) < 1 && Math.abs(stage.getHeight() - height) < 1
                    && Math.abs(root.getWidth() - width) < 1 && Math.abs(root.getHeight() - height) < 1;
        });
    }

    private void assertImageFitsViewport() throws Exception {
        runFx(() -> {
            ImageView image = image();
            assertTrue(image.isPreserveRatio());
            assertEquals(1.6, image.getBoundsInLocal().getWidth() / image.getBoundsInLocal().getHeight(), 0.001);
            assertTrue(image.getBoundsInLocal().getWidth() <= image.getFitWidth() + 1);
            assertTrue(image.getBoundsInLocal().getHeight() <= image.getFitHeight() + 1);
            assertEquals(new PixelSize((int) Math.ceil(image.getFitWidth() * stage.getOutputScaleX()),
                    (int) Math.ceil(image.getFitHeight() * stage.getOutputScaleY())), requestedSize.get());
        });
    }

    private void assertBounds(Rectangle2D expected) throws Exception {
        Rectangle2D actual = callFx(this::bounds);
        assertEquals(expected.getMinX(), actual.getMinX(), 1);
        assertEquals(expected.getMinY(), actual.getMinY(), 1);
        assertEquals(expected.getWidth(), actual.getWidth(), 1);
        assertEquals(expected.getHeight(), actual.getHeight(), 1);
    }

    private Rectangle2D bounds() {
        return new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    private Button button(String id) { return (Button) root.lookup("#" + id); }
    private Label label(String id) { return (Label) root.lookup("#" + id); }
    private javafx.scene.Node node(String id) { return root.lookup("#" + id); }
    private ImageView image() { return (ImageView) node("image"); }
    private Region card() { return (Region) root.lookup(".desktop-preview-card"); }

    private void snapshot(String name) throws Exception {
        WritableImage pixels = callFx(() -> root.snapshot(null, null));
        int width = (int) pixels.getWidth();
        int height = (int) pixels.getHeight();
        int[] argb = new int[width * height];
        pixels.getPixelReader().getPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), argb, 0, width);
        BufferedImage rendered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        rendered.setRGB(0, 0, width, height, argb, 0, width);
        Files.createDirectories(SNAPSHOTS);
        assertTrue(ImageIO.write(rendered, "png", SNAPSHOTS.resolve(name).toFile()));
    }

    private static WritableImage fixtureImage(String caption) {
        BufferedImage fixture = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = fixture.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new java.awt.Color(248, 249, 252));
            graphics.fillRect(0, 0, 1280, 800);
            graphics.setColor(new java.awt.Color(235, 238, 245));
            graphics.fillRect(0, 0, 245, 800);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
            graphics.setColor(new java.awt.Color(43, 48, 62));
            graphics.drawString("LM Studio", 28, 55);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
            for (int row = 0; row < 5; row++) {
                graphics.drawString(new String[] {"Chat", "Developer", "My Models", "Discover", "Settings"}[row],
                        28, 130 + row * 62);
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 31));
            graphics.drawString(caption, 286, 64);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            graphics.setColor(new java.awt.Color(105, 114, 132));
            graphics.drawString("Models downloaded on this computer", 288, 105);
            String[] models = {"qwen3.5-9b", "llama-3.3-70b-instruct", "deepseek-r1-8b"};
            for (int row = 0; row < models.length; row++) {
                int y = 160 + row * 170;
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRoundRect(285, y, 944, 136, 18, 18);
                graphics.setColor(new java.awt.Color(52, 87, 184));
                graphics.fillRoundRect(310, y + 28, 66, 66, 12, 12);
                graphics.setColor(new java.awt.Color(43, 48, 62));
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
                graphics.drawString(models[row], 400, y + 48);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
                graphics.setColor(new java.awt.Color(105, 114, 132));
                graphics.drawString("GGUF   Q4_K_M   Ready to load", 400, y + 85);
            }
        } finally {
            graphics.dispose();
        }
        int[] argb = fixture.getRGB(0, 0, 1280, 800, null, 0, 1280);
        WritableImage image = new WritableImage(1280, 800);
        image.getPixelWriter().setPixels(0, 0, 1280, 800, PixelFormat.getIntArgbInstance(), argb, 0, 1280);
        return image;
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

    private record PixelSize(int width, int height) {}

}
