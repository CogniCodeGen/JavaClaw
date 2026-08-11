package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个模态设置窗口及其完整 FXML Controller 树生命周期。 */
public final class SettingsView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<?> handle;
    private final SettingsViewController controller;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private boolean closing;

    SettingsView(Stage stage, ViewHandle<?> handle, SettingsViewController controller) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
        controller.configure(this::closeWindow, null);
        stage.setOnCloseRequest(event -> {
            if (!closing) {
                event.consume();
                controller.requestClose();
            }
        });
        stage.setOnHidden(event -> close());
    }

    /** 模型或运行时配置生效后的通知；回调在 JavaFX Application Thread 执行。 */
    public void setOnModelConfigChanged(Runnable callback) {
        ensureOpen();
        controller.configure(this::closeWindow, callback);
    }

    public void show() {
        show(null);
    }

    /** 显示窗口并按中文分类名直达；未知分类回落到模型配置。 */
    public void show(String categoryName) {
        ensureOpen();
        controller.prepare(categoryName);
        stage.showAndWait();
    }

    private void closeWindow() {
        if (closed.get()) return;
        closing = true;
        stage.close();
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("设置窗口已关闭");
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
