package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个插件中心窗口及其完整 FXML Controller 树生命周期。
 *
 * <p>只能在 FX 线程显示或关闭。隐藏窗口会取消页面任务、释放目录订阅，并反序销毁所有
 * 动态卡片和嵌套 Controller；关闭幂等。</p>
 */
public final class PluginCenterView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<HBox> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    PluginCenterView(Stage stage, ViewHandle<HBox> handle) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        stage.setOnHidden(event -> close());
    }

    public void show() {
        ensureOpen();
        stage.show();
    }

    public void showAndWait() {
        ensureOpen();
        stage.showAndWait();
    }

    public PluginCenterView openServicePluginConfiguration(String pluginId, String pageId) {
        ensureOpen();
        controller().openServicePluginConfiguration(pluginId, pageId);
        return this;
    }

    public Stage stage() { return stage; }

    PluginCenterController controller() {
        return handle.controller(PluginCenterController.class);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("插件中心窗口已关闭");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
