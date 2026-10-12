package com.javaclaw.ui.javafx.skill;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
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

/** 从工作区 Spring Context 创建可释放的技能中心窗口。 */
public final class SkillCenterViewFactory {

    private static final URL VIEW = Objects.requireNonNull(
            SkillCenterViewFactory.class.getResource("/fxml/skill/skill-center.fxml"),
            "缺少 skill-center.fxml");
    private static final List<String> STYLES =
            List.of("/css/chat.css", "/css/controls.css", "/css/skill-center.css");
    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;

    public SkillCenterViewFactory(SpringFxmlLoader loader, FxDispatcher fx) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    public SkillCenterView create(Window owner) {
        ViewHandle<StackPane> handle = load();
        try {
            SkillCenterController controller = handle.controller(SkillCenterController.class);
            Stage stage = new Stage();
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle("技能中心");
            stage.setResizable(true);
            Scene scene = new Scene(handle.root(), 920, 650);
            addStyles(scene, owner);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
            stage.setScene(scene);
            return new SkillCenterView(stage, handle, controller, fx);
        } catch (RuntimeException | Error failure) {
            handle.close();
            throw failure;
        }
    }

    private ViewHandle<StackPane> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载技能中心失败", failure);
        }
    }

    private static void addStyles(Scene scene, Window owner) {
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        for (String path : STYLES) {
            URL css = SkillCenterViewFactory.class.getResource(path);
            if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
                scene.getStylesheets().add(css.toExternalForm());
            }
        }
    }
}
