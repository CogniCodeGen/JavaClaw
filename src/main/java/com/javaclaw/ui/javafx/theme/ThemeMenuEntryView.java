package com.javaclaw.ui.javafx.theme;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.MenuItem;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个由 FXML 定义的主题菜单项及其幂等生命周期句柄。 */
final class ThemeMenuEntryView implements AutoCloseable {

    private final ViewHandle<MenuItem> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    ThemeMenuEntryView(ViewHandle<MenuItem> handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    MenuItem root() { return handle.root(); }

    ThemeMenuEntryController controller() {
        return handle.controller(ThemeMenuEntryController.class);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
