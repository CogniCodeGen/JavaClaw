package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.api.DesktopVirtualInputState;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in native display checks; no JavaFX/EDT synchronous cross calls. */
@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的原生桌面显示服务")
class JavaDesktopPreviewHostTest {
    private JavaDesktopPreviewHost host;
    private JFrame focusedApp;

    @AfterEach
    void closeWindows() throws Exception {
        edt(() -> {
            if (host != null) host.close();
            if (focusedApp != null) focusedApp.dispose();
            return null;
        });
    }

    @Test
    void showFramesAndModeChangesKeepTheExistingAppKeyboardFocusAndSystemPointer() throws Exception {
        JTextField field = edt(() -> {
            focusedApp = new JFrame("preview focus test");
            JTextField text = new JTextField("Typing remains in this application");
            focusedApp.add(text);
            focusedApp.setBounds(40, 40, 340, 90);
            focusedApp.setVisible(true);
            text.requestFocusInWindow();
            return text;
        });
        // The native peer must be visible before a focus-in-window request can succeed.
        Thread.sleep(100);
        edt(() -> {
            focusedApp.toFront();
            focusedApp.requestFocus();
            field.requestFocusInWindow();
            return null;
        });
        await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == field);
        Point originalPointer = MouseInfo.getPointerInfo().getLocation();
        edt(() -> {
            host = new JavaDesktopPreviewHost("App", "Window", 0, () -> { }, () -> { }, () -> { });
            host.show();
            DesktopFrame frame = frame(1, 400, 240, true);
            host.frame(frame, DesktopPreviewImage.convert(frame));
            assertFalse(host.window().getFocusableWindowState());
            assertFalse(host.window().isAutoRequestFocus());
            assertTrue(host.window().isAlwaysOnTop());
            host.toggleMini(); host.toggleMaximized(); host.restore();
            host.hide(); host.show();
            return null;
        });
        // Let native focus notifications drain as well as the Java EDT queue.
        Thread.sleep(150);
        assertSame(field, edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        assertEquals(originalPointer, MouseInfo.getPointerInfo().getLocation());
    }

    @Test
    void restorationUsesLatestFrameLogicalSizeAndPointerNeverUsesAnotherGeneration() throws Exception {
        edt(() -> {
            host = new JavaDesktopPreviewHost("App", "Window", 0, () -> { }, () -> { }, () -> { });
            host.show();
            DesktopFrame initial = frame(1, 400, 240, true);
            host.frame(initial, DesktopPreviewImage.convert(initial));
            assertEquals(400, host.transform().width(), 1);
            assertEquals(240, host.transform().height(), 1);
            host.toggleMini();
            DesktopFrame resized = frame(2, 600, 300, true);
            host.frame(resized, DesktopPreviewImage.convert(resized));
            assertEquals(JavaDesktopPreviewHost.Mode.MINI, host.mode());
            host.restore();
            assertEquals(600, host.transform().logicalWidth());
            assertEquals(300, host.transform().logicalHeight());
            assertTrue(host.transform().width() <= 600);
            assertEquals(2, host.transform().width() / host.transform().height(), 0.001);
            BufferedImage baseline = paint();
            host.input(new DesktopVirtualInputState("session", 1, 600, 300, true, 1,
                    DesktopVirtualInputState.Phase.PRESSED, 1));
            assertEquals(signature(baseline), signature(paint()));
            host.input(new DesktopVirtualInputState("session", 2, 600, 300, true, 1,
                    DesktopVirtualInputState.Phase.PRESSED, 2));
            assertTrue(signature(baseline) != signature(paint()));
            if (host.window().getBackground().getAlpha() == 0) {
                DesktopViewportTransform mapping = host.transform();
                BufferedImage rendered = paint();
                assertEquals(0, rendered.getRGB((int) mapping.x() + 1, (int) mapping.y() + 1) >>> 24,
                        "reliable transparent source corner must remain transparent");
            }
            return null;
        });
    }

    private BufferedImage paint() {
        BufferedImage image = new BufferedImage(host.window().getWidth(), host.window().getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { host.window().getContentPane().paint(graphics); }
        finally { graphics.dispose(); }
        return image;
    }

    private static long signature(BufferedImage image) {
        long result = 1;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) result = 31 * result + image.getRGB(x, y);
        return result;
    }

    private static DesktopFrame frame(long generation, int logicalWidth, int logicalHeight, boolean alpha) {
        int width = logicalWidth * 2;
        int height = logicalHeight * 2;
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int index = (y * width + x) * 4;
            if (x < 12 && y < 12) continue;
            pixels[index] = 40; pixels[index + 1] = 80;
            pixels[index + 2] = 120; pixels[index + 3] = (byte) 255;
        }
        return new DesktopFrame("target", generation, generation, width, height, width * 4, pixels,
                generation, new DesktopFrameGeometry(logicalWidth, logicalHeight, 0, 0, width, height, alpha));
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do { if (edt(condition)) return; Thread.sleep(20); } while (System.nanoTime() < until);
        assertTrue(edt(condition), "native focus did not settle");
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task);
        EventQueue.invokeLater(result);
        return result.get(5, TimeUnit.SECONDS);
    }
}
