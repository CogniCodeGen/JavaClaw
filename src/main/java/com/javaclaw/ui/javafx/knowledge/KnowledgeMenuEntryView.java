package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.MenuItem;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个动态知识库菜单项及其 FXML 生命周期。 */
final class KnowledgeMenuEntryView implements AutoCloseable {

    private final MenuItem root;
    private final ViewHandle<?> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    KnowledgeMenuEntryView(MenuItem root, ViewHandle<?> handle) {
        this.root = Objects.requireNonNull(root, "root");
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    MenuItem root() { return root; }

    <C> C controller(Class<C> type) { return handle.controller(type); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
