package com.javaclaw.ui.javafx.plugin.v2;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicBoolean;

/** 一个窗口拥有一组异步动作和订阅，隐藏时统一释放。 */
public final class UnifiedPluginCenterView implements AutoCloseable {
    private final Stage stage;
    private final ViewHandle<VBox> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    UnifiedPluginCenterView(Stage stage, ViewHandle<VBox> handle) {
        this.stage = stage; this.handle = handle;
        stage.setOnCloseRequest(event -> { event.consume(); controller().requestClose(this::close); });
        stage.setOnHidden(event -> close());
    }
    public void show() { ensureOpen(); stage.show(); }
    public void showAndWait() { ensureOpen(); stage.showAndWait(); }
    public Stage stage() { return stage; }
    public UnifiedPluginCenterController controller() { return handle.controller(UnifiedPluginCenterController.class); }
    public UnifiedPluginCenterView openPlugin(String pluginId, String pageId) {
        ensureOpen(); controller().openPlugin(pluginId, pageId); return this;
    }
    private void ensureOpen() { if (closed.get()) throw new IllegalStateException("插件中心已关闭"); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null); stage.setOnCloseRequest(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
