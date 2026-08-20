package com.javaclaw.ui.javafx.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.control.JsonSchemaForm;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static com.javaclaw.ui.javafx.settings.InferenceProfilePresentation.*;

/** 模型档案、动态参数与当前工作区四档绑定。 */
public final class InferenceProfileSettingsController implements AutoCloseable {
    @FXML private VBox catalogPanel, parametersPanel, emptyProfileState;
    @FXML private VBox embeddingDimensionsBox, loadParametersBox, generationParametersSection;
    @FXML private VBox generationParametersBox, generationBindingsBox, embeddingBindingBox;
    @FXML private Label modelPurposeLabel, modelLoadSummaryLabel, recommendedCapacityLabel;
    @FXML private Label memoryWarningLabel, profilePurposeLabel, bindingStatusLabel, statusLabel;
    @FXML private ListView<InferenceSettingsChoice<UUID>> profileList;
    @FXML private Button runtimeProfileButton, editProfileButton, parameterSaveProfileButton;
    @FXML private Button backToModelsFromParametersButton;
    @FXML private Button retryBindingsButton;
    @FXML private MenuButton profileActionsMenu;
    @FXML private MenuItem deleteProfileItem;
    @FXML private TextField profileNameField, contextLengthField, embeddingDimensionsField;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> profileAssetCombo, highBindingCombo;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> normalBindingCombo, lightBindingCombo;
    @FXML private ComboBox<InferenceSettingsChoice<UUID>> embeddingBindingCombo;
    @FXML private ComboBox<InferenceSettingsChoice<String>> profileRuntimeCombo;

    private final InferenceManagementApplicationService useCases;
    private final WorkspaceApplicationService workspaces;
    private final ObjectMapper json;
    private final InferenceSettingsUiActions ui;
    private final JsonSchemaForm schemaForms;
    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;
    private InferenceManagementApplicationService.Snapshot snapshot;
    private JsonSchemaForm.Editor loadEditor, generationEditor;
    private Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> runtimeStatuses = Map.of();
    private InferenceModelProfile.Kind selectedKind = InferenceModelProfile.Kind.GENERATION;
    private Runnable reloadAll = () -> { };
    private InferenceProfileLoadFlow loadFlow = InferenceProfileLoadFlow.none();
    private boolean profilePrerequisitesAvailable;
    private InferenceBindingAutosave bindingAutosave;
    private InferenceManagementApplicationService.RecommendedProfile recommendation;

    public InferenceProfileSettingsController(
            InferenceManagementApplicationService useCases, WorkspaceApplicationService workspaces,
            ObjectMapper json, DialogService dialogs, ManagedTaskExecutor tasks, FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.json = Objects.requireNonNull(json, "json");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fx = Objects.requireNonNull(fx, "fx");
        ui = new InferenceSettingsUiActions(dialogs, tasks, fx);
        schemaForms = new JsonSchemaForm(json);
    }

