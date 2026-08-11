package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.ModelSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Tier;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;
import com.javaclaw.platform.dialog.DialogService;
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

/** 分级模型设置 Controller。 */
public final class TieredModelSettingsController
        implements ModelSettingsSectionFactory.AppliedSettingsController, AutoCloseable {

    private static final List<String> PROVIDERS =
            List.of("OpenAI", "DashScope", "Anthropic", "Gemini", "Ollama");

    @FXML private ScrollPane root;
    @FXML private ToggleSwitch normalEnabledCheck;
    @FXML private ComboBox<String> normalProviderCombo;
    @FXML private TextField normalBaseUrlField;
    @FXML private TextField normalModelNameField;
    @FXML private Node normalApiKeyField;
    @FXML private SecretFieldController normalApiKeyFieldController;
    @FXML private ToggleSwitch normalThinkingCheck;
    @FXML private ToggleSwitch lightEnabledCheck;
    @FXML private ComboBox<String> lightProviderCombo;
    @FXML private TextField lightBaseUrlField;
    @FXML private TextField lightModelNameField;
    @FXML private Node lightApiKeyField;
    @FXML private SecretFieldController lightApiKeyFieldController;
    @FXML private ToggleSwitch lightThinkingCheck;

    private final ModelSettingsApplicationService useCases;
    private final DialogService dialogs;
    private final UiAsyncAction<SaveResult> mutation;
    private Consumer<SaveResult> onApplied = ignored -> { };

    public TieredModelSettingsController(
            ModelSettingsApplicationService useCases,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        mutation = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        normalProviderCombo.getItems().setAll(PROVIDERS);
        lightProviderCombo.getItems().setAll(PROVIDERS);
        normalEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableNormal(enabled));
        lightEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableLight(enabled));
        reload();
    }

    @Override
    public void configure(Consumer<SaveResult> callback) {
        onApplied = Objects.requireNonNull(callback, "callback");
    }

    public void reload() {
        TierSettings value = useCases.snapshot().tiers();
        SettingsFieldSupport.loading(root, () -> {
            apply(value.normal(), false);
            apply(value.light(), true);
        });
    }

    public void save(Consumer<SaveResult> success, Consumer<Throwable> failure) {
        TierSettings command = new TierSettings(form(false), form(true));
        mutation.execute(TaskSpec.io("settings-tiered-model-save"),
                context -> useCases.saveTiers(command), result -> {
                    reload();
                    onApplied.accept(result);
                    success.accept(result);
                }, failure);
    }

    @FXML private void normalProviderChanged() {
        applyPreset(normalProviderCombo.getValue(), normalBaseUrlField, normalModelNameField);
    }

    @FXML private void lightProviderChanged() {
        applyPreset(lightProviderCombo.getValue(), lightBaseUrlField, lightModelNameField);
    }

    @FXML
    private void clearRequested() {
        mutation.execute(TaskSpec.io("settings-tiered-model-clear"), context -> {
            ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                    "清除分级配置", "恢复模型回落规则",
                    "清除后普通/轻量模型都将回落到高性能模型，确定继续？",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.clearTiers() : null;
        }, result -> {
            if (result == null) return;
            reload();
            onApplied.accept(result);
        }, ignored -> { });
    }

    private void apply(Tier value, boolean light) {
        ToggleSwitch enabled = light ? lightEnabledCheck : normalEnabledCheck;
        ComboBox<String> provider = light ? lightProviderCombo : normalProviderCombo;
        TextField baseUrl = light ? lightBaseUrlField : normalBaseUrlField;
        TextField model = light ? lightModelNameField : normalModelNameField;
        SecretFieldController secret = light
                ? lightApiKeyFieldController : normalApiKeyFieldController;
        ToggleSwitch thinking = light ? lightThinkingCheck : normalThinkingCheck;
        enabled.setSelected(value.enabled());
        provider.setValue(value.provider());
        baseUrl.setText(value.baseUrl());
        model.setText(value.modelName());
        secret.setText(value.apiKey());
        thinking.setSelected(value.thinkingEnabled());
        if (light) enableLight(value.enabled()); else enableNormal(value.enabled());
    }

    private Tier form(boolean light) {
        boolean enabled = (light ? lightEnabledCheck : normalEnabledCheck).isSelected();
        ComboBox<String> provider = light ? lightProviderCombo : normalProviderCombo;
        TextField baseUrl = light ? lightBaseUrlField : normalBaseUrlField;
        TextField model = light ? lightModelNameField : normalModelNameField;
        SecretFieldController secret = light
                ? lightApiKeyFieldController : normalApiKeyFieldController;
        ToggleSwitch thinking = light ? lightThinkingCheck : normalThinkingCheck;
        return new Tier(enabled, provider.getValue(), SettingsFieldSupport.text(baseUrl),
                SettingsFieldSupport.text(model), secret.text(), thinking.isSelected());
    }

    private void enableNormal(boolean enabled) {
        setDisabled(!enabled, normalProviderCombo, normalBaseUrlField, normalModelNameField,
                normalApiKeyField, normalThinkingCheck);
    }

    private void enableLight(boolean enabled) {
        setDisabled(!enabled, lightProviderCombo, lightBaseUrlField, lightModelNameField,
                lightApiKeyField, lightThinkingCheck);
    }

    private static void setDisabled(boolean disabled, Node... nodes) {
        for (Node node : nodes) node.setDisable(disabled);
    }

    private static void applyPreset(
            String provider, TextField baseUrl, TextField modelName) {
        if (provider == null) return;
        String[] preset = switch (provider) {
            case "OpenAI" -> new String[]{"https://api.openai.com/v1", "gpt-4o-mini"};
            case "DashScope" -> new String[]{
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo"};
            case "Anthropic" -> new String[]{"https://api.anthropic.com", "claude-haiku-4-5-20251001"};
            case "Gemini" -> new String[]{"", "gemini-2.5-flash"};
            case "Ollama" -> new String[]{"http://localhost:11434", "qwen3:8b"};
            default -> new String[]{"", ""};
        };
        if (baseUrl.getText().isBlank() && !preset[0].isBlank()) baseUrl.setText(preset[0]);
        if (modelName.getText().isBlank()) modelName.setText(preset[1]);
    }

    @Override
    public void close() {
        mutation.close();
    }
}
