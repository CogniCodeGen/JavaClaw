package com.javaclaw.ui.javafx.memory;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

/** 一个动态 FXML 片段及其显式 Controller 生命周期。 */
final class MemoryChildView<T extends Node> implements AutoCloseable {
    private final ViewHandle<T> handle;

    MemoryChildView(ViewHandle<T> handle) { this.handle = handle; }
    T root() { return handle.root(); }
    <C> C controller(Class<C> type) { return handle.controller(type); }
    @Override public void close() { handle.close(); }
}
