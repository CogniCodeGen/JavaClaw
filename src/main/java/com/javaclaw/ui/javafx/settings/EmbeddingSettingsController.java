package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.ModelProviderCatalog;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.SecretFieldController;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import com.javaclaw.runtime.WorkspaceContext;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.UUID;

/** 嵌入模型设置 Controller。 */
public final class EmbeddingSettingsController
        implements ModelSettingsSectionFactory.AppliedSettingsController, AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ToggleSwitch enabledCheck;
    @FXML private ComboBox<String> providerCombo;
    @FXML private Node baseUrlRow;
    @FXML private Node apiKeyRow;
    @FXML private Node modelNameRow;
    @FXML private Node managedProfileRow;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> managedProfileCombo;
    @FXML private TextField baseUrlField;
    @FXML private Node apiKeyField;
    @FXML private SecretFieldController apiKeyFieldController;
    @FXML private TextField modelNameField;
    @FXML private TextField dimensionsField;
    @FXML private TextField retrieveLimitField;
    @FXML private TextField scoreThresholdField;

    private final ModelSettingsApplicationService useCases;
    private final ModelProviderCatalog providers;
    private final InferenceManagementApplicationService inference;
    private final PluginCenterViewFactory plugins;
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<ProbeResult> probe;
    private final UiAsyncAction<ViewData> refresh;
    private final UiAsyncAction<ProfileProjection> profileRefresh;
    private Consumer<SaveResult> onApplied = ignored -> { };
    private Runnable onRuntimeConfigurationChanged = () -> { };
    private Map<UUID, Integer> managedDimensions = Map.of();

    public EmbeddingSettingsController(
            ModelSettingsApplicationService useCases,
            ModelProviderCatalog providers,
            InferenceManagementApplicationService inference,
            PluginCenterViewFactory plugins,
            WorkspaceContext workspace,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.inference = Objects.requireNonNull(inference, "inference");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        Objects.requireNonNull(workspace, "workspace");
        mutation = new UiAsyncAction<>(tasks, fx);
        probe = new UiAsyncAction<>(tasks, fx);
        refresh = new UiAsyncAction<>(tasks, fx);
        profileRefresh = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        providerCombo.getItems().setAll(providers.providers().stream()
                .filter(provider -> provider.capabilities().contains(ModelProviderCatalog.Capability.EMBEDDING))
                .map(ModelProviderCatalog.Provider::displayName).toList());
        enabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableFields(enabled));
        SettingsFieldSupport.validateInteger(dimensionsField, 1, Integer.MAX_VALUE);
        SettingsFieldSupport.validateInteger(retrieveLimitField, 1, Integer.MAX_VALUE);
        SettingsFieldSupport.validateDecimal(scoreThresholdField, 0, 1);
        managedProfileCombo.valueProperty().addListener((ignored, previous, selected) -> {
            if (selected == null) return;
            Integer dimensions = managedDimensions.get(selected.value());
            if (dimensions != null && dimensions > 0) {
                dimensionsField.setText(Integer.toString(dimensions));
            }
        });
    }

    @Override
    public void configure(
            Consumer<SaveResult> callback, Runnable runtimeConfigurationChanged) {
        onApplied = Objects.requireNonNull(callback, "callback");
        onRuntimeConfigurationChanged = Objects.requireNonNull(
                runtimeConfigurationChanged, "runtimeConfigurationChanged");
    }

    public void reload() {
        refresh.execute(TaskSpec.io("settings-embedding-load"), context -> {
            EmbeddingSettings value = useCases.snapshot().embedding();
            boolean local = providers.find(value.provider())
                    .map(ModelProviderCatalog.Provider::localManaged).orElse(false);
            return new ViewData(value, local ? embeddingProfiles() : ProfileProjection.EMPTY);
        }, this::applySnapshot, ignored -> { });
    }

    private void applySnapshot(ViewData data) {
        EmbeddingSettings value = data.settings();
        SettingsFieldSupport.loading(root, () -> {
            enabledCheck.setSelected(value.enabled());
            providerCombo.setValue(providers.find(value.provider()).map(ModelProviderCatalog.Provider::displayName)
                    .orElse(value.provider()));
            baseUrlField.setText(value.baseUrl());
            apiKeyFieldController.setText(value.apiKey());
            modelNameField.setText(value.modelName());
            dimensionsField.setText(Integer.toString(value.dimensions()));
            retrieveLimitField.setText(Integer.toString(value.retrieveLimit()));
            scoreThresholdField.setText(Double.toString(value.scoreThreshold()));
            applyProfiles(data.profiles());
            selectManaged(value.managedProfileId());
            updateProvider(false);
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
        if (SettingsFieldSupport.isLoading(root)) return;
        updateProvider(true);
        if (selectedProvider().localManaged()) loadManagedProfiles();
    }

    @FXML private void manageLocalModelsRequested() {
        plugins.createServicePluginConfiguration(root.getScene().getWindow(),
                "builtin-deliverance", "models", onRuntimeConfigurationChanged).showAndWait();
    }

    private void preset(String baseUrl, String model, String dimensions, String prompt) {
        baseUrlField.setText(baseUrl);
        modelNameField.setText(model);
        dimensionsField.setText(dimensions);
        apiKeyFieldController.setPromptText(prompt);
    }

    private void enableFields(boolean enabled) {
        for (Node node : List.of(providerCombo, baseUrlField, apiKeyField, modelNameField,
                managedProfileCombo, dimensionsField, retrieveLimitField, scoreThresholdField)) {
            node.setDisable(!enabled);
        }
    }

    private EmbeddingSettings form() {
        ModelProviderCatalog.Provider provider = selectedProvider();
        String managedId = managedProfileCombo.getValue() == null ? ""
                : managedProfileCombo.getValue().value().toString();
        return new EmbeddingSettings(enabledCheck.isSelected(), provider.id(),
                SettingsFieldSupport.text(baseUrlField), apiKeyFieldController.text(),
                SettingsFieldSupport.text(modelNameField),
                SettingsFieldSupport.integer(dimensionsField, 1, Integer.MAX_VALUE, "向量维度"),
                SettingsFieldSupport.integer(retrieveLimitField, 1, Integer.MAX_VALUE, "检索数量"),
                SettingsFieldSupport.decimal(scoreThresholdField, 0, 1, "分数阈值"), managedId);
    }

    private void updateProvider(boolean defaults) {
        ModelProviderCatalog.Provider provider = selectedProvider();
        boolean managed = provider.localManaged();
        visible(baseUrlRow, !managed);
        visible(apiKeyRow, !managed);
        visible(modelNameRow, !managed);
        visible(managedProfileRow, managed);
        dimensionsField.setEditable(!managed);
        if (defaults && !managed) {
            preset(provider.defaultBaseUrl(), provider.defaultEmbeddingModel(),
                    Integer.toString(provider.defaultEmbeddingDimensions()), provider.displayName() + " API 密钥");
        }
    }

    private void loadManagedProfiles() {
        profileRefresh.execute(TaskSpec.io("settings-embedding-local-profiles"),
                context -> embeddingProfiles(), projection -> {
                    if (!selectedProvider().localManaged()) return;
                    SettingsFieldSupport.loading(root, () -> applyProfiles(projection));
                }, ignored -> { });
    }

    private ProfileProjection embeddingProfiles() {
        List<InferenceModelProfile> profiles =
                inference.readyProfiles(InferenceModelProfile.Kind.EMBEDDING);
        return new ProfileProjection(profiles.stream()
                .map(profile -> new InferenceSettingsChoice<>(
                        profile.name(), profile.id()))
                .toList(), profiles.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        InferenceModelProfile::id, InferenceModelProfile::embeddingDimensions)));
    }

    private void applyProfiles(ProfileProjection projection) {
        managedDimensions = projection.dimensions();
        managedProfileCombo.getItems().setAll(projection.choices());
    }

    private ModelProviderCatalog.Provider selectedProvider() {
        return providers.find(providerCombo.getValue()).orElseThrow(
                () -> new IllegalArgumentException("请选择嵌入模型提供商"));
    }

    private void selectManaged(String id) {
        if (id == null || id.isBlank()) { managedProfileCombo.setValue(null); return; }
        managedProfileCombo.getItems().stream().filter(choice -> choice.value().toString().equals(id))
                .findFirst().ifPresent(managedProfileCombo::setValue);
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    void deactivate() {
        refresh.cancel();
        profileRefresh.cancel();
    }

    @Override
    public void close() {
        onApplied = ignored -> { };
        onRuntimeConfigurationChanged = () -> { };
        mutation.close();
        probe.close();
        refresh.close();
        profileRefresh.close();
    }

    private record ViewData(EmbeddingSettings settings, ProfileProjection profiles) { }

    private record ProfileProjection(
            List<InferenceSettingsChoice<UUID>> choices,
            Map<UUID, Integer> dimensions) {
        private static final ProfileProjection EMPTY = new ProfileProjection(List.of(), Map.of());
        private ProfileProjection {
            choices = List.copyOf(choices);
            dimensions = Map.copyOf(dimensions);
        }
    }
}
