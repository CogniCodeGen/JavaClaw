package com.javaclaw.ui.javafx.task;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个模态 SDD 托管任务窗口及其完整 FXML Controller 树生命周期。 */
public final class SddTaskView implements AutoCloseable {
    private final Stage stage;
    private final ViewHandle<?> handle;
    private final SddTaskController controller;
    private final AtomicBoolean closed = new AtomicBoolean();

    SddTaskView(Stage stage, ViewHandle<?> handle, SddTaskController controller) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
        controller.configure(stage::close);
        stage.setOnHidden(event -> close());
    }

    public void show() {
        ensureOpen();
        controller.prepare();
        stage.show();
        stage.toFront();
    }

    public void showCreate() { showCreate(null); }

    public void showCreate(String description) {
        show();
        controller.openCreate(description);
    }

    public void show(String taskId) {
        show();
        controller.showTask(taskId);
    }

    HBox root() { return (HBox) handle.root(); }

    SddTaskController controller() { return controller; }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("SDD 任务窗口已关闭");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
