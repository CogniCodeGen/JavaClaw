package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformDialogs;

/**
 * 新建、编辑和补全服务共用的两步配置窗口；最终一次保存完整配置，不改变聊天或工作区模型。
 *
 * <p>关闭、取消和 Esc 共用草稿保护；提交期间锁定窗口，结果不明时仅查询原请求回执。
 */
public final class ProviderSetupWizard {
    private final Dialog<Void> dialog = new Dialog<>();
    private final ProviderSetupWorkflow workflow;
    private final ProviderSetupConnectionForm connection = new ProviderSetupConnectionForm();
    private final ProviderSetupModelForm models;
    private final VBox modelStep;
    private final CheckBox enable = new CheckBox("保存后启用");
    private final Label status = new Label();
    private final Label disabledReason = new Label();
    private final Label savedSummary = new Label();
    private final VBox completion = new VBox(12);
    private final Consumer<ProviderEndpoint> completed;
    private final Button primary;
    private final Button back;
    private Runnable closed = () -> {};
    private boolean modelStage;
    private boolean dirty;
    private boolean finished;
    private boolean hidden;
    private boolean disposing;

    private ProviderSetupWizard(
            Window owner,
            CoreSettingsGateway gateway,
            Optional<ProviderEndpoint> source,
            long credentialRevision,
            Consumer<ProviderEndpoint> completed) {
        this.completed = Objects.requireNonNull(completed, "completed");
        workflow = source.map(value -> new ProviderSetupWorkflow(gateway, value, credentialRevision))
                .orElseGet(() -> new ProviderSetupWorkflow(gateway));
        models = new ProviderSetupModelForm(new PlatformComponentFactory(), this::discover);
        source.ifPresent(value -> {
            connection.seed(ProviderDraft.from(value));
            models.seed(value.spec().models());
        });
        enable.setSelected(true);
        enable.setId("providerWizardEnable");
        modelStep = new VBox(12, models, enable);
        modelStep.setMinWidth(0);
        VBox.setVgrow(models, Priority.ALWAYS);
        VBox.setVgrow(modelStep, Priority.ALWAYS);
        configureBody(source.isPresent());
        ButtonType primaryType = new ButtonType("下一步：获取模型", ButtonData.OK_DONE);
        ButtonType backType = new ButtonType("上一步", ButtonData.BACK_PREVIOUS);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, backType, primaryType);
        primary = (Button) dialog.getDialogPane().lookupButton(primaryType);
        back = (Button) dialog.getDialogPane().lookupButton(backType);
        bindEvents();
        workflow.onProgress(value -> FxStateDispatcher.dispatch(() -> {
            if (hidden || finished) {
                return;
            }
            status.setText(value);
            status.getStyleClass().remove("status-error");
            if (!status.getStyleClass().contains("sec-hint")) {
                status.getStyleClass().add("sec-hint");
            }
            if (workflow.capabilityKnown() && !workflow.supported()) {
                connection.clearSecret();
            }
            updateButtons();
        }));
        status.setText(workflow.phase());
        PlatformDialogs.style(dialog, owner);
        showStep(false);
    }

    /**
     * 打开新建配置窗口；目标参数仅保留调用兼容性，保存不改变该目标。
     *
     * @param owner 所属窗口，可空
     * @param gateway SDK 边界
     * @param target 原入口上下文
     * @param completed 保存成功后的刷新回调
     */
    public static void show(Window owner, CoreSettingsGateway gateway, ProviderSetupTarget target, Runnable completed) {
        show(owner, gateway, target, completed, () -> {});
    }

    /**
     * 打开新建配置窗口；独立使用模型的回调不会由配置保存触发。
     *
     * @param owner 所属窗口，可空
     * @param gateway SDK 边界
     * @param target 原入口上下文
     * @param completed 保存成功后的刷新回调
     * @param used 兼容旧入口的独立使用回调，本窗口不调用
     */
    public static void show(
            Window owner, CoreSettingsGateway gateway, ProviderSetupTarget target, Runnable completed, Runnable used) {
        configure(owner, gateway, Optional.empty(), 0, ignored -> completed.run());
    }

    static ProviderSetupWizard configure(
            Window owner,
            CoreSettingsGateway gateway,
            Optional<ProviderEndpoint> source,
            long credentialRevision,
            Consumer<ProviderEndpoint> completed) {
        ProviderSetupWizard wizard = new ProviderSetupWizard(owner, gateway, source, credentialRevision, completed);
        wizard.dialog.show();
        return wizard;
    }

    /**
     * 在独立入口中使用已保存的精确模型。
     *
     * @param owner 所属窗口，可空
     * @param gateway SDK 边界
     * @param target 固定入口作用域
     * @param model 已保存模型引用
     * @param completed 使用成功后的刷新回调
     */
    public static void useModel(
            Window owner,
            CoreSettingsGateway gateway,
            ProviderSetupTarget target,
            ProviderRef model,
            Runnable completed) {
        useModel(owner, gateway, target, model, completed, () -> {});
    }

    /**
     * 使用模型成功后执行独立导航，不属于统一配置保存。
     *
     * @param owner 所属窗口，可空
     * @param gateway SDK 边界
     * @param target 固定入口作用域
     * @param model 已保存模型引用
     * @param completed 使用成功后的刷新回调
     * @param used 使用成功后的导航回调
     */
    public static void useModel(
            Window owner,
            CoreSettingsGateway gateway,
            ProviderSetupTarget target,
            ProviderRef model,
            Runnable completed,
            Runnable used) {
        ProviderModelUseDialog.show(owner, gateway, target, model, completed, used);
    }

    void onClosed(Runnable listener) {
        closed = listener;
    }

    boolean dirty() {
        return !hidden && dirty;
    }

    boolean pending() {
        return !hidden && (workflow.pending() || workflow.unknown());
    }

    boolean showing() {
        return dialog.isShowing();
    }

    void requestClose() {
        dialog.close();
    }

    void dispose() {
        disposing = true;
        connection.clearSecret();
        workflow.close();
        dialog.close();
    }

    private void configureBody(boolean editing) {
        configureNotice(status, "providerWizardStatus");
        configureNotice(disabledReason, "providerWizardDisabledReason");
        dialog.setTitle(editing ? "配置模型服务" : "新建模型服务");
        dialog.setHeaderText("① 连接服务 ───── ② 选择模型");
        dialog.setResizable(true);
        configureCompletion();
        VBox body = new VBox(12, connection, modelStep, completion);
        body.setMinWidth(0);
        body.setPrefWidth(580);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setId("providerWizardBodyScroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setMinWidth(0);
        scroll.setMinHeight(0);
        scroll.setPrefViewportHeight(320);
        scroll.setPrefViewportWidth(580);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox content = new VBox(8, scroll, status, disabledReason);
        content.setMinWidth(0);
        content.setMinHeight(0);
        // 仅表单内容滚动；固定提示与 DialogPane 底栏共同保留，禁止通用 Dialog 再包一层滚动容器。
        content.getStyleClass().add("dialog-no-auto-scroll");
        dialog.getDialogPane().setContent(content);
    }

    private void configureCompletion() {
        Label title = new Label("保存成功");
        title.getStyleClass().addAll("sec-title", "status-success");
        savedSummary.setId("providerWizardSavedSummary");
        savedSummary.setWrapText(true);
        savedSummary.setMinHeight(Region.USE_PREF_SIZE);
        Label hint = new Label("服务配置已保存，可关闭窗口返回列表。当前对话和工作区的模型选择未改变。");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.getStyleClass().add("sec-hint");
        completion.setId("providerWizardCompletion");
        completion.getChildren().addAll(title, savedSummary, hint);
        completion.setVisible(false);
        completion.setManaged(false);
    }

    private static void configureNotice(Label notice, String id) {
        notice.setId(id);
        notice.setWrapText(true);
        notice.setMinWidth(0);
        notice.setMinHeight(Region.USE_PREF_SIZE);
        notice.getStyleClass().add("sec-hint");
        notice.visibleProperty().bind(notice.textProperty().isNotEmpty());
        notice.managedProperty().bind(notice.visibleProperty());
    }

    private void bindEvents() {
        ButtonBar buttonBar = (ButtonBar) dialog.getDialogPane().lookup(".button-bar");
        buttonBar.setButtonMinWidth(Region.USE_PREF_SIZE);
        for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
            ButtonBar.setButtonUniformSize(dialog.getDialogPane().lookupButton(type), false);
        }
        primary.setId("providerWizardContinue");
        back.setId("providerWizardBack");
        Button close = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        close.setId("providerWizardClose");
        close.setText("关闭");
        primary.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (finished) {
                dialog.close();
            } else if (workflow.unknown()) {
                checkResult();
            } else if (modelStage && workflow.needsSecretInput()) {
                showStep(false);
                connection.focusSecret();
            } else if (modelStage) {
                save();
            } else {
                connect();
            }
        });
        back.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            workflow.cancelPreview();
            showStep(false);
        });
        connection.onChanged(this::edited);
        models.onChanged(this::edited);
        enable.selectedProperty().addListener((ignored, before, value) -> edited());
        dialog.setOnShown(event -> routeWindowClose());
        dialog.setOnCloseRequest(event -> {
            if (!mayClose()) {
                event.consume();
            }
        });
        dialog.setOnHidden(event -> {
            hidden = true;
            connection.clearSecret();
            workflow.close();
            closed.run();
        });
    }

    private void routeWindowClose() {
        // 原生标题栏关闭直接作用于 Stage；先消费原事件，再统一进入 Dialog 草稿与提交保护，只确认一次。
        dialog.getDialogPane().getScene().getWindow().addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            event.consume();
            dialog.close();
        });
    }

    private boolean mayClose() {
        if (finished || disposing) {
            return true;
        }
        if (workflow.pending() || workflow.unknown()) {
            status.setText("保存正在完成或结果尚未确认；请在本窗口查询原回执后再关闭。");
            return false;
        }
        if (!dirty) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "放弃尚未保存的配置和临时密钥？", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("配置尚未保存");
        PlatformDialogs.style(alert, dialog.getDialogPane().getScene().getWindow());
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void connect() {
        try {
            var draft = connection.draft();
            workflow.connect(
                            draft,
                            connection.takeSecret(),
                            connection.replacementRequested(),
                            connection.clearingConfirmed())
                    .whenComplete((ignored, failure) -> FxStateDispatcher.dispatch(() -> {
                        if (hidden) {
                            return;
                        }
                        if (failure != null) {
                            fail(failure);
                        } else {
                            connection.prepared();
                            showStep(true);
                            discover();
                        }
                    }));
        } catch (RuntimeException invalid) {
            connection.clearSecret();
            fail(invalid);
        }
    }

    private void discover() {
        if (workflow.needsSecretInput()) {
            showStep(false);
            connection.focusSecret();
            return;
        }
        workflow.discover()
                .whenComplete((result, failure) -> FxStateDispatcher.dispatch(() -> {
                    if (hidden || finished || !modelStage || workflow.pending() || workflow.unknown()) {
                        return;
                    }
                    if (failure != null) {
                        if (!(SettingsFailures.unwrap(failure) instanceof java.util.concurrent.CancellationException)) {
                            status.setText(ProviderPreviewMessages.describe(failure));
                        }
                    } else {
                        models.candidates(result.candidates());
                        status.setText(result.truncated() ? "目录结果已截断，可手动补充。" : "目录已读取；请选择需要保存的模型。");
                    }
                    updateButtons();
                }));
        updateButtons();
    }

    private void save() {
        try {
            workflow.save(models.selectedModels(), enable.isSelected())
                    .whenComplete((result, failure) -> FxStateDispatcher.dispatch(() -> {
                        if (failure == null) {
                            finish(result);
                        } else {
                            saveFailed(failure);
                            if (workflow.unknown()) {
                                checkResult();
                            }
                        }
                    }));
            updateButtons();
        } catch (RuntimeException failure) {
            saveFailed(failure);
        }
    }

    private void checkResult() {
        workflow.checkResult()
                .whenComplete((result, failure) -> FxStateDispatcher.dispatch(() -> {
                    if (failure != null) {
                        fail(failure);
                    } else {
                        result.ifPresent(this::finish);
                        updateButtons();
                    }
                }));
        updateButtons();
    }

    private void finish(ProviderConfigurationResult result) {
        if (hidden || finished) {
            return;
        }
        finished = true;
        dirty = false;
        workflow.close();
        connection.clearSecret();
        ProviderEndpoint provider = result.provider();
        savedSummary.setText(provider.spec().displayName()
                + "\n已保存 " + provider.spec().models().size() + " 个模型 · "
                + (provider.lifecycle() == ProviderLifecycle.ACTIVE ? "服务已启用" : "服务已停用"));
        dialog.setHeaderText("配置已保存");
        connection.setVisible(false);
        connection.setManaged(false);
        modelStep.setVisible(false);
        modelStep.setManaged(false);
        completion.setVisible(true);
        completion.setManaged(true);
        status.setText("");
        updateButtons();
        completed.accept(result.provider());
    }

    private void showStep(boolean value) {
        modelStage = value;
        connection.setVisible(!value);
        connection.setManaged(!value);
        modelStep.setVisible(value);
        modelStep.setManaged(value);
        updateButtons();
    }

    private void edited() {
        dirty = true;
        updateButtons();
    }

    private void updateButtons() {
        if (finished) {
            primary.setText("关闭");
            primary.setDisable(false);
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setCancelButton(false);
            primary.setCancelButton(true);
            back.setVisible(false);
            back.setManaged(false);
            dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setVisible(false);
            dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setManaged(false);
            disabledReason.setText("");
            return;
        }
        boolean locked = workflow.pending() || workflow.unknown();
        connection.setDisable(locked);
        modelStep.setDisable(locked);
        back.setVisible(modelStage);
        back.setManaged(modelStage);
        back.setDisable(locked);
        primary.setText(primaryText());
        String reason = unavailableReason();
        primary.setDisable(!reason.isEmpty());
        disabledReason.setText(reason);
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(locked);
    }

    private String primaryText() {
        if (workflow.unknown()) {
            return "查询保存结果";
        }
        if (!modelStage) {
            return "下一步：获取模型";
        }
        return workflow.needsSecretInput() ? "返回填写密钥" : "保存模型";
    }

    private String unavailableReason() {
        if (workflow.pending()) {
            return "正在保存或查询，请稍候。";
        }
        if (workflow.unknown()) {
            return "";
        }
        if (!workflow.supported()) {
            return workflow.capability().message();
        }
        if (modelStage) {
            if (workflow.needsSecretInput()) {
                return "";
            }
            try {
                return models.selectedModels().isEmpty() ? "请至少选择或手动添加一个模型。" : "";
            } catch (RuntimeException invalid) {
                return SettingsFailures.message(invalid);
            }
        }
        return "";
    }

    private void fail(Throwable failure) {
        status.setText(workflow.unknown() ? workflow.phase() : SettingsFailures.message(failure));
        status.getStyleClass().remove("sec-hint");
        if (!status.getStyleClass().contains("status-error")) {
            status.getStyleClass().add("status-error");
        }
        updateButtons();
    }

    private void saveFailed(Throwable failure) {
        if (hidden || finished) {
            return;
        }
        workflow.cancelPreview();
        String message = workflow.unknown()
                ? workflow.phase()
                : "尚未保存：" + SettingsFailures.message(failure)
                        + (workflow.needsSecretInput() ? "\n临时密钥已清除，请返回填写密钥。" : "");
        status.setText(message);
        status.getStyleClass().remove("sec-hint");
        if (!status.getStyleClass().contains("status-error")) {
            status.getStyleClass().add("status-error");
        }
        updateButtons();
    }
}
