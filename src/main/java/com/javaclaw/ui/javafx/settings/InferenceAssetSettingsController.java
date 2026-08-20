package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Searchable anonymous Hugging Face catalog plus the plugin-managed local model directory. */
public final class InferenceAssetSettingsController implements AutoCloseable {
    @FXML private VBox root;
    @FXML private Label modelPurposeLabel, onlineModelTitleLabel, onlineModelMetaLabel,
            localModelTitleLabel, localModelMetaLabel, onlinePageLabel, assetProgressLabel, statusLabel,
            onlineCatalogStateLabel, onlineUpdatedLabel;
    @FXML private TextField onlineSearchField, localSearchField, onlineRepositoryField, onlineCommitField;
    @FXML private ListView<InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary>>
            onlineModelList;
    @FXML private TextArea onlineModelDetailArea, localModelDetailArea;
    @FXML private Button onlineDownloadButton, onlineNextButton, loadSelectedAssetButton,
            unloadSelectedAssetButton, deleteAssetButton, importLocalButton, emptyLocalCatalogButton,
            onlineRetryButton, openAssetDirectoryButton;
    @FXML private ListView<InferenceSettingsChoice<UUID>> assetList;
    @FXML private javafx.scene.control.ProgressBar assetProgress, onlineCatalogProgress;
    @FXML private TabPane modelSourceTabs;
    @FXML private Tab onlineModelsTab, localModelsTab;

    private final InferenceManagementApplicationService useCases;
    private final FxDispatcher fx;
    private final ManagedTaskExecutor tasks;
    private final ExternalDirectoryOpener directories;
    private final InferenceSettingsUiActions ui;
    private InferenceManagementApplicationService.Snapshot snapshot;
    private Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> runtimeStatuses = Map.of();
    private InferenceModelProfile.Kind selectedKind = InferenceModelProfile.Kind.GENERATION;
    private Runnable reloadAll = () -> { };
    private BiConsumer<InferenceModelAsset, Set<InferenceModelProfile.Kind>> modelSelected = (ignored, purposes) -> reloadAll.run();
    private HuggingFaceModelCatalogPort.ModelDetail onlineDetail;
    private long detailRequestSerial;
    private boolean onlineInitialized;
    private Mode mode = Mode.LEGACY;
    private Runnable openCatalog = () -> { };
    private InferenceAssetProgressView progressView;
    private InferenceOnlineCatalogCoordinator onlineCatalog;
    private List<InferenceSettingsChoice<UUID>> localModels = List.of();

    enum Mode { LEGACY, CATALOG, LOCAL_ONLY_PICKER }

