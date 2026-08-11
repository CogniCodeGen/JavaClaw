package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.SaveResult;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.SecretFieldController;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 模型配置分区 Controller；只协调表单、应用用例与异步动作。 */
public final class ModelSettingsController
        implements ModelSettingsSectionFactory.AppliedSettingsController, AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ToggleButton basicTab;
    @FXML private ToggleButton advancedTab;
    @FXML private VBox basicPanel;
    @FXML private VBox advancedPanel;
    @FXML private TextField baseUrlField;
    @FXML private TextField modelNameField;
    @FXML private SecretFieldController apiKeyFieldController;
    @FXML private ToggleSwitch thinkingEnabledCheck;
    @FXML private TextField thinkingBudgetField;
    @FXML private RadioButton http11Radio;
    @FXML private RadioButton http2Radio;
    @FXML private TextField connectTimeoutField;
    @FXML private TextField readTimeoutField;
    @FXML private TextField writeTimeoutField;
    @FXML private TextField orchestratorMaxItersField;
    @FXML private TextField webAgentMaxItersField;
    @FXML private TextField emailAgentMaxItersField;
    @FXML private TextField maxRepeatedCallsField;
    @FXML private TextField loopThresholdField;
    @FXML private TextField evaluatorThresholdField;
    @FXML private TextField evaluatorMaxRetriesField;
    @FXML private Label storageLabel;
    @FXML private Button resetButton;

    private final ModelSettingsApplicationService useCases;
    private final DialogService dialogs;
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<ProbeResult> probe;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public ModelSettingsController(
            ModelSettingsApplicationService useCases,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        mutation = new UiAsyncAction<>(tasks, fx);
        probe = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        thinkingEnabledCheck.selectedProperty().addListener((ignored, previous, enabled) ->
                thinkingBudgetField.setDisable(!enabled));
        SettingsFieldSupport.validateInteger(thinkingBudgetField, 1024, 65536);
        SettingsFieldSupport.validateInteger(connectTimeoutField, 1, 600);
        SettingsFieldSupport.validateInteger(readTimeoutField, 1, 3600);
        SettingsFieldSupport.validateInteger(writeTimeoutField, 1, 600);
        SettingsFieldSupport.validateInteger(orchestratorMaxItersField, 1, 100);
        SettingsFieldSupport.validateInteger(webAgentMaxItersField, 1, 50);
        SettingsFieldSupport.validateInteger(emailAgentMaxItersField, 1, 50);
        SettingsFieldSupport.validateInteger(maxRepeatedCallsField, 1, 50);
        SettingsFieldSupport.validateDecimal(loopThresholdField, 0, 1);
        SettingsFieldSupport.validateDecimal(evaluatorThresholdField, 1, 5);
        SettingsFieldSupport.validateInteger(evaluatorMaxRetriesField, 0, 10);
        reload();
    }

    @Override
    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public void reload() {
        var snapshot = useCases.snapshot();
        ModelSettings value = snapshot.model();
        SettingsFieldSupport.loading(root, () -> {
            baseUrlField.setText(value.baseUrl());
            modelNameField.setText(value.modelName());
            apiKeyFieldController.setText(value.apiKey());
            thinkingEnabledCheck.setSelected(value.thinkingEnabled());
            thinkingBudgetField.setText(Integer.toString(value.thinkingBudget()));
            thinkingBudgetField.setDisable(!value.thinkingEnabled());
            http2Radio.setSelected("HTTP_2".equals(value.httpVersion()));
            http11Radio.setSelected(!http2Radio.isSelected());
            connectTimeoutField.setText(Integer.toString(value.connectTimeoutSeconds()));
            readTimeoutField.setText(Integer.toString(value.readTimeoutSeconds()));
            writeTimeoutField.setText(Integer.toString(value.writeTimeoutSeconds()));
            orchestratorMaxItersField.setText(Integer.toString(value.orchestratorMaxIterations()));
            webAgentMaxItersField.setText(Integer.toString(value.webAgentMaxIterations()));
            emailAgentMaxItersField.setText(Integer.toString(value.emailAgentMaxIterations()));
            maxRepeatedCallsField.setText(Integer.toString(value.maxRepeatedToolCalls()));
            loopThresholdField.setText(Double.toString(value.loopSimilarityThreshold()));
            evaluatorThresholdField.setText(Double.toString(value.evaluatorPassThreshold()));
            evaluatorMaxRetriesField.setText(Integer.toString(value.evaluatorMaxRetries()));
            storageLabel.setText("配置文件: " + snapshot.storageDescription());
        });
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        ModelSettings command = form();
        mutation.execute(TaskSpec.io("settings-model-save"),
                context -> useCases.saveModel(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, failure);
    }

    public void probe(Consumer<ProbeResult> success, Consumer<Throwable> failure) {
        ModelSettings command = form();
        probe.execute(TaskSpec.io("settings-model-probe"),
                context -> useCases.probeModel(command), success, failure);
    }

    @FXML private void basicRequested() { showBasic(true); }
    @FXML private void advancedRequested() { showBasic(false); }

    private void showBasic(boolean basic) {
        basicTab.setSelected(basic);
        advancedTab.setSelected(!basic);
        basicPanel.setVisible(basic);
        basicPanel.setManaged(basic);
        advancedPanel.setVisible(!basic);
        advancedPanel.setManaged(!basic);
    }

    @FXML
    private void resetRequested() {
        mutation.execute(TaskSpec.io("settings-model-reset"), context -> {
            ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                    "重置配置", "恢复默认模型配置", "确定要恢复所有模型配置为默认值？",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.resetModel() : null;
        }, result -> {
            if (result == null) return;
            reload();
            onApplied.accept(result);
        }, ignored -> { });
    }

    private ModelSettings form() {
        return new ModelSettings("OpenAI", SettingsFieldSupport.text(baseUrlField),
                SettingsFieldSupport.text(modelNameField), apiKeyFieldController.text(),
                thinkingEnabledCheck.isSelected(),
                SettingsFieldSupport.integer(thinkingBudgetField, 1024, 65536, "思考预算"),
                http2Radio.isSelected() ? "HTTP_2" : "HTTP_1_1",
                SettingsFieldSupport.integer(connectTimeoutField, 1, 600, "连接超时"),
                SettingsFieldSupport.integer(readTimeoutField, 1, 3600, "读取超时"),
                SettingsFieldSupport.integer(writeTimeoutField, 1, 600, "写入超时"),
                SettingsFieldSupport.integer(orchestratorMaxItersField, 1, 100, "编排迭代次数"),
                SettingsFieldSupport.integer(webAgentMaxItersField, 1, 50, "Web 迭代次数"),
                SettingsFieldSupport.integer(emailAgentMaxItersField, 1, 50, "邮件迭代次数"),
                SettingsFieldSupport.integer(maxRepeatedCallsField, 1, 50, "最大重复次数"),
                SettingsFieldSupport.decimal(loopThresholdField, 0, 1, "相似度阈值"),
                SettingsFieldSupport.decimal(evaluatorThresholdField, 1, 5, "评估通过阈值"),
                SettingsFieldSupport.integer(evaluatorMaxRetriesField, 0, 10, "评估重试次数"));
    }

    @Override
    public void close() {
        mutation.close();
        probe.close();
    }
}
