package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

import java.util.Objects;

/** 动态插件 FXML 片段与其 prototype Controller 生命周期。 */
final class PluginChildView<T extends Node> implements AutoCloseable {

    private final ViewHandle<T> handle;

    PluginChildView(ViewHandle<T> handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    T root() { return handle.root(); }

    @Override
    public void close() { handle.close(); }
}
