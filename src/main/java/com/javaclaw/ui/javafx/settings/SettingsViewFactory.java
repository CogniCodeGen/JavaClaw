package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
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

/** 从工作区 Spring Context 创建独立、可释放的设置窗口。 */
public final class SettingsViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            SettingsViewFactory.class.getResource("/fxml/settings/settings-view.fxml"),
            "缺少 settings-view.fxml");
    private static final List<String> STYLES = List.of("/css/chat.css", "/css/controls.css");

    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;

    public SettingsViewFactory(SpringFxmlLoader loader, FxDispatcher fx) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    public SettingsView create(Window owner) {
        ViewHandle<HBox> handle = load();
        try {
            SettingsViewController controller = handle.controller(SettingsViewController.class);
            Stage stage = new Stage();
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("设置");
            stage.setResizable(true);
            stage.setMinWidth(900);
            stage.setMinHeight(680);
            Scene scene = new Scene(handle.root(), 1100, 760);
            addStyles(scene);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE),
                    controller::requestClose);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S,
                            KeyCombination.SHORTCUT_DOWN),
                    controller::saveCurrentPanel);
            stage.setScene(scene);
            return new SettingsView(stage, handle, controller, fx);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<HBox> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载设置窗口失败", failure);
        }
    }

    private static void addStyles(Scene scene) {
        for (String path : STYLES) {
            URL css = SettingsViewFactory.class.getResource(path);
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
        }
    }
}
