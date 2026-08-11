package com.javaclaw.ui.javafx.skill;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

/** 创建并释放新建脚本文件名 FXML 弹窗。 */
public final class SkillScriptNameDialogFactory {

    private static final URL VIEW = Objects.requireNonNull(
            SkillScriptNameDialogFactory.class.getResource(
                    "/fxml/skill/skill-script-name-dialog.fxml"),
            "缺少 skill-script-name-dialog.fxml");
    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;
    private final UIHelper ui;

    public SkillScriptNameDialogFactory(
            SpringFxmlLoader loader, FxDispatcher fx, UIHelper ui) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    Optional<String> show(Window owner) {
        try (ViewHandle<VBox> handle = loader.load(VIEW)) {
            SkillScriptNameDialogController controller =
                    handle.controller(SkillScriptNameDialogController.class);
            Dialog<ButtonType> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("新建脚本");
            dialog.setHeaderText("输入脚本文件名（.jsh 或 .java）");
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            ui.styleDialog(dialog);
            dialog.setOnShown(event -> fx.dispatchLater(controller::requestFocus));
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return Optional.empty();
            }
            return Optional.of(controller.name());
        } catch (IOException failure) {
            throw new UncheckedIOException("加载新建脚本弹窗失败", failure);
        }
    }
}
