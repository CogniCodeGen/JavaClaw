package com.javaclaw.ui.javafx.memory;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个非模态记忆中心窗口及其完整 FXML Controller 树生命周期。 */
public final class MemoryView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<StackPane> handle;
    private final MemoryViewController controller;
    private final AtomicBoolean closed = new AtomicBoolean();

    MemoryView(Stage stage, ViewHandle<StackPane> handle, MemoryViewController controller) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
        controller.configure(stage::close, stage);
        stage.setOnHidden(event -> close());
    }

    public void show() {
        if (closed.get()) throw new IllegalStateException("记忆中心窗口已关闭");
        controller.prepare();
        stage.show();
        stage.toFront();
    }

    StackPane root() { return handle.root(); }
    MemoryViewController controller() { return controller; }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
