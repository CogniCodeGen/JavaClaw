package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailProbeResult;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.SaveResult;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.SecretFieldController;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 邮件设置 Controller；网络探测与持久化均通过应用服务在托管 I/O 任务中执行。 */
public final class EmailSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ComboBox<String> presetCombo;
    @FXML private TextField smtpHostField;
    @FXML private TextField smtpPortField;
    @FXML private TextField imapHostField;
    @FXML private TextField imapPortField;
    @FXML private ComboBox<String> encryptionCombo;
    @FXML private TextField usernameField;
    @FXML private SecretFieldController passwordFieldController;
    @FXML private TextField fromAddressField;
    @FXML private Label storageLabel;

    private final CommunicationSettingsApplicationService useCases;
    private final EmailSettingsViewModel viewModel = new EmailSettingsViewModel();
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<EmailProbeResult> probe;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public EmailSettingsController(
            CommunicationSettingsApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        mutation = new UiAsyncAction<>(tasks, fx);
        probe = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        presetCombo.getItems().setAll(
                List.of("QQ 邮箱", "163 邮箱", "Gmail", "Outlook", "自定义"));
        encryptionCombo.getItems().setAll(List.of("SSL", "STARTTLS", "无"));
        bind();
        SettingsFieldSupport.validateInteger(smtpPortField, 1, 65535);
        SettingsFieldSupport.validateInteger(imapPortField, 1, 65535);
        viewModel.busyProperty().bind(
                mutation.busyProperty().or(probe.busyProperty()));
        reload();
    }

    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public EmailSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        var snapshot = useCases.snapshot();
        SettingsFieldSupport.loading(root,
                () -> viewModel.load(snapshot.email(), snapshot.storageDescription()));
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        EmailSettings command = form();
        mutation.execute(TaskSpec.io("settings-email-save"),
                context -> useCases.saveEmail(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, thrown -> failed(thrown, failure));
    }

    public void probe(Consumer<EmailProbeResult> success, Consumer<Throwable> failure) {
        EmailSettings command = form();
        probe.execute(TaskSpec.io("settings-email-probe"),
                context -> useCases.saveAndProbeEmail(command), result -> {
                    reload();
                    onApplied.accept(result.saved());
                    success.accept(result);
                }, thrown -> failed(thrown, failure));
    }

    @FXML
    private void presetChanged() {
        if (!SettingsFieldSupport.isLoading(root)) {
            viewModel.applyPreset(presetCombo.getValue());
        }
    }

    private EmailSettings form() {
        return new EmailSettings(SettingsFieldSupport.text(smtpHostField),
                SettingsFieldSupport.integer(smtpPortField, 1, 65535, "SMTP 端口"),
                SettingsFieldSupport.text(imapHostField),
                SettingsFieldSupport.integer(imapPortField, 1, 65535, "IMAP 端口"),
                SettingsFieldSupport.text(usernameField), passwordFieldController.text(),
                SettingsFieldSupport.text(fromAddressField), viewModel.encryptionValue());
    }

    private void bind() {
        presetCombo.valueProperty().bindBidirectional(viewModel.presetProperty());
        smtpHostField.textProperty().bindBidirectional(viewModel.smtpHostProperty());
        smtpPortField.textProperty().bindBidirectional(viewModel.smtpPortProperty());
        imapHostField.textProperty().bindBidirectional(viewModel.imapHostProperty());
        imapPortField.textProperty().bindBidirectional(viewModel.imapPortProperty());
        encryptionCombo.valueProperty().bindBidirectional(viewModel.encryptionProperty());
        usernameField.textProperty().bindBidirectional(viewModel.usernameProperty());
        passwordFieldController.textProperty().bindBidirectional(viewModel.passwordProperty());
        fromAddressField.textProperty().bindBidirectional(viewModel.fromAddressProperty());
        storageLabel.textProperty().bind(viewModel.storageDescriptionProperty());
    }

    private void failed(Throwable thrown, Consumer<Throwable> failure) {
        viewModel.errorProperty().set(SettingsFieldSupport.failureMessage(thrown));
        failure.accept(thrown);
    }

    @Override
    public void close() {
        mutation.close();
        probe.close();
        viewModel.busyProperty().unbind();
        presetCombo.valueProperty().unbindBidirectional(viewModel.presetProperty());
        smtpHostField.textProperty().unbindBidirectional(viewModel.smtpHostProperty());
        smtpPortField.textProperty().unbindBidirectional(viewModel.smtpPortProperty());
        imapHostField.textProperty().unbindBidirectional(viewModel.imapHostProperty());
        imapPortField.textProperty().unbindBidirectional(viewModel.imapPortProperty());
        encryptionCombo.valueProperty().unbindBidirectional(viewModel.encryptionProperty());
        usernameField.textProperty().unbindBidirectional(viewModel.usernameProperty());
        passwordFieldController.textProperty().unbindBidirectional(viewModel.passwordProperty());
        fromAddressField.textProperty().unbindBidirectional(viewModel.fromAddressProperty());
        storageLabel.textProperty().unbind();
    }
}
