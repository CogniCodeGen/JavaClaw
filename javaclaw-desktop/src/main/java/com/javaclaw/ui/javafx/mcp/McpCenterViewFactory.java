package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;

/** 创建嵌入设置窗口或独立窗口的 MCP FXML 中心。 */
public final class McpCenterViewFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpCenterViewFactory.class.getResource("/fxml/mcp/mcp-center.fxml"),
            "缺少 mcp-center.fxml");
    private static final List<String> STYLES = List.of("/css/chat.css", "/css/mcp-settings.css");
    private final SpringFxmlLoader loader;

    public McpCenterViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public McpCenterView createPanel(Runnable onConfigurationChanged) {
        ViewHandle<HBox> handle = load();
        handle.controller(McpCenterController.class).configure(false, onConfigurationChanged);
        attachStyles(handle.root());
        return new McpCenterView(handle, null);
    }

    public McpCenterView createWindow(Window owner, Runnable onConfigurationChanged) {
        ViewHandle<HBox> handle = load();
        try {
            handle.controller(McpCenterController.class).configure(true, onConfigurationChanged);
            Stage stage = new Stage();
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("MCP 服务器");
            stage.setResizable(true);
            stage.setMinWidth(780);
            stage.setMinHeight(560);
            Scene scene = new Scene(handle.root(), 960, 680);
            addStyles(scene);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
            stage.setScene(scene);
            return new McpCenterView(handle, stage);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<HBox> load() {
        try { return loader.load(VIEW); }
        catch (IOException failure) { throw new UncheckedIOException("加载 MCP 中心失败", failure); }
    }

    private static void attachStyles(HBox root) {
        root.sceneProperty().addListener((ignored, previous, scene) -> {
            if (scene != null) addStyles(scene);
        });
        if (root.getScene() != null) addStyles(root.getScene());
    }

    private static void addStyles(Scene scene) {
        for (String path : STYLES) {
            URL css = McpCenterViewFactory.class.getResource(path);
            if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
                scene.getStylesheets().add(css.toExternalForm());
            }
        }
    }
}
