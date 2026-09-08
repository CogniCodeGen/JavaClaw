package com.javaclaw.desktop.settings;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.javaclaw.api.ProviderRef;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 已配置模型的使用动作；缺工作区与失败均在同一窗口内恢复。 */
final class ProviderModelUseDialog {
    private final CoreSettingsGateway gateway;
    private final ProviderRef model;
    private final Runnable completed;
    private final Dialog<Void> dialog = new Dialog<>();
    private final ProviderSetupWorkspacePicker picker;
    private final Label status = new Label("将模型用于目标对话，并记为新对话的默认模型。");
    private final Button use;

    private ProviderModelUseDialog(
            Window owner,
            CoreSettingsGateway gateway,
            ProviderSetupTarget target,
            ProviderRef model,
            Runnable completed) {
        this.gateway = gateway;
        this.model = model;
        this.completed = completed;
        picker = new ProviderSetupWorkspacePicker(owner, gateway, target);
        dialog.setTitle("使用此模型");
        dialog.setHeaderText(model.model());
        status.setWrapText(true);
        VBox body = new VBox(12, picker, status);
        body.setPrefWidth(500);
        dialog.getDialogPane().setContent(body);
        ButtonType useType = new ButtonType("使用此模型", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, useType);
        use = (Button) dialog.getDialogPane().lookupButton(useType);
        picker.onTargetChanged(next -> use.setText(label(next)));
        use.setText(label(target));
        use.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            apply();
        });
        dialog.setOnCloseRequest(event -> {
            if (use.isDisabled() || picker.pending()) {
                event.consume();
            }
        });
        PlatformDialogs.style(dialog, owner);
    }

    static void show(
            Window owner,
            CoreSettingsGateway gateway,
            ProviderSetupTarget target,
            ProviderRef model,
            Runnable completed) {
        ProviderModelUseDialog action = new ProviderModelUseDialog(owner, gateway, target, model, completed);
        action.dialog.show();
        if (target.workspaceId().isPresent()) {
            action.use.fire();
        }
    }

    private void apply() {
        ProviderSetupTarget fixed = picker.target();
        if (fixed.workspaceId().isEmpty()) {
            status.setText("请选择或创建工作区，已配置模型会保留。");
            return;
        }
        use.setDisable(true);
        picker.setDisable(true);
        status.setText("正在应用模型…");
        gateway.useModel(fixed.workspaceId(), fixed.threadId(), model).whenComplete((ignored, failure) -> {
            use.setDisable(false);
            picker.setDisable(false);
            if (failure != null) {
                status.setText("模型已保存，应用失败，可重试：" + SettingsFailures.message(failure));
                return;
            }
            completed.run();
            dialog.close();
        });
    }

    private static String label(ProviderSetupTarget target) {
        return target.workspaceId().isPresent() ? "在「" + target.workspaceName() + "」中使用" : "使用此模型";
    }
}
