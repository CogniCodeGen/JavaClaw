package com.javaclaw.ui.javafx.workflow;

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

/** 创建并释放 FXML 工作流测试输入弹窗。 */
public final class WorkflowInputDialogFactory {

    private static final URL VIEW = Objects.requireNonNull(
            WorkflowInputDialogFactory.class.getResource(
                    "/fxml/workflow/workflow-input-dialog.fxml"),
            "缺少 workflow-input-dialog.fxml");
    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;

    public WorkflowInputDialogFactory(SpringFxmlLoader loader, FxDispatcher fx) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    Optional<String> show(Window owner) {
        try (ViewHandle<VBox> handle = loader.load(VIEW)) {
            WorkflowInputDialogController controller =
                    handle.controller(WorkflowInputDialogController.class);
            Dialog<ButtonType> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("测试运行");
            dialog.setHeaderText("输入工作流的 input 状态");
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            UIHelper.styleDialog(dialog);
            dialog.setOnShown(event -> fx.dispatchLater(controller::requestFocus));
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return Optional.empty();
            }
            return Optional.of(controller.input());
        } catch (IOException failure) {
            throw new UncheckedIOException("加载工作流测试输入弹窗失败", failure);
        }
    }
}
