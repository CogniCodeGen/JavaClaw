package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.stage.Stage;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个模态定时任务窗口及其完整 FXML Controller 树生命周期。 */
public final class ScheduleView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<?> handle;
    private final ScheduleViewController controller;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean closing;

    ScheduleView(Stage stage, ViewHandle<?> handle, ScheduleViewController controller) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
        controller.configure(this::closeWindow);
        stage.setOnCloseRequest(event -> {
            if (!closing) {
                event.consume();
                controller.requestClose();
            }
        });
        stage.setOnHidden(event -> close());
    }

    public void show() {
        ensureOpen();
        controller.prepare();
        stage.showAndWait();
    }

    HBox root() { return (HBox) handle.root(); }

    ScheduleViewController controller() { return controller; }

    private void closeWindow() {
        if (closed.get()) return;
        closing = true;
        stage.close();
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("定时任务窗口已关闭");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnCloseRequest(null);
        stage.setOnHidden(null);
        if (stage.isShowing()) {
            closing = true;
            stage.hide();
        }
        handle.close();
    }
}
