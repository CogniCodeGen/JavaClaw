package com.javaclaw.ui.javafx.task;

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

/** 从工作区 Spring Context 创建可释放的 SDD 任务窗口。 */
public final class SddTaskViewFactory {
    private static final URL VIEW = Objects.requireNonNull(
            SddTaskViewFactory.class.getResource("/fxml/task/sdd-task-view.fxml"),
            "缺少 sdd-task-view.fxml");
    private static final List<String> STYLES = List.of("/css/chat.css", "/css/sdd-task.css");
    private final SpringFxmlLoader loader;

    public SddTaskViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public SddTaskView create(Window owner) {
        ViewHandle<HBox> handle = load();
        try {
            SddTaskController controller = handle.controller(SddTaskController.class);
            Stage stage = new Stage();
            if (owner != null) stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("托管任务");
            stage.setMinWidth(860);
            stage.setMinHeight(600);
            Scene scene = new Scene(handle.root(), 1000, 700);
            for (String path : STYLES) addStyle(scene, path);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
            stage.setScene(scene);
            return new SddTaskView(stage, handle, controller);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<HBox> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 SDD 任务窗口失败", failure);
        }
    }

    private static void addStyle(Scene scene, String path) {
        URL css = SddTaskViewFactory.class.getResource(path);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
    }
}
