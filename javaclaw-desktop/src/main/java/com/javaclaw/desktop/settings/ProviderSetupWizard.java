package com.javaclaw.desktop.settings;

import java.util.Objects;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.javaclaw.api.ProviderRef;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformDialogs;

/**
 * 在当前聊天或设置入口完成连接、选模型和应用的两步窗口。
 *
 * <p>入口工作区与对话固定；仅入口没有工作区时允许在窗口内补选。写步骤完成前禁止关闭，失败保留已保存阶段供重试。
 */
public final class ProviderSetupWizard {
    private final Dialog<Void> dialog = new Dialog<>();
    private final ProviderSetupWorkflow workflow;
    private final ProviderSetupConnectionForm connection = new ProviderSetupConnectionForm();
    private final ProviderSetupModelForm models;
    private final ProviderSetupWorkspacePicker workspace;
    private final VBox modelStep;
    private final Label status = new Label();
    private final Runnable completed;
    private final Button primary;
    private final Button secondary;
    private final Button back;
    private boolean modelStage;

    private ProviderSetupWizard(
            Window owner, CoreSettingsGateway gateway, ProviderSetupTarget target, Runnable completed) {
        this.completed = Objects.requireNonNull(completed, "completed");
        workflow = new ProviderSetupWorkflow(gateway);
        workflow.onProgress(status::setText);
        models = new ProviderSetupModelForm(new PlatformComponentFactory(), this::discover);
        workspace = new ProviderSetupWorkspacePicker(owner, gateway, target);
        modelStep = new VBox(12, models, workspace);
        modelStep.setVisible(false);
        modelStep.setManaged(false);
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        dialog.setTitle("添加模型");
        dialog.setHeaderText("① 连接服务 ───── ② 选择模型");
        VBox body = new VBox(12, connection, modelStep, status);
        body.setPrefWidth(580);
        dialog.getDialogPane().setContent(body);
        ButtonType primaryType = new ButtonType("继续：选择模型", ButtonData.OK_DONE);
        ButtonType saveType = new ButtonType("仅保存", ButtonData.OTHER);
        ButtonType backType = new ButtonType("上一步", ButtonData.BACK_PREVIOUS);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, backType, saveType, primaryType);
        primary = (Button) dialog.getDialogPane().lookupButton(primaryType);
        secondary = (Button) dialog.getDialogPane().lookupButton(saveType);
        back = (Button) dialog.getDialogPane().lookupButton(backType);
        bindEvents();
        PlatformDialogs.style(dialog, owner);
        updateButtons();
    }

    /**
     * 打开添加模型窗口；成功使用或仅保存后刷新调用页。
     *
     * @param owner 所属窗口；可空
     * @param gateway SDK 边界
     * @param target 入口固定目标
     * @param completed 完成时的页面刷新回调，不负责发消息
     */
    public static void show(Window owner, CoreSettingsGateway gateway, ProviderSetupTarget target, Runnable completed) {
        new ProviderSetupWizard(owner, gateway, target, completed).dialog.show();
    }

    /**
     * 将已经保存的精确模型用于指定目标；目标缺失时在原流程内选择或创建工作区。
     *
     * @param owner 所属窗口；可空
     * @param gateway SDK 边界
     * @param target 固定的入口作用域
     * @param model 已保存的精确模型引用
     * @param completed 使用完成时的页面刷新回调
     */
    public static void useModel(
            Window owner,
            CoreSettingsGateway gateway,
            ProviderSetupTarget target,
            ProviderRef model,
            Runnable completed) {
        ProviderModelUseDialog.show(owner, gateway, target, model, completed);
    }

    private void bindEvents() {
        primary.setId("providerWizardContinue");
        secondary.setId("providerWizardSaveOnly");
        primary.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (modelStage) {
                save(true);
            } else {
                connect();
            }
        });
        secondary.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            save(false);
        });
        back.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            showStep(false);
        });
        workspace.onTargetChanged(ignored -> updateButtons());
        dialog.setOnCloseRequest(event -> {
            if (workflow.pending() || workspace.pending()) {
                event.consume();
                status.setText("当前步骤正在完成，请稍候。");
            }
        });
        dialog.setOnHidden(event -> {
            connection.clearSecret();
            workflow.close();
        });
    }

    private void connect() {
        try {
            var operation = workflow.connect(connection.draft(), connection.secret());
            updateButtons();
            operation.whenComplete((ignored, failure) -> {
                workflow.endpoint()
                        .ifPresent(endpoint -> connection.connectionSaved(
                                endpoint.spec().credential().isPresent()));
                if (failure != null) {
                    fail(failure);
                    return;
                }
                showStep(true);
                discover();
            });
        } catch (RuntimeException invalid) {
            fail(invalid);
        }
    }

    private void discover() {
        var operation = workflow.discover();
        updateButtons();
        operation.whenComplete((result, failure) -> {
            if (failure != null) {
                status.setText("模型列表获取失败，可重试或直接手动输入模型 ID：" + SettingsFailures.message(failure));
            } else {
                models.candidates(result.candidates());
                status.setText(result.truncated() ? "已获取模型列表，结果已截断，可手动补充。" : "已获取模型列表。");
            }
            updateButtons();
        });
    }

    private void save(boolean use) {
        ProviderSetupTarget target = workspace.target();
        var operation = workflow.save(models.selectedModels(), models.currentModel(), use, target);
        updateButtons();
        operation.whenComplete((reference, failure) -> {
            if (failure != null) {
                fail(failure);
                return;
            }
            completed.run();
            dialog.close();
        });
    }

    private void showStep(boolean second) {
        modelStage = second;
        connection.setVisible(!second);
        connection.setManaged(!second);
        modelStep.setVisible(second);
        modelStep.setManaged(second);
        dialog.setHeaderText(second ? "✓ 连接服务 ───── ② 选择模型" : "① 连接服务 ───── ② 选择模型");
        updateButtons();
    }

    private void fail(Throwable failure) {
        status.setText(workflow.phase() + " 未完成：" + SettingsFailures.message(failure));
        updateButtons();
    }

    private void updateButtons() {
        boolean pending = workflow.pending();
        primary.setDisable(pending);
        secondary.setDisable(pending);
        back.setDisable(pending);
        connection.setDisable(pending);
        models.setDisable(pending);
        primary.setText(modelStage ? workspace.target().saveLabel() : "继续：选择模型");
        secondary.setVisible(modelStage);
        secondary.setManaged(modelStage);
        back.setVisible(modelStage);
        back.setManaged(modelStage);
    }
}
