package com.javaclaw.ui.javafx.control;

import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import javafx.event.EventHandler;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 一次 FXML Toast 装载结果；关闭时停止动画并解除交互端口接管。 */
public final class WindowToast implements AutoCloseable {

    private final ViewHandle<StackPane> handle;
    private final WindowToastController controller;
    private final Consumer<String> renderer = this::show;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Stage boundStage;
    private JfxUserInteractionPort boundPort;
    private Consumer<String> previousRenderer;
    private EventHandler<WindowEvent> shownHandler;
    private EventHandler<WindowEvent> hiddenHandler;

    WindowToast(ViewHandle<StackPane> handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
        controller = handle.controller(WindowToastController.class);
    }

    public Region node() { return handle.root(); }

    public void show(String text) {
        if (!closed.get()) controller.show(text);
    }

    /** 窗口显示期间接管统一 Toast 渲染器，隐藏或关闭时精确恢复前一个渲染器。 */
    public void bindToPort(Stage stage, UserInteractionPort interaction) {
        if (closed.get()) throw new IllegalStateException("WindowToast 已关闭");
        unbindPort();
        if (stage == null || !(interaction instanceof JfxUserInteractionPort jfx)) return;
        boundStage = stage;
        boundPort = jfx;
        shownHandler = event -> {
            previousRenderer = jfx.getToastHandler();
            jfx.setToastHandler(renderer);
        };
        hiddenHandler = event -> restoreRenderer();
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, shownHandler);
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, hiddenHandler);
        if (stage.isShowing()) shownHandler.handle(new WindowEvent(stage, WindowEvent.WINDOW_SHOWN));
    }

    private void restoreRenderer() {
        if (boundPort != null && boundPort.getToastHandler() == renderer) {
            boundPort.setToastHandler(previousRenderer);
        }
        previousRenderer = null;
    }

    private void unbindPort() {
        restoreRenderer();
        if (boundStage != null) {
            if (shownHandler != null) {
                boundStage.removeEventHandler(WindowEvent.WINDOW_SHOWN, shownHandler);
            }
            if (hiddenHandler != null) {
                boundStage.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hiddenHandler);
            }
        }
        boundStage = null;
        boundPort = null;
        shownHandler = null;
        hiddenHandler = null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        unbindPort();
        handle.close();
    }

    WindowToastController controller() { return controller; }
}
