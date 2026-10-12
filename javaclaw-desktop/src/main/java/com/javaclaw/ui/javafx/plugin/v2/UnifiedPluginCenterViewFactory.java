package com.javaclaw.ui.javafx.plugin.v2;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** 创建统一插件中心；全部布局由宿主自己的 FXML 定义。 */
public final class UnifiedPluginCenterViewFactory {
    private final SpringFxmlLoader loader;
    public UnifiedPluginCenterViewFactory(SpringFxmlLoader loader) { this.loader = Objects.requireNonNull(loader); }

    public UnifiedPluginCenterView create(Window owner) { return create(owner, () -> { }); }
    public UnifiedPluginCenterView create(Window owner, Runnable runtimeConfigurationChanged) {
        ViewHandle<VBox> handle;
        try {
            handle = loader.load(Objects.requireNonNull(getClass().getResource("/fxml/plugin/v2/unified-plugin-center.fxml")));
        } catch (IOException failure) { throw new UncheckedIOException("加载统一插件中心失败", failure); }
        try {
            Stage stage = new Stage();
            if (owner != null) stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("插件中心");
            stage.setMinWidth(900); stage.setMinHeight(620);
            Scene scene = new Scene(handle.root(), 1100, 760);
            for (String stylesheet : List.of("/css/chat.css", "/css/controls.css", "/css/plugins.css")) {
                var resource = getClass().getResource(stylesheet);
                if (resource != null) scene.getStylesheets().add(resource.toExternalForm());
            }
            stage.setScene(scene);
            handle.controller(UnifiedPluginCenterController.class).configure(runtimeConfigurationChanged);
            return new UnifiedPluginCenterView(stage, handle);
        } catch (RuntimeException | Error failure) {
            try { handle.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
