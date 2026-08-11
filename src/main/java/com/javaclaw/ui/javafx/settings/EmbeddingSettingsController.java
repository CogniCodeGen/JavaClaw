package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.SaveResult;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.SecretFieldController;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 嵌入模型设置 Controller。 */
public final class EmbeddingSettingsController
        implements ModelSettingsSectionFactory.AppliedSettingsController, AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ToggleSwitch enabledCheck;
    @FXML private ComboBox<String> providerCombo;
    @FXML private TextField baseUrlField;
    @FXML private Node apiKeyField;
    @FXML private SecretFieldController apiKeyFieldController;
    @FXML private TextField modelNameField;
    @FXML private TextField dimensionsField;
    @FXML private TextField retrieveLimitField;
    @FXML private TextField scoreThresholdField;

    private final ModelSettingsApplicationService useCases;
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<ProbeResult> probe;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public EmbeddingSettingsController(
            ModelSettingsApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        mutation = new UiAsyncAction<>(tasks, fx);
        probe = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        providerCombo.getItems().setAll(List.of("OpenAI", "DashScope", "Ollama"));
        enabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableFields(enabled));
        SettingsFieldSupport.validateInteger(dimensionsField, 1, Integer.MAX_VALUE);
        SettingsFieldSupport.validateInteger(retrieveLimitField, 1, Integer.MAX_VALUE);
        SettingsFieldSupport.validateDecimal(scoreThresholdField, 0, 1);
        reload();
    }

    @Override
    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public void reload() {
        EmbeddingSettings value = useCases.snapshot().embedding();
        SettingsFieldSupport.loading(root, () -> {
            enabledCheck.setSelected(value.enabled());
            providerCombo.setValue(value.provider());
            baseUrlField.setText(value.baseUrl());
            apiKeyFieldController.setText(value.apiKey());
            modelNameField.setText(value.modelName());
            dimensionsField.setText(Integer.toString(value.dimensions()));
            retrieveLimitField.setText(Integer.toString(value.retrieveLimit()));
            scoreThresholdField.setText(Double.toString(value.scoreThreshold()));
            enableFields(value.enabled());
        });
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        EmbeddingSettings command = form();
        mutation.execute(TaskSpec.io("settings-embedding-save"),
                context -> useCases.saveEmbedding(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, failure);
    }

    public void probe(Consumer<ProbeResult> success, Consumer<Throwable> failure) {
        EmbeddingSettings command = form();
        probe.execute(TaskSpec.io("settings-embedding-probe"),
                context -> useCases.probeEmbedding(command), success, failure);
    }

    @FXML
    private void providerChanged() {
        switch (providerCombo.getValue() == null ? "" : providerCombo.getValue()) {
            case "OpenAI" -> preset("https://api.openai.com/v1", "text-embedding-3-small", "1024",
                    "OpenAI API 密钥");
            case "DashScope" -> preset("https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "text-embedding-v3", "1024", "DashScope API 密钥");
            case "Ollama" -> preset("http://localhost:11434/v1", "nomic-embed-text", "768",
                    "Ollama 无需密钥");
            default -> { }
        }
    }

    private void preset(String baseUrl, String model, String dimensions, String prompt) {
        baseUrlField.setText(baseUrl);
        modelNameField.setText(model);
        dimensionsField.setText(dimensions);
        apiKeyFieldController.setPromptText(prompt);
    }

    private void enableFields(boolean enabled) {
        for (Node node : List.of(providerCombo, baseUrlField, apiKeyField, modelNameField,
                dimensionsField, retrieveLimitField, scoreThresholdField)) {
            node.setDisable(!enabled);
        }
    }

    private EmbeddingSettings form() {
        return new EmbeddingSettings(enabledCheck.isSelected(), providerCombo.getValue(),
                SettingsFieldSupport.text(baseUrlField), apiKeyFieldController.text(),
                SettingsFieldSupport.text(modelNameField),
                SettingsFieldSupport.integer(dimensionsField, 1, Integer.MAX_VALUE, "向量维度"),
                SettingsFieldSupport.integer(retrieveLimitField, 1, Integer.MAX_VALUE, "检索数量"),
                SettingsFieldSupport.decimal(scoreThresholdField, 0, 1, "分数阈值"));
    }

    @Override
    public void close() {
        mutation.close();
        probe.close();
    }
}
