package com.javaclaw.ui.javafx.theme;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.MenuItem;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/** 创建 FXML 主题菜单项；加载失败不会遗留 prototype Controller。 */
public final class ThemeMenuEntryFactory {

    private static final URL TEMPLATE = Objects.requireNonNull(
            ThemeMenuEntryFactory.class.getResource("/fxml/chat/theme-menu-entry.fxml"),
            "缺少主题菜单项 FXML");

    private final SpringFxmlLoader loader;

    public ThemeMenuEntryFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    ThemeMenuEntryView create(ThemeOption option, boolean selected, Runnable selection) {
        ViewHandle<MenuItem> handle;
        try {
            handle = loader.load(TEMPLATE);
        } catch (IOException failure) {
            throw new IllegalStateException("加载主题菜单项 FXML 失败", failure);
        }
        try {
            ThemeMenuEntryController controller =
                    handle.controller(ThemeMenuEntryController.class);
            controller.configure(option, selected, selection);
            return new ThemeMenuEntryView(handle);
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
