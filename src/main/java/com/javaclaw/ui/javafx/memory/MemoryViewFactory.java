package com.javaclaw.ui.javafx.memory;

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

/** 从工作区 Spring Context 创建可释放的记忆中心窗口。 */
public final class MemoryViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            MemoryViewFactory.class.getResource("/fxml/memory/memory-view.fxml"),
            "缺少 memory-view.fxml");
    private static final List<String> STYLES =
            List.of("/css/chat.css", "/css/controls.css", "/css/memory-center.css");
    private final SpringFxmlLoader loader;

    public MemoryViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public MemoryView create(Window owner) {
        ViewHandle<StackPane> handle = load();
        try {
            MemoryViewController controller = handle.controller(MemoryViewController.class);
            Stage stage = new Stage();
            stage.initModality(Modality.NONE);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("记忆中心");
            Scene scene = new Scene(handle.root(), 1000, 680);
            addStyles(scene, owner);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
            stage.setScene(scene);
            return new MemoryView(stage, handle, controller);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<StackPane> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载记忆中心失败", failure);
        }
    }

    private static void addStyles(Scene scene, Window owner) {
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        for (String path : STYLES) {
            URL css = MemoryViewFactory.class.getResource(path);
            if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
                scene.getStylesheets().add(css.toExternalForm());
            }
        }
    }
}
