package com.javaclaw.ui.javafx.control;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 为每个窗口创建独立、可关闭的 FXML Toast 浮层。 */
public final class WindowToastFactory {

    private static final URL VIEW = Objects.requireNonNull(
            WindowToastFactory.class.getResource("/fxml/control/window-toast.fxml"),
            "缺少 window-toast.fxml");

    private final SpringFxmlLoader loader;

    public WindowToastFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public WindowToast create() {
        try {
            ViewHandle<StackPane> handle = loader.load(VIEW);
            return new WindowToast(handle);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 Window Toast FXML 失败", failure);
        }
    }
}
