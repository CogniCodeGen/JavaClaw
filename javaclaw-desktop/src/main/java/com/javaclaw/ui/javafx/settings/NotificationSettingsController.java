package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Snapshot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.SecretFieldController;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;

import java.util.Objects;
import java.util.function.Consumer;

/** 通知渠道设置 Controller。 */
public final class NotificationSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ToggleSwitch dingtalkEnabledCheck;
    @FXML private TextField dingtalkWebhookField;
    @FXML private Node dingtalkSecretField;
    @FXML private SecretFieldController dingtalkSecretFieldController;
    @FXML private ToggleSwitch wechatEnabledCheck;
    @FXML private TextField wechatWebhookField;
    @FXML private ToggleSwitch feishuEnabledCheck;
    @FXML private TextField feishuWebhookField;
    @FXML private Node feishuSecretField;
    @FXML private SecretFieldController feishuSecretFieldController;
    @FXML private ToggleSwitch emailEnabledCheck;
    @FXML private TextField emailRecipientField;
    @FXML private ToggleSwitch customEnabledCheck;
    @FXML private TextField customWebhookField;
    @FXML private TextField customBodyField;
    @FXML private Label storageLabel;

    private final CommunicationSettingsApplicationService useCases;
    private final NotificationSettingsViewModel viewModel = new NotificationSettingsViewModel();
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<Snapshot> refresh;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public NotificationSettingsController(
            CommunicationSettingsApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        mutation = new UiAsyncAction<>(tasks, fx);
        refresh = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        bind();
        dingtalkEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableDingtalk(enabled));
        wechatEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> wechatWebhookField.setDisable(!enabled));
        feishuEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableFeishu(enabled));
        emailEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> emailRecipientField.setDisable(!enabled));
        customEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableCustom(enabled));
        viewModel.busyProperty().bind(mutation.busyProperty().or(refresh.busyProperty()));
    }

    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public NotificationSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        refresh.execute(TaskSpec.io("settings-notifications-load"), context -> useCases.snapshot(),
                snapshot -> SettingsFieldSupport.loading(root, () -> {
            viewModel.load(snapshot.notifications(), snapshot.storageDescription());
            applyEnabledState();
        }), failure -> viewModel.errorProperty().set(
                SettingsFieldSupport.failureMessage(failure)));
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        var command = viewModel.value();
        mutation.execute(TaskSpec.io("settings-notifications-save"),
                context -> useCases.saveNotifications(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, thrown -> {
                    viewModel.errorProperty().set(SettingsFieldSupport.failureMessage(thrown));
                    failure.accept(thrown);
                });
    }

    private void applyEnabledState() {
        enableDingtalk(dingtalkEnabledCheck.isSelected());
        wechatWebhookField.setDisable(!wechatEnabledCheck.isSelected());
        enableFeishu(feishuEnabledCheck.isSelected());
        emailRecipientField.setDisable(!emailEnabledCheck.isSelected());
        enableCustom(customEnabledCheck.isSelected());
    }

    private void enableDingtalk(boolean enabled) {
        dingtalkWebhookField.setDisable(!enabled);
        dingtalkSecretField.setDisable(!enabled);
    }

    private void enableFeishu(boolean enabled) {
        feishuWebhookField.setDisable(!enabled);
        feishuSecretField.setDisable(!enabled);
    }

    private void enableCustom(boolean enabled) {
        customWebhookField.setDisable(!enabled);
        customBodyField.setDisable(!enabled);
    }

    private void bind() {
        dingtalkEnabledCheck.selectedProperty().bindBidirectional(
                viewModel.dingtalkEnabledProperty());
        dingtalkWebhookField.textProperty().bindBidirectional(
                viewModel.dingtalkWebhookProperty());
        dingtalkSecretFieldController.textProperty().bindBidirectional(
                viewModel.dingtalkSecretProperty());
        wechatEnabledCheck.selectedProperty().bindBidirectional(viewModel.wechatEnabledProperty());
        wechatWebhookField.textProperty().bindBidirectional(viewModel.wechatWebhookProperty());
        feishuEnabledCheck.selectedProperty().bindBidirectional(viewModel.feishuEnabledProperty());
        feishuWebhookField.textProperty().bindBidirectional(viewModel.feishuWebhookProperty());
        feishuSecretFieldController.textProperty().bindBidirectional(
                viewModel.feishuSecretProperty());
        emailEnabledCheck.selectedProperty().bindBidirectional(viewModel.emailEnabledProperty());
        emailRecipientField.textProperty().bindBidirectional(viewModel.emailRecipientProperty());
        customEnabledCheck.selectedProperty().bindBidirectional(viewModel.customEnabledProperty());
        customWebhookField.textProperty().bindBidirectional(viewModel.customWebhookProperty());
        customBodyField.textProperty().bindBidirectional(viewModel.customBodyProperty());
        storageLabel.textProperty().bind(viewModel.storageDescriptionProperty());
    }

    void deactivate() { refresh.cancel(); }

    @Override
    public void close() {
        mutation.close();
        refresh.close();
        viewModel.busyProperty().unbind();
        dingtalkEnabledCheck.selectedProperty().unbindBidirectional(
                viewModel.dingtalkEnabledProperty());
        dingtalkWebhookField.textProperty().unbindBidirectional(
                viewModel.dingtalkWebhookProperty());
        dingtalkSecretFieldController.textProperty().unbindBidirectional(
                viewModel.dingtalkSecretProperty());
        wechatEnabledCheck.selectedProperty().unbindBidirectional(viewModel.wechatEnabledProperty());
        wechatWebhookField.textProperty().unbindBidirectional(viewModel.wechatWebhookProperty());
        feishuEnabledCheck.selectedProperty().unbindBidirectional(viewModel.feishuEnabledProperty());
        feishuWebhookField.textProperty().unbindBidirectional(viewModel.feishuWebhookProperty());
        feishuSecretFieldController.textProperty().unbindBidirectional(
                viewModel.feishuSecretProperty());
        emailEnabledCheck.selectedProperty().unbindBidirectional(viewModel.emailEnabledProperty());
        emailRecipientField.textProperty().unbindBidirectional(viewModel.emailRecipientProperty());
        customEnabledCheck.selectedProperty().unbindBidirectional(viewModel.customEnabledProperty());
        customWebhookField.textProperty().unbindBidirectional(viewModel.customWebhookProperty());
        customBodyField.textProperty().unbindBidirectional(viewModel.customBodyProperty());
        storageLabel.textProperty().unbind();
    }
}