    public InferenceAssetSettingsController(
            InferenceManagementApplicationService useCases, DialogService dialogs,
            ManagedTaskExecutor tasks, FxDispatcher fx, ExternalDirectoryOpener directories) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.directories = Objects.requireNonNull(directories, "directories");
        ui = new InferenceSettingsUiActions(dialogs, tasks, fx);
    }

    @FXML
    private void initialize() {
        ui.attach(statusLabel);
        progressView = new InferenceAssetProgressView(fx, assetProgress, assetProgressLabel);
        modelSourceTabs.getSelectionModel().selectFirst();
        onlineModelList.setCellFactory(InferenceModelListCells.online(modelType -> InferenceModelPurposeClassifier.purposeLabel(previewPurposes(modelType))));
        assetList.setCellFactory(InferenceModelListCells.local(this::asset, this::hasLoadedProfile));
        onlineCatalog = new InferenceOnlineCatalogCoordinator(useCases, tasks, fx,
                onlineSearchField, onlineModelList, onlineCatalogStateLabel, onlineUpdatedLabel,
                onlinePageLabel, onlineCatalogProgress, onlineRetryButton, onlineNextButton, root,
                (javafx.scene.control.SplitPane) onlineModelsTab.getContent(), (javafx.scene.control.SplitPane) localModelsTab.getContent(), count -> onlineModelsTab.setText("在线模型 " + count));
        onlineModelList.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> inspectOnline(selected));
        assetList.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> updateLocalDetail(selected));
        localSearchField.textProperty().addListener((ignored, previous, value) -> renderLocalModels());
        updateActions();
    }

    void configure(Runnable reload) {
        reloadAll = Objects.requireNonNull(reload, "reload");
        modelSelected = (ignored, purposes) -> reloadAll.run();
    }

    void configure(Runnable reload, InferenceModelProfile.Kind kind, BiConsumer<InferenceModelAsset, Set<InferenceModelProfile.Kind>> selected) {
        configure(reload, kind, selected, Mode.LEGACY, () -> { });
    }

    void configure(Runnable reload, InferenceModelProfile.Kind kind,
                   BiConsumer<InferenceModelAsset, Set<InferenceModelProfile.Kind>> selected,
                   Mode mode, Runnable openCatalog) {
        reloadAll = Objects.requireNonNull(reload, "reload");
        selectedKind = Objects.requireNonNull(kind, "kind");
        modelSelected = Objects.requireNonNull(selected, "selected");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.openCatalog = Objects.requireNonNull(openCatalog, "openCatalog");
        boolean picker = mode == Mode.LOCAL_ONLY_PICKER;
        if (picker) {
            javafx.scene.Node pickerContent = localModelsTab.getContent();
            localModelsTab.setContent(null); root.getChildren().set(root.getChildren().indexOf(modelSourceTabs), pickerContent);
            fx.dispatchLater(() -> { root.applyCss(); root.layout(); });
        }
        InferenceProfilePresentation.show(importLocalButton, !picker);
        InferenceProfilePresentation.show(loadSelectedAssetButton, mode != Mode.CATALOG);
        InferenceProfilePresentation.show(unloadSelectedAssetButton, mode == Mode.LEGACY);
        InferenceProfilePresentation.show(deleteAssetButton, !picker);
        InferenceProfilePresentation.show(emptyLocalCatalogButton, picker);
        updatePurposePresentation();
    }

    void apply(InferenceManagementApplicationService.Snapshot value) {
        apply(value, Map.of());
    }

    void apply(InferenceManagementApplicationService.Snapshot value, Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses) {
        snapshot = Objects.requireNonNull(value, "value");
        runtimeStatuses = statuses == null ? Map.of() : Map.copyOf(statuses);
        UUID selectedId = selectedModelId();
        localModels = value.assets().stream()
                .filter(asset -> {
                    Set<InferenceModelProfile.Kind> compatible = purposes(asset);
                    return compatible.isEmpty() || mode != Mode.LEGACY
                            || compatible.contains(selectedKind);
                })
                .map(this::choice).toList();
        renderLocalModels();
        localModelsTab.setText("本地模型 " + localModels.size());
        localModels.stream().filter(item -> item.value().equals(selectedId)).findFirst()
                .ifPresent(assetList.getSelectionModel()::select);
        if (assetList.getSelectionModel().getSelectedItem() == null && !assetList.getItems().isEmpty()) {
            assetList.getSelectionModel().selectFirst();
        }
        updatePurposePresentation();
        updateActions();
        if (mode != Mode.LOCAL_ONLY_PICKER && !onlineInitialized) {
            onlineInitialized = true;
            onlineCatalog.searchFirstPage();
        }
    }

    @FXML private void searchOnlineRequested() { onlineCatalog.searchFirstPage(); }
    @FXML private void refreshOnlineRequested() { onlineCatalog.refresh(); }
    @FXML private void retryOnlineRequested() { onlineCatalog.retry(); }
    @FXML private void nextOnlineRequested() { onlineCatalog.next(); }

    private void inspectOnline(
            InferenceSettingsChoice<HuggingFaceModelCatalogPort.ModelSummary> selected) {
        long serial = ++detailRequestSerial;
        onlineDetail = null;
        onlineDownloadButton.setDisable(true);
        onlineRepositoryField.setText(selected == null ? "" : selected.value().repository());
        onlineCommitField.setText(selected == null ? "" : selected.value().commit());
        if (selected == null) return;
        var model = selected.value();
        onlineModelTitleLabel.setText(model.displayName());
        onlineModelMetaLabel.setText(model.repository() + " · " + model.quantizationType()
                + " · " + humanBytes(model.quantizedSizeBytes()));
        onlineModelDetailArea.setText("正在解析并验证仓库详情…");
        ui.run("读取在线模型详情", context -> useCases.onlineModelDetail(
                selected.value().repository(), context.cancellation()::isCancellationRequested), result -> {
            if (serial != detailRequestSerial) return;
            onlineDetail = (HuggingFaceModelCatalogPort.ModelDetail) result;
            renderOnlineDetail(onlineDetail);
        });
    }

    private void renderOnlineDetail(HuggingFaceModelCatalogPort.ModelDetail detail) {
        var model = detail.summary();
        onlineModelTitleLabel.setText(model.repository());
        onlineModelMetaLabel.setText(model.quantizationType() + " · "
                + humanBytes(model.quantizedSizeBytes()) + " · 更新于 "
                + InferenceModelPresentation.instant(model.lastModified()));
        onlineRepositoryField.setText(model.repository());
        onlineCommitField.setText(model.commit());
        onlineModelDetailArea.setText(InferenceModelPresentation.onlineDetail(
                detail, previewPurposes(model.modelType())));
        onlineDownloadButton.setDisable(!detail.downloadable());
        onlineDownloadButton.setText(detail.downloadable() ? "下载到插件目录" : "门控仓库不可匿名下载");
        ui.status(detail.downloadable() ? "模型详情已验证" : "门控仓库仅供查看");
    }

    @FXML
    private void downloadSelectedOnlineRequested() {
        HuggingFaceModelCatalogPort.ModelDetail detail = onlineDetail;
        if (detail == null || !detail.downloadable()) return;
        var model = detail.summary();
        ui.confirm("下载在线模型", "将固定下载 " + model.repository() + "@" + model.commit()
                + "（" + model.quantizationType() + "，" + humanBytes(model.quantizedSizeBytes())
                + "）到 Deliverance 插件 data/models，确定继续？", context ->
                useCases.downloadOnlineModel(detail, progressView::update,
                        context.cancellation()::isCancellationRequested), result -> {
            InferenceModelAsset asset = (InferenceModelAsset) result;
            reloadAll.run();
            ui.status(asset.displayName() + " 已下载并通过本地兼容性校验");
        });
    }

    @FXML
    private void loadSelectedAssetRequested() {
        if (mode == Mode.CATALOG) return;
        InferenceModelAsset model = selectedAsset();
        Set<InferenceModelProfile.Kind> purposes = model == null ? Set.of() : purposes(model);
        if (model != null && (mode == Mode.LOCAL_ONLY_PICKER
                ? !purposes.isEmpty() : purposes.contains(selectedKind))) {
            modelSelected.accept(model, purposes);
        }
    }

    @FXML private void openCatalogRequested() { openCatalog.run(); }

    @FXML private void copyOnlineRepositoryRequested() {
        InferenceApiConsoleActions.copy(onlineRepositoryField.getText(), "模型仓库", ui);
    }

    @FXML private void copyOnlineCommitRequested() {
        InferenceApiConsoleActions.copy(onlineCommitField.getText(), "模型 commit", ui);
    }

    @FXML private void openAssetDirectoryRequested() {
        InferenceModelAsset selected = selectedAsset();
        if (selected != null) directories.open(java.nio.file.Path.of(selected.location()));
    }

    @FXML
    private void unloadSelectedAssetRequested() {
        InferenceModelAsset asset = selectedAsset();
        if (asset == null || snapshot == null) return;
        snapshot.profiles().stream().filter(profile -> profile.assetId().equals(asset.id()))
                .filter(profile -> runtimeStatuses.containsKey(profile.id())).findFirst()
                .ifPresent(profile -> ui.run("卸载本地模型", context -> {
                    useCases.setProfileRunning(profile.id(), false);
                    return null;
                }, ignored -> {
                    reloadAll.run();
                    ui.status("模型已从内存卸载，本地文件仍保留");
                }));
    }

    @FXML
    private void importLocalRequested() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 Hugging Face 模型目录");
        var selected = chooser.showDialog(root.getScene().getWindow());
        if (selected == null) return;
        ui.run("添加本地模型", context -> useCases.importLocal(selected.toPath(), progressView::update,
                context.cancellation()::isCancellationRequested), result -> {
            reloadAll.run();
            ui.status(((InferenceModelAsset) result).displayName() + " 已复制到插件目录并完成校验");
        });
    }

    @FXML
    private void deleteAssetRequested() {
        InferenceModelAsset selected = selectedAsset();
        if (selected == null) return;
        ui.confirm("删除模型", "将删除插件 data/models 中未被配置引用的模型；此操作不可撤销，确定继续？",
                context -> {
                    useCases.deleteAsset(selected.id());
                    return null;
                }, ignored -> reloadAll.run());
    }

    private void updateLocalDetail(InferenceSettingsChoice<UUID> selected) {
        InferenceModelAsset asset = selected == null ? null : asset(selected.value());
        if (asset == null) {
            localModelDetailArea.clear();
            localModelTitleLabel.setText("选择本地模型");
            localModelMetaLabel.setText("查看插件内路径、来源、用途与加载状态");
            updateActions();
            return;
        }
        Set<InferenceModelProfile.Kind> supported = purposes(asset);
        boolean loaded = hasLoadedProfile(asset.id());
        localModelDetailArea.setText(InferenceModelPresentation.localDetail(
                asset, supported, loaded));
        localModelTitleLabel.setText(asset.displayName());
        localModelMetaLabel.setText((loaded ? "已加载" : "本地可用") + " · "
                + asset.quantizationType() + " · " + humanBytes(asset.sizeBytes()));
        updateActions();
    }

    private void updateActions() {
        InferenceModelAsset selected = selectedAsset();
        boolean loadable = selected != null && selected.state() == InferenceModelAsset.State.READY
                && (mode == Mode.LOCAL_ONLY_PICKER ? !purposes(selected).isEmpty()
                : purposes(selected).contains(selectedKind));
        boolean loaded = selected != null && hasLoadedProfile(selected.id());
        loadSelectedAssetButton.setDisable(mode == Mode.CATALOG || !loadable || loaded);
        unloadSelectedAssetButton.setDisable(!loaded);
        deleteAssetButton.setDisable(selected == null || loaded);
        openAssetDirectoryButton.setDisable(selected == null);
    }

    private void renderLocalModels() {
        if (assetList == null) return;
        String query = localSearchField == null || localSearchField.getText() == null
                ? "" : localSearchField.getText().strip().toLowerCase(java.util.Locale.ROOT);
        assetList.getItems().setAll(localModels.stream().filter(item -> query.isBlank()
                || item.label().toLowerCase(java.util.Locale.ROOT).contains(query)
                || asset(item.value()) != null && asset(item.value()).location()
                        .toLowerCase(java.util.Locale.ROOT).contains(query)).toList());
    }

    private InferenceModelAsset selectedAsset() {
        UUID id = selectedModelId();
        return id == null ? null : asset(id);
    }

    private UUID selectedModelId() {
        var selected = assetList == null ? null : assetList.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.value();
    }

    private InferenceModelAsset asset(UUID id) {
        if (snapshot == null || id == null) return null;
        return snapshot.assets().stream().filter(value -> value.id().equals(id))
                .findFirst().orElse(null);
    }

    private boolean hasLoadedProfile(UUID assetId) {
        return snapshot != null && snapshot.profiles().stream()
                .anyMatch(profile -> profile.assetId().equals(assetId)
                        && runtimeStatuses.containsKey(profile.id()));
    }

    private Set<InferenceModelProfile.Kind> purposes(InferenceModelAsset model) {
        return snapshot == null ? Set.of() : InferenceModelPurposeClassifier.purposes(
                model, snapshot.runtimes(), snapshot.profiles());
    }

    private Set<InferenceModelProfile.Kind> previewPurposes(String modelType) {
        return snapshot == null ? Set.of() : InferenceModelPurposeClassifier.purposes(
                modelType, snapshot.runtimes());
    }

    private InferenceSettingsChoice<UUID> choice(InferenceModelAsset model) {
        boolean loaded = hasLoadedProfile(model.id());
        return new InferenceSettingsChoice<>(
                InferenceModelPresentation.localChoice(model, loaded), model.id());
    }

    private void updatePurposePresentation() {
        if (modelPurposeLabel != null) {
            modelPurposeLabel.setText(mode == Mode.LOCAL_ONLY_PICKER
                    ? "选择本地" + InferenceModelPurposeClassifier.kindLabel(selectedKind)
                    : mode == Mode.CATALOG ? "模型目录" : InferenceModelPurposeClassifier.kindLabel(selectedKind));
        }
    }

    static String humanBytes(long value) {
        return InferenceModelPresentation.bytes(value);
    }

    void cancel() {
        ++detailRequestSerial;
        onlineCatalog.cancel();
        ui.cancel();
    }

    @Override
    public void close() {
        ++detailRequestSerial;
        onlineCatalog.close();
        onlineModelList.setCellFactory(null);
        assetList.setCellFactory(null);
        modelSelected = (ignored, purposes) -> { };
        openCatalog = () -> { };
        ui.close();
    }
}
