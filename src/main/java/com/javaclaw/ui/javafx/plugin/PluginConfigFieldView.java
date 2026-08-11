package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** 一个动态配置字段及其值读取和销毁边界。 */
final class PluginConfigFieldView implements AutoCloseable {

    private final ViewHandle<VBox> handle;
    private final PluginConfigFieldController controller;

    PluginConfigFieldView(
            ViewHandle<VBox> handle, PluginConfigFieldController controller) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    VBox root() { return handle.root(); }
    String key() { return controller.key(); }
    String value() { return controller.value(); }

    @Override
    public void close() { handle.close(); }
}
