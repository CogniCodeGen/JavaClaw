package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;

/** Loads the knowledge center from the current workspace Spring context. */
public final class KnowledgeCenterViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            KnowledgeCenterViewFactory.class.getResource(
                    "/fxml/knowledge/knowledge-center.fxml"),
            "缺少 knowledge-center.fxml");
    private static final List<String> STYLES = List.of(
            "/css/chat.css", "/css/controls.css", "/css/knowledge-center.css");
    private final SpringFxmlLoader loader;

    public KnowledgeCenterViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public KnowledgeCenterView create(
            Window owner,
            Runnable onConfigChanged,
            Runnable onOpenModelSettings) {
        ViewHandle<StackPane> handle = load();
        try {
            KnowledgeCenterController controller =
                    handle.controller(KnowledgeCenterController.class);
            Stage stage = new Stage();
            if (owner != null) stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("知识库中心");
            stage.setResizable(true);
            stage.setMinWidth(1040);
            stage.setMinHeight(680);
            Scene scene = new Scene(handle.root(), 1340, 864);
            for (String style : STYLES) addStyle(scene, style);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
            stage.setScene(scene);
            return new KnowledgeCenterView(stage, handle, controller,
                    onConfigChanged, onOpenModelSettings);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<StackPane> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载知识库中心失败", failure);
        }
    }

    private static void addStyle(Scene scene, String path) {
        URL resource = KnowledgeCenterViewFactory.class.getResource(path);
        if (resource != null) scene.getStylesheets().add(resource.toExternalForm());
    }
}
