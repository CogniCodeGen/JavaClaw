package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

/** 创建并释放 FXML 条件出口弹窗。 */
public final class WorkflowConditionDialogFactory {

    private static final URL VIEW = Objects.requireNonNull(
            WorkflowConditionDialogFactory.class.getResource(
                    "/fxml/workflow/workflow-condition-dialog.fxml"),
            "缺少 workflow-condition-dialog.fxml");
    private final SpringFxmlLoader loader;

    public WorkflowConditionDialogFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Optional<WorkflowConditionDialogController.Selection> show(Window owner) {
        try (ViewHandle<GridPane> handle = loader.load(VIEW)) {
            Dialog<ButtonType> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("条件分支");
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            UIHelper.styleDialog(dialog);
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return Optional.empty();
            }
            return Optional.of(handle.controller(WorkflowConditionDialogController.class)
                    .selection());
        } catch (IOException failure) {
            throw new UncheckedIOException("加载条件出口弹窗失败", failure);
        }
    }
}
