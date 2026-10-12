package com.javaclaw.chat;

import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Coordinates chat-window shortcuts and responsive sidebar state.
 *
 * <p>This controller owns every listener and accelerator it installs. {@link #close()} removes them,
 * so reloading the FXML does not retain the old page graph.</p>
 */
final class ChatShellController implements AutoCloseable {

    private static final double RESPONSIVE_BREAKPOINT_PX = 960;

    private final BorderPane root;
    private final Node sidebar;
    private final ChatHeaderController header;
    private final ChatComposerController composer;
    private final ChatShortcutHelpFactory shortcutHelp;
    private final BooleanSupplier streaming;
    private final Runnable stopStream;
    private final Map<KeyCombination, Runnable> accelerators = new LinkedHashMap<>();
    private final ChangeListener<Scene> sceneListener = this::sceneChanged;
    private final ChangeListener<Number> widthListener =
            (observable, oldWidth, newWidth) -> applyResponsiveSidebar(newWidth.doubleValue());
    private final EventHandler<KeyEvent> escapeHandler = this::handleEscape;

    private Scene attachedScene;
    private boolean sidebarAutoHidden;
    private boolean closed;

    ChatShellController(
            BorderPane root,
            Node sidebar,
            ChatHeaderController header,
            ChatComposerController composer,
            ChatShortcutHelpFactory shortcutHelp,
            BooleanSupplier streaming,
            Runnable stopStream,
            Runnable newSession,
            Runnable openSettings,
            Runnable clearHistory,
            Runnable openMcp) {
        this.root = Objects.requireNonNull(root, "root");
        this.sidebar = Objects.requireNonNull(sidebar, "sidebar");
        this.header = Objects.requireNonNull(header, "header");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.shortcutHelp = Objects.requireNonNull(shortcutHelp, "shortcutHelp");
        this.streaming = Objects.requireNonNull(streaming, "streaming");
        this.stopStream = Objects.requireNonNull(stopStream, "stopStream");
        register(KeyCode.N, newSession);
        register(KeyCode.COMMA, openSettings);
        register(KeyCode.L, clearHistory);
        register(KeyCode.BACK_SLASH, this::toggleSidebar);
        register(KeyCode.K, composer::focusInput);
        register(KeyCode.M, openMcp);
        accelerators.put(shortcut(KeyCode.SLASH), this::showShortcutHelp);
        accelerators.put(new KeyCodeCombination(
                KeyCode.SLASH, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::showShortcutHelp);
    }

    void install() {
        root.sceneProperty().addListener(sceneListener);
        attach(root.getScene());
    }

    void toggleSidebar() {
        boolean visible = sidebar.isVisible();
        sidebar.setVisible(!visible);
        sidebar.setManaged(!visible);
        header.setSidebarToggleVisible(visible);
    }

    String shortcutHint() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
                ? "⌘" : "Ctrl";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        root.sceneProperty().removeListener(sceneListener);
        detach(attachedScene);
    }

    private void sceneChanged(javafx.beans.value.ObservableValue<? extends Scene> observable,
                              Scene previous,
                              Scene current) {
        detach(previous);
        attach(current);
    }

    private void attach(Scene scene) {
        if (scene == null || closed) {
            return;
        }
        attachedScene = scene;
        scene.addEventFilter(KeyEvent.KEY_PRESSED, escapeHandler);
        accelerators.forEach(scene.getAccelerators()::put);
        scene.widthProperty().addListener(widthListener);
        applyResponsiveSidebar(scene.getWidth());
    }

    private void detach(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.removeEventFilter(KeyEvent.KEY_PRESSED, escapeHandler);
        scene.widthProperty().removeListener(widthListener);
        accelerators.forEach((key, action) -> {
            if (scene.getAccelerators().get(key) == action) {
                scene.getAccelerators().remove(key);
            }
        });
        if (attachedScene == scene) {
            attachedScene = null;
        }
    }

    private void handleEscape(KeyEvent event) {
        if (event.getCode() != KeyCode.ESCAPE) {
            return;
        }
        if (composer.inputLength() > 0) {
            composer.clearInput();
            event.consume();
        } else if (streaming.getAsBoolean()) {
            stopStream.run();
            event.consume();
        }
    }

    private void applyResponsiveSidebar(double width) {
        if (width < RESPONSIVE_BREAKPOINT_PX && sidebar.isVisible()) {
            sidebar.setVisible(false);
            sidebar.setManaged(false);
            header.setSidebarToggleVisible(true);
            sidebarAutoHidden = true;
        } else if (width >= RESPONSIVE_BREAKPOINT_PX
                && sidebarAutoHidden && !sidebar.isVisible()) {
            sidebar.setVisible(true);
            sidebar.setManaged(true);
            header.setSidebarToggleVisible(false);
            sidebarAutoHidden = false;
        }
    }

    private void showShortcutHelp() {
        shortcutHelp.show(root.getScene() == null ? null : root.getScene().getWindow(), shortcutHint());
    }

    private void register(KeyCode key, Runnable action) {
        accelerators.put(shortcut(key), Objects.requireNonNull(action, "action"));
    }

    private static KeyCodeCombination shortcut(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }
}