    @FXML
    private void initialize() {
        ui.attach(statusLabel);
        profileList.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> {
                    editProfile(selected == null ? null : selected.value());
                    updateProfileActions();
                });
        profileAssetCombo.valueProperty().addListener(
                (ignored, previous, selected) -> {
                    updateCompatibleRuntimes(null);
                    updateModelLoadSummary();
                });
        bindingAutosave = new InferenceBindingAutosave(useCases, workspaces, tasks, fx,
                highBindingCombo, normalBindingCombo, lightBindingCombo, embeddingBindingCombo,
                bindingStatusLabel, retryBindingsButton, ui::status);
        SettingsFieldSupport.validateInteger(contextLengthField, 0, 2_000_000);
        embeddingDimensionsField.setEditable(false);
        embeddingDimensionsField.setPromptText("测试后自动填写");
        updateKindPresentation();
        showCatalog(true);
        updateProfileActions();
    }

    void configure(
            Runnable reload, Runnable runtimeChanged,
            InferenceModelProfile.Kind kind) {
        reloadAll = Objects.requireNonNull(reload, "reload");
        bindingAutosave.configure(Objects.requireNonNull(runtimeChanged, "runtimeChanged"));
        selectedKind = Objects.requireNonNull(kind, "kind");
        loadFlow = InferenceProfileLoadFlow.of(() -> showCatalog(true), ignored -> {
            reloadAll.run();
            showCatalog(true);
        });
        updateKindPresentation();
    }

    void configure(
            Runnable reload, Runnable runtimeChanged,
            InferenceModelProfile.Kind kind, InferenceProfileLoadFlow flow) {
        configure(reload, runtimeChanged, kind);
        loadFlow = Objects.requireNonNull(flow, "flow");
        backToModelsFromParametersButton.setText("← 返回服务");
    }

    void apply(InferenceManagementApplicationService.Snapshot value) { apply(value, Map.of()); }

    void apply(
            InferenceManagementApplicationService.Snapshot value,
            Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses) {
        UUID selectedId = selectedValue(profileList);
        snapshot = value;
        runtimeStatuses = statuses == null ? Map.of() : Map.copyOf(statuses);
        List<InferenceSettingsChoice<UUID>> assets = assets(value, selectedKind);
        profileAssetCombo.getItems().setAll(assets);
        profileList.getItems().setAll(profiles(value, selectedKind, runtimeStatuses));
        if (selectedId != null) selectList(profileList, selectedId);
        List<InferenceSettingsChoice<UUID>> generation = ready(value, InferenceModelProfile.Kind.GENERATION);
        List<InferenceSettingsChoice<UUID>> embedding = ready(value, InferenceModelProfile.Kind.EMBEDDING);
        bindingAutosave.apply(value.bindings(), generation, embedding);
        updateCompatibleRuntimes(null);
        profilePrerequisitesAvailable = !assets.isEmpty() && !profileRuntimeCombo.getItems().isEmpty();
        if (loadEditor == null) renderParameterForms(null);
        boolean empty = profileList.getItems().isEmpty();
        emptyProfileState.setVisible(empty);
        emptyProfileState.setManaged(empty);
        profileList.setVisible(!empty);
        profileList.setManaged(!empty);
        updateKindPresentation();
        updateModelLoadSummary();
        updateProfileActions();
    }

    void beginCreate(UUID assetId) {
        profileList.getSelectionModel().clearSelection();
        var asset = snapshot == null ? null : snapshot.assets().stream()
                .filter(value -> value.id().equals(assetId)).findFirst().orElse(null);
        profileNameField.setText(asset == null ? "本地模型" : asset.displayName());
        select(profileAssetCombo, assetId);
        updateCompatibleRuntimes(null);
        contextLengthField.setText("0");
        embeddingDimensionsField.clear();
        recommendation = null;
        recommendedCapacityLabel.setText("正在读取本机 CPU、物理内存和模型元数据…");
        memoryWarningLabel.setText("");
        updateKindPresentation();
        renderParameterForms(null);
        updateModelLoadSummary();
        showCatalog(false);
        loadRecommendation(assetId);
    }

    void beginEdit(UUID profileId) {
        selectList(profileList, profileId);
        editProfile(profileId);
        showCatalog(false);
    }

    @FXML
    private void restoreRecommendedRequested() {
        var asset = profileAssetCombo.getValue();
        if (asset != null) loadRecommendation(asset.value());
    }

    private void loadRecommendation(UUID assetId) {
        if (assetId == null) return;
        parameterSaveProfileButton.setDisable(true);
        ui.run("计算本机推荐加载参数", context -> useCases.recommendedProfile(
                assetId, selectedKind, context.cancellation()::isCancellationRequested), result -> {
            recommendation = (InferenceManagementApplicationService.RecommendedProfile) result;
            select(profileRuntimeCombo, recommendation.runtimeId());
            contextLengthField.setText(recommendation.contextLength() <= 0
                    ? "0" : Integer.toString(recommendation.contextLength()));
            renderParameterForms(null, recommendation.loadParameters(),
                    recommendation.defaultParameters());
            renderCapacity(recommendation, recommendedCapacityLabel, memoryWarningLabel);
            updateProfileActions();
            ui.status("已填充本机均衡安全推荐值；内存告警仅提示，不阻止加载");
        });
    }

    @FXML private void catalogRequested() { loadFlow.cancel(); }
    @FXML private void parametersRequested() { showCatalog(false); }

    private void showCatalog(boolean catalog) {
        catalogPanel.setVisible(catalog); catalogPanel.setManaged(catalog);
        parametersPanel.setVisible(!catalog); parametersPanel.setManaged(!catalog);
    }

    @FXML private void saveAndVerifyProfileRequested() {
        InferenceManagementApplicationService.ProfileDraft command = profileForm();
        ui.run("保存并加载本地模型", context -> {
            InferenceModelProfile saved = useCases.saveAndVerifyProfile(command,
                    context.cancellation()::isCancellationRequested);
            try {
                useCases.setProfileRunning(saved.id(), true);
                return new InferenceProfileLoadFlow.Result(saved.id(), true, "");
            } catch (Exception failure) {
                return new InferenceProfileLoadFlow.Result(saved.id(), false,
                        SettingsFieldSupport.failureMessage(failure));
            }
        }, result -> {
            var outcome = (InferenceProfileLoadFlow.Result) result;
            ui.status(outcome.loaded() ? "模型已加载并通过测试"
                    : "运行设置已保存，加载失败：" + outcome.failure());
            loadFlow.complete(outcome);
        });
    }

    @FXML private void runtimeProfileRequested() {
        selectedProfile(id -> {
            boolean running = runtimeStatuses.containsKey(id);
            ui.run(running ? "卸载本地模型" : "加载本地模型", context -> {
                useCases.setProfileRunning(id, !running);
                return null;
            }, ignored -> {
                ui.status(running ? "模型已卸载" : "模型已加载");
                reloadAll.run();
            });
        });
    }

    @FXML private void deleteProfileRequested() {
        selectedProfile(id -> ui.confirm("删除运行设置", "仍被工作区或 API 使用时不能删除，确定继续？",
                context -> { useCases.deleteProfile(id); return null; }, ignored -> reloadAll.run()));
    }

    @FXML private void retryBindingsRequested() { bindingAutosave.retry(); }

    private void editProfile(UUID id) {
        InferenceModelProfile profile = profile(id);
        if (profile == null) return;
        recommendation = null;
        recommendedCapacityLabel.setText("已有模型档案：保留已保存参数，不会被本机推荐值覆盖。");
        memoryWarningLabel.setVisible(false);
        memoryWarningLabel.setManaged(false);
        profileNameField.setText(profile.name());
        select(profileAssetCombo, profile.assetId());
        updateCompatibleRuntimes(profile.runtimeId());
        contextLengthField.setText(Integer.toString(profile.contextLength()));
        embeddingDimensionsField.setText(profile.embeddingDimensions() > 0
                ? Integer.toString(profile.embeddingDimensions()) : "");
        updateKindPresentation();
        renderParameterForms(profile);
        updateModelLoadSummary();
    }

    @FXML private void runtimeForProfileChanged() {
        renderParameterForms(null);
        updateModelLoadSummary();
    }

    private void renderParameterForms(InferenceModelProfile profile) {
        renderParameterForms(profile, profile == null ? null : profile.loadParameters(),
                profile == null ? null : profile.defaultParameters());
    }

    private void renderParameterForms(InferenceModelProfile profile,
                                      Map<String, Object> loadValues,
                                      Map<String, Object> generationValues) {
        var selected = profileRuntimeCombo.getValue();
        String runtimeId = profile == null ? selected == null ? null : selected.value() : profile.runtimeId();
        var runtime = snapshot == null ? null : snapshot.runtimes().stream()
                .filter(value -> value.manifest().runtimeId().equals(runtimeId)).findFirst().orElse(null);
        JsonNode schema = runtime == null ? json.createObjectNode()
                : json.valueToTree(runtime.manifest().parameterSchema());
        JsonNode properties = schema.path("properties");
        loadEditor = schemaForms.render(properties.path("load"), null,
                loadValues == null ? null : json.valueToTree(loadValues), false);
        generationEditor = schemaForms.render(properties.path("generation"), null,
                generationValues == null ? null : json.valueToTree(generationValues), false);
        loadParametersBox.getChildren().setAll(loadEditor.root());
        generationParametersBox.getChildren().setAll(generationEditor.root());
    }

    private InferenceManagementApplicationService.ProfileDraft profileForm() {
        InferenceModelProfile previous = profile(selectedValue(profileList));
        var asset = profileAssetCombo.getValue();
        var runtime = profileRuntimeCombo.getValue();
        if (asset == null || runtime == null) throw new IllegalArgumentException("请选择模型文件和运行组件");
        return new InferenceManagementApplicationService.ProfileDraft(
                previous == null ? UUID.randomUUID() : previous.id(),
                SettingsFieldSupport.text(profileNameField), selectedKind,
                asset.value(), runtime.value(),
                json.convertValue(loadEditor.value(), new TypeReference<Map<String, Object>>() { }),
                json.convertValue(generationEditor.value(), new TypeReference<Map<String, Object>>() { }),
                SettingsFieldSupport.integer(contextLengthField, 0, 2_000_000, "上下文上限"));
    }

    private InferenceModelProfile profile(UUID id) {
        if (snapshot == null || id == null) return null;
        return snapshot.profiles().stream().filter(value -> value.id().equals(id))
                .findFirst().orElse(null);
    }

    private void selectedProfile(Consumer<UUID> operation) {
        UUID selected = selectedValue(profileList);
        if (selected != null) operation.accept(selected);
    }

    private void updateProfileActions() {
        boolean selected = selectedValue(profileList) != null;
        boolean canSave = !profileAssetCombo.getItems().isEmpty()
                && !profileRuntimeCombo.getItems().isEmpty();
        runtimeProfileButton.setDisable(!selected);
        runtimeProfileButton.setText(selected && runtimeStatuses.containsKey(selectedValue(profileList))
                ? "卸载" : "加载");
        editProfileButton.setDisable(!selected);
        parameterSaveProfileButton.setDisable(!profilePrerequisitesAvailable || !canSave);
        profileActionsMenu.setDisable(!selected);
        deleteProfileItem.setDisable(!selected);
    }

    private void updateCompatibleRuntimes(String preferredRuntimeId) {
        if (snapshot == null || profileRuntimeCombo == null) return;
        String current = preferredRuntimeId;
        if (current == null && profileRuntimeCombo.getValue() != null) {
            current = profileRuntimeCombo.getValue().value();
        }
        UUID assetId = profileAssetCombo.getValue() == null
                ? null : profileAssetCombo.getValue().value();
        List<InferenceSettingsChoice<String>> runtimes = compatibleRuntimes(
                snapshot, assetId, selectedKind);
        profileRuntimeCombo.getItems().setAll(runtimes);
        select(profileRuntimeCombo, current);
        if (profileRuntimeCombo.getValue() == null && !runtimes.isEmpty()) {
            profileRuntimeCombo.setValue(runtimes.stream().filter(choice -> snapshot.runtimes().stream()
                    .anyMatch(runtime -> runtime.active()
                            && runtime.manifest().runtimeId().equals(choice.value())))
                    .findFirst().orElse(runtimes.getFirst()));
        }
        profilePrerequisitesAvailable = !profileAssetCombo.getItems().isEmpty()
                && !runtimes.isEmpty();
        updateModelLoadSummary();
        updateProfileActions();
    }

    private void updateModelLoadSummary() {
        if (modelLoadSummaryLabel == null) return;
        var asset = profileAssetCombo.getValue();
        var runtime = profileRuntimeCombo.getValue();
        if (asset == null) { modelLoadSummaryLabel.setText("选择要加载的模型"); return; }
        modelLoadSummaryLabel.setText(asset.label() + (runtime == null
                ? "" : "\n" + runtime.label()));
    }

    private void updateKindPresentation() {
        boolean embedding = selectedKind == InferenceModelProfile.Kind.EMBEDDING;
        String label = InferenceModelPurposeClassifier.kindLabel(selectedKind);
        if (modelPurposeLabel != null) modelPurposeLabel.setText(label);
        if (profilePurposeLabel != null) profilePurposeLabel.setText(label);
        show(embeddingDimensionsBox, embedding); show(generationParametersSection, !embedding);
        show(generationBindingsBox, !embedding); show(embeddingBindingBox, embedding);
    }

    void cancel() { bindingAutosave.cancel(); ui.cancel(); }
    @Override public void close() {
        reloadAll = () -> { };
        loadFlow = InferenceProfileLoadFlow.none();
        bindingAutosave.close(); ui.close();
    }
}
