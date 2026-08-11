package com.javaclaw.ui.javafx.control;

import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
class ToggleSwitchFxmlTest {

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
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @Test
    void loadsFxmlAndTracksState() throws Exception {
        ToggleSwitch toggle = callFx(() -> new ToggleSwitch(true));

        StackPane track = callFx(() -> (StackPane) toggle.lookup(".jc-switch"));
        assertNotNull(track);
        assertNotNull(callFx(() -> toggle.lookup(".jc-switch-thumb")));
        assertTrue(callFx(toggle::isSelected));
        assertTrue(callFx(() -> track.getStyleClass().contains("jc-switch-on")));
    }

    @Test
    void mouseAndKeyboardToggleUnlessDisabled() throws Exception {
        ToggleSwitch toggle = callFx(ToggleSwitch::new);
        StackPane track = callFx(() -> (StackPane) toggle.lookup(".jc-switch"));

        runFx(() -> track.fireEvent(mouseClick()));
        assertTrue(callFx(toggle::isSelected));
        runFx(() -> toggle.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE,
                false, false, false, false)));
        assertFalse(callFx(toggle::isSelected));

        runFx(() -> {
            toggle.setDisable(true);
            track.fireEvent(mouseClick());
        });
        assertFalse(callFx(toggle::isSelected));
    }

    @Test
    void keepsOriginalDimensions() throws Exception {
        ToggleSwitch toggle = callFx(ToggleSwitch::new);

        assertEquals(38.0, callFx(() -> toggle.prefWidth(-1)));
        assertEquals(22.0, callFx(() -> toggle.prefHeight(-1)));
    }

    private static MouseEvent mouseClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false,
                true, false, false, true, false, false, null);
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                completed.countDown();
            }
        });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
