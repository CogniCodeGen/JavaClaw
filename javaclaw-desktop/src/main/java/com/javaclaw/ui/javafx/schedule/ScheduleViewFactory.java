package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;

/** 从工作区 Spring Context 创建可释放的定时任务窗口。 */
public final class ScheduleViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            ScheduleViewFactory.class.getResource("/fxml/schedule/schedule-view.fxml"),
            "缺少 schedule-view.fxml");
    private static final List<String> STYLES =
            List.of("/css/chat.css", "/css/controls.css", "/css/schedule.css");
    private final SpringFxmlLoader loader;

    public ScheduleViewFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public ScheduleView create(Window owner) {
        ViewHandle<HBox> handle = load();
        try {
            ScheduleViewController controller = handle.controller(ScheduleViewController.class);
            Stage stage = new Stage();
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("定时任务");
            stage.setResizable(true);
            stage.setMinWidth(780);
            stage.setMinHeight(560);
            Scene scene = new Scene(handle.root(), 960, 680);
            addStyles(scene);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE),
                    controller::requestClose);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S,
                    KeyCombination.SHORTCUT_DOWN), controller::saveRequested);
            stage.setScene(scene);
            return new ScheduleView(stage, handle, controller);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<HBox> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载定时任务窗口失败", failure);
        }
    }

    private static void addStyles(Scene scene) {
        for (String path : STYLES) {
            URL css = ScheduleViewFactory.class.getResource(path);
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
        }
    }
}
