package com.javaclaw.ui.javafx.diagnostics;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个诊断窗口及其 FXML Controller 生命周期。
 *
 * <p>只能在 FX 线程显示或关闭。窗口隐藏会自动关闭句柄并取消页面任务；关闭幂等。</p>
 */
public final class DiagnosticsView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<BorderPane> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    DiagnosticsView(Stage stage, ViewHandle<BorderPane> handle) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        stage.setOnHidden(event -> close());
    }

    public void show() {
        if (closed.get()) throw new IllegalStateException("诊断窗口已关闭");
        stage.show();
    }

    public Stage stage() { return stage; }

    DiagnosticsController controller() {
        return handle.controller(DiagnosticsController.class);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
