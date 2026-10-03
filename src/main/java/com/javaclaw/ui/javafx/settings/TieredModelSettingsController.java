package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Tier;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;
import com.javaclaw.application.settings.ModelProviderCatalog;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.platform.dialog.DialogService;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.UUID;

/** 分级模型设置 Controller。 */
public final class TieredModelSettingsController
        implements ModelSettingsSectionFactory.AppliedSettingsController, AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ToggleSwitch normalEnabledCheck;
    @FXML private ComboBox<String> normalProviderCombo;
    @FXML private Node normalBaseUrlRow;
    @FXML private Node normalModelNameRow;
    @FXML private Node normalApiKeyRow;
    @FXML private Node normalManagedProfileRow;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> normalManagedProfileCombo;
    @FXML private TextField normalBaseUrlField;
    @FXML private ComboBox<String> normalModelNameField;
    @FXML private Button normalDiscoveryRefreshButton;
    @FXML private Label normalDiscoveryStatusLabel;
    @FXML private Node normalApiKeyField;
    @FXML private SecretFieldController normalApiKeyFieldController;
    @FXML private ToggleSwitch normalThinkingCheck;
    @FXML private ToggleSwitch lightEnabledCheck;
    @FXML private ComboBox<String> lightProviderCombo;
    @FXML private Node lightBaseUrlRow;
    @FXML private Node lightModelNameRow;
    @FXML private Node lightApiKeyRow;
    @FXML private Node lightManagedProfileRow;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> lightManagedProfileCombo;
    @FXML private TextField lightBaseUrlField;
    @FXML private ComboBox<String> lightModelNameField;
    @FXML private Button lightDiscoveryRefreshButton;
    @FXML private Label lightDiscoveryStatusLabel;
    @FXML private Node lightApiKeyField;
    @FXML private SecretFieldController lightApiKeyFieldController;
    @FXML private ToggleSwitch lightThinkingCheck;

    private final ModelSettingsApplicationService useCases;
    private final ModelDiscoveryApplicationService modelDiscovery;
    private final DialogService dialogs;
    private final ModelProviderCatalog providers;
    private final InferenceManagementApplicationService inference;
    private final PluginCenterViewFactory plugins;
    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;
    private final UiAsyncAction<SaveResult> mutation;
    private final UiAsyncAction<ViewData> refresh;
    private final UiAsyncAction<List<InferenceSettingsChoice<UUID>>> profileRefresh;
    private final TieredModelDiscovery tierDiscovery = new TieredModelDiscovery();
    private ModelDiscoveryCombo normalChoices;
    private ModelDiscoveryCombo lightChoices;
    private String normalProviderId;
    private String lightProviderId;
    private Consumer<SaveResult> onApplied = ignored -> { };
    private Runnable onRuntimeConfigurationChanged = () -> { };

    public TieredModelSettingsController(
            ModelSettingsApplicationService useCases,
            ModelDiscoveryApplicationService modelDiscovery,
            ModelProviderCatalog providers,
            InferenceManagementApplicationService inference,
            PluginCenterViewFactory plugins,
            WorkspaceContext workspace,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.modelDiscovery = Objects.requireNonNull(modelDiscovery, "modelDiscovery");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.inference = Objects.requireNonNull(inference, "inference");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fx = Objects.requireNonNull(fx, "fx");
        Objects.requireNonNull(workspace, "workspace");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        mutation = new UiAsyncAction<>(tasks, fx);
        refresh = new UiAsyncAction<>(tasks, fx);
        profileRefresh = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        normalChoices = new ModelDiscoveryCombo(normalModelNameField,
                normalDiscoveryRefreshButton, normalDiscoveryStatusLabel,
                modelDiscovery, () -> discoveryRequest(false), tasks, fx);
        lightChoices = new ModelDiscoveryCombo(lightModelNameField,
                lightDiscoveryRefreshButton, lightDiscoveryStatusLabel,
                modelDiscovery, () -> discoveryRequest(true), tasks, fx);
        normalBaseUrlField.textProperty().addListener((ignored, oldValue, newValue) ->
                baseUrlChanged(false, newValue));
        normalApiKeyFieldController.textProperty().addListener((ignored, oldValue, newValue) -> {
            if (!SettingsFieldSupport.isLoading(root)) rememberDiscoveryCredential(false);
            normalChoices.scheduleRefresh();
        });
        lightBaseUrlField.textProperty().addListener((ignored, oldValue, newValue) ->
                baseUrlChanged(true, newValue));
        lightApiKeyFieldController.textProperty().addListener((ignored, oldValue, newValue) -> {
            if (!SettingsFieldSupport.isLoading(root)) rememberDiscoveryCredential(true);
            lightChoices.scheduleRefresh();
        });
        List<String> chatProviders = providers.providers().stream()
                .filter(provider -> provider.capabilities().contains(ModelProviderCatalog.Capability.CHAT))
                .map(ModelProviderCatalog.Provider::displayName).toList();
        normalProviderCombo.getItems().setAll(chatProviders);
        lightProviderCombo.getItems().setAll(chatProviders);
        normalEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableNormal(enabled));
        lightEnabledCheck.selectedProperty().addListener(
                (ignored, previous, enabled) -> enableLight(enabled));
    }

    @Override
    public void configure(
            Consumer<SaveResult> callback, Runnable runtimeConfigurationChanged) {
        onApplied = Objects.requireNonNull(callback, "callback");
        onRuntimeConfigurationChanged = Objects.requireNonNull(
                runtimeConfigurationChanged, "runtimeConfigurationChanged");
    }

    public void reload() {
        refresh.execute(TaskSpec.io("settings-tiered-model-load"), context -> {
            var snapshot = useCases.snapshot();
            TierSettings value = snapshot.tiers();
            boolean local = localProvider(value.normal().provider())
                    || localProvider(value.light().provider());
            return new ViewData(value, snapshot.model(),
                    local ? generationProfiles() : List.of());
        }, this::applySnapshot, ignored -> { });
    }

    private void applySnapshot(ViewData data) {
        TierSettings value = data.settings();
        tierDiscovery.setHighModel(data.highModel());
        SettingsFieldSupport.loading(root, () -> {
            normalManagedProfileCombo.getItems().setAll(data.profiles());
            lightManagedProfileCombo.getItems().setAll(data.profiles());
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

    @FXML private void normalProviderChanged() { providerChanged(false); }
    @FXML private void lightProviderChanged() { providerChanged(true); }

    private void providerChanged(boolean light) {
        if (SettingsFieldSupport.isLoading(root)) return;
        String providerId = selectedProviderId(light);
        boolean changed = !providerId.equals(light ? lightProviderId : normalProviderId);
        if (light) lightProviderId = providerId; else normalProviderId = providerId;
        if (changed) (light ? lightApiKeyFieldController : normalApiKeyFieldController).setText("");
        updateProvider(light, changed);
        if (selectedLocal(light ? lightProviderCombo : normalProviderCombo)) loadManagedProfiles();
        else (light ? lightChoices : normalChoices).scheduleRefresh();
    }

    @FXML private void manageLocalModelsRequested() {
        plugins.createServicePluginConfiguration(root.getScene().getWindow(),
                "builtin-deliverance", "models", onRuntimeConfigurationChanged).showAndWait();
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
        ModelDiscoveryCombo choices = light ? lightChoices : normalChoices;
        SecretFieldController secret = light
                ? lightApiKeyFieldController : normalApiKeyFieldController;
        ToggleSwitch thinking = light ? lightThinkingCheck : normalThinkingCheck;
        enabled.setSelected(value.enabled());
        provider.setValue(providers.find(value.provider()).map(ModelProviderCatalog.Provider::displayName)
                .orElse(value.provider()));
        baseUrl.setText(value.baseUrl());
        choices.setText(value.modelName());
        secret.setText(value.apiKey());
        thinking.setSelected(value.thinkingEnabled());
        ComboBox<InferenceSettingsChoice<UUID>> managed = light
                ? lightManagedProfileCombo : normalManagedProfileCombo;
        selectManaged(managed, value.managedProfileId());
        updateProvider(light, false);
        if (light) enableLight(value.enabled()); else enableNormal(value.enabled());
        String providerId = ModelDiscoveryCombo.provider(providers, provider.getValue())
                .map(ModelProviderCatalog.Provider::id).orElse("");
        if (light) lightProviderId = providerId; else normalProviderId = providerId;
        rememberDiscoveryCredential(light);
    }

    private Tier form(boolean light) {
        boolean enabled = (light ? lightEnabledCheck : normalEnabledCheck).isSelected();
        ComboBox<String> provider = light ? lightProviderCombo : normalProviderCombo;
        TextField baseUrl = light ? lightBaseUrlField : normalBaseUrlField;
        ModelDiscoveryCombo choices = light ? lightChoices : normalChoices;
        SecretFieldController secret = light
                ? lightApiKeyFieldController : normalApiKeyFieldController;
        ToggleSwitch thinking = light ? lightThinkingCheck : normalThinkingCheck;
        ModelProviderCatalog.Provider selected = ModelDiscoveryCombo.provider(providers,
                provider.getValue()).orElse(null);
        if (selected == null && enabled) throw new IllegalArgumentException("请选择模型提供商");
        ComboBox<InferenceSettingsChoice<UUID>> managed = light
                ? lightManagedProfileCombo : normalManagedProfileCombo;
        String managedId = managed.getValue() == null ? "" : managed.getValue().value().toString();
        return new Tier(enabled, selected == null ? "" : selected.id(), SettingsFieldSupport.text(baseUrl),
                choices.text(), tierDiscovery.ownKeyFor(light,
                        selected == null ? "" : selected.id(), SettingsFieldSupport.text(baseUrl),
                        secret.text()), thinking.isSelected(), managedId);
    }

    private void enableNormal(boolean enabled) {
        SettingsFieldSupport.setDisabled(!enabled, normalProviderCombo, normalBaseUrlField, normalModelNameField,
                normalApiKeyField, normalManagedProfileCombo, normalThinkingCheck,
                normalDiscoveryRefreshButton);
        normalChoices.setActive(enabled && !selectedLocal(normalProviderCombo));
    }

    private void enableLight(boolean enabled) {
        SettingsFieldSupport.setDisabled(!enabled, lightProviderCombo, lightBaseUrlField, lightModelNameField,
                lightApiKeyField, lightManagedProfileCombo, lightThinkingCheck,
                lightDiscoveryRefreshButton);
        lightChoices.setActive(enabled && !selectedLocal(lightProviderCombo));
    }

    private void updateProvider(boolean light, boolean applyDefaults) {
        ComboBox<String> providerBox = light ? lightProviderCombo : normalProviderCombo;
        ModelProviderCatalog.Provider provider = ModelDiscoveryCombo.provider(providers,
                providerBox.getValue()).orElse(null);
        if (provider == null) return;
        visible(light ? lightBaseUrlRow : normalBaseUrlRow, !provider.localManaged());
        visible(light ? lightModelNameRow : normalModelNameRow, !provider.localManaged());
        visible(light ? lightApiKeyRow : normalApiKeyRow, !provider.localManaged());
        visible(light ? lightManagedProfileRow : normalManagedProfileRow, provider.localManaged());
        if (applyDefaults && !provider.localManaged()) {
            (light ? lightBaseUrlField : normalBaseUrlField).setText(provider.defaultBaseUrl());
            ModelDiscoveryCombo choices = light ? lightChoices : normalChoices;
            choices.setText(provider.defaultChatModel());
        }
        (light ? lightChoices : normalChoices).setActive(
                (light ? lightEnabledCheck : normalEnabledCheck).isSelected()
                        && !provider.localManaged());
    }

    private void loadManagedProfiles() {
        profileRefresh.execute(TaskSpec.io("settings-tiered-local-profiles"),
                context -> generationProfiles(), values -> SettingsFieldSupport.loading(root, () -> {
                    normalManagedProfileCombo.getItems().setAll(values);
                    lightManagedProfileCombo.getItems().setAll(values);
                }), ignored -> { });
    }

    private List<InferenceSettingsChoice<UUID>> generationProfiles() {
        return inference.readyProfiles(InferenceModelProfile.Kind.GENERATION).stream()
                .map(profile -> new InferenceSettingsChoice<>(
                        profile.name(), profile.id()))
                .toList();
    }

    private boolean localProvider(String value) {
        return providers.find(value).map(ModelProviderCatalog.Provider::localManaged).orElse(false);
    }

    private boolean selectedLocal(ComboBox<String> box) {
        return ModelDiscoveryCombo.provider(providers, box.getValue())
                .map(ModelProviderCatalog.Provider::localManaged)
                .orElse(false);
    }

    private DiscoveryRequest discoveryRequest(boolean light) {
        String providerId = selectedProviderId(light);
        String url = SettingsFieldSupport.text(light ? lightBaseUrlField : normalBaseUrlField);
        String key = (light ? lightApiKeyFieldController : normalApiKeyFieldController).text();
        return tierDiscovery.request(light, providerId, url, key);
    }

    private String selectedProviderId(boolean light) {
        ComboBox<String> box = light ? lightProviderCombo : normalProviderCombo;
        return ModelDiscoveryCombo.provider(providers, box.getValue())
                .map(ModelProviderCatalog.Provider::id).orElse("");
    }

    private void baseUrlChanged(boolean light, String value) {
        if (!SettingsFieldSupport.isLoading(root)
                && tierDiscovery.requiresNewKey(light, selectedProviderId(light), value))
            (light ? lightApiKeyFieldController : normalApiKeyFieldController).setText("");
        (light ? lightChoices : normalChoices).scheduleRefresh();
    }

    private void rememberDiscoveryCredential(boolean light) {
        tierDiscovery.capture(light, selectedProviderId(light),
                SettingsFieldSupport.text(light ? lightBaseUrlField : normalBaseUrlField));
    }

    private static void selectManaged(
            ComboBox<InferenceSettingsChoice<UUID>> box, String id) {
        if (id == null || id.isBlank()) { box.setValue(null); return; }
        box.getItems().stream().filter(choice -> choice.value().toString().equals(id))
                .findFirst().ifPresent(box::setValue);
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value); node.setManaged(value);
    }

    void deactivate() {
        refresh.cancel();
        profileRefresh.cancel();
        normalChoices.cancel();
        lightChoices.cancel();
    }

    @Override
    public void close() {
        onApplied = ignored -> { };
        onRuntimeConfigurationChanged = () -> { };
        mutation.close();
        refresh.close();
        profileRefresh.close();
        normalChoices.close();
        lightChoices.close();
    }

    private record ViewData(
            TierSettings settings,
            ModelSettings highModel,
            List<InferenceSettingsChoice<UUID>> profiles) { }
}
