package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;

/** 创建插件中心模态窗口；每次创建都有独立 prototype Controller 树。 */
public final class PluginCenterViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            PluginCenterViewFactory.class.getResource("/fxml/plugin/plugin-center.fxml"),
            "缺少 plugin-center.fxml");
    private static final List<String> STYLES =
            List.of("/css/chat.css", "/css/controls.css", "/css/plugins.css");

    private final SpringFxmlLoader loader;

    public PluginCenterViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** 必须在 FX 线程调用；创建失败时立即销毁已创建的 Controller。 */
    public PluginCenterView create(Window owner) {
        ViewHandle<HBox> handle;
        try {
            handle = loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载插件中心 FXML 失败", failure);
        }
        try {
            Stage stage = new Stage();
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("插件中心");
            stage.setResizable(true);
            Scene scene = new Scene(handle.root(), 960, 680);
            for (String path : STYLES) {
                URL css = PluginCenterViewFactory.class.getResource(path);
                if (css != null) scene.getStylesheets().add(css.toExternalForm());
            }
            stage.setScene(scene);
            return new PluginCenterView(stage, handle);
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
