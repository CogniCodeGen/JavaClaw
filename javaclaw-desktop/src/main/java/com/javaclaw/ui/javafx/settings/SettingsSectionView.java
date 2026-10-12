package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

import java.util.Objects;

/** 一个 FXML 设置分区及其全部嵌套 Controller 的统一生命周期句柄。 */
public final class SettingsSectionView<C> implements AutoCloseable {

    private final ViewHandle<? extends Node> handle;
    private final C controller;

    SettingsSectionView(ViewHandle<? extends Node> handle, C controller) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    public Node root() {
        return handle.root();
    }

    public C controller() {
        return controller;
    }

    @Override
    public void close() {
        handle.close();
    }
}
