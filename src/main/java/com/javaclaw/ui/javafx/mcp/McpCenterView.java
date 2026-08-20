package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个嵌入面板或独立 MCP 窗口及其完整 Controller 树生命周期。 */
public final class McpCenterView implements AutoCloseable {
    private final ViewHandle<HBox> handle;
    private final Stage stage;
    private final AtomicBoolean closed = new AtomicBoolean();

    McpCenterView(ViewHandle<HBox> handle, Stage stage) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.stage = stage;
        if (stage != null) stage.setOnHidden(event -> close());
    }

    public HBox root() { return handle.root(); }

    public void activate() { controller().activate(); }

    public void deactivate() { controller().deactivate(); }

    public void show() {
        if (stage == null) throw new IllegalStateException("嵌入式 MCP 面板不能作为窗口显示");
        ensureOpen();
        activate();
        stage.show();
    }

    public void showAndWait() {
        if (stage == null) throw new IllegalStateException("嵌入式 MCP 面板不能作为窗口显示");
        ensureOpen();
        activate();
        stage.showAndWait();
    }

    McpCenterController controller() { return handle.controller(McpCenterController.class); }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("MCP 中心已关闭");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (stage != null) {
            stage.setOnHidden(null);
            if (stage.isShowing()) stage.hide();
        }
        handle.close();
    }
}
