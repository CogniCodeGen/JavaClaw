package com.javaclaw.ui.javafx.plugin.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 统一插件中心仅调用应用门面；插件 UI 与代码均不进入宿主视图树。 */
public final class UnifiedPluginCenterController implements AutoCloseable {
    @FXML private VBox root, emptyDetail, detailPane, configurationHost, permissionsBox, modelPanelHost, versionsHost;
    @FXML private TextField searchField, downloadUrl;
    @FXML private ComboBox<String> capabilityFilter;
    @FXML private ListView<PluginSummary> pluginList;
    @FXML private TabPane detailTabs;
    @FXML private Label workspaceLabel, countLabel, pluginName, pluginIdentity, enabledLabel, processLabel;
    @FXML private Label descriptionLabel, capabilitiesLabel, artifactLabel, instanceLabel, revisionLabel;
    @FXML private Label dirtyLabel, healthLabel, lastErrorLabel, statusLabel, errorLabel;
    @FXML private Button refreshButton, addLocalButton, addUrlButton, upgradeButton, uninstallButton;
    @FXML private Button enabledButton, saveButton, discardButton, grantButton, cancelButton;
    @FXML private Button startButton, stopButton, restartButton, unquarantineButton, forceStopButton;
    @FXML private CheckBox autoStart;
    @FXML private TextArea logsArea;
    @FXML private ProgressBar progressBar;

    private final PluginManagementService service;
    private final WorkspaceApplicationService workspaces;
    private final DialogService dialogs;
    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;
    private final PluginConfigurationEditor editor;
    private final InferenceManagementApplicationService inference;
    private final ObjectMapper json;
    private PluginInferencePanel modelPanel; private PluginVersionSelector versions;
    private final UnifiedPluginCenterViewModel model = new UnifiedPluginCenterViewModel();
    private final UiAsyncAction<List<PluginSummary>> catalogAction;
    private final UiAsyncAction<PluginDetails> detailsAction;
    private final UiAsyncAction<Diagnostics> diagnosticsAction;
    private PluginPermissionEditor permissionEditor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private PluginCenterOperations operations;
    private PluginDetails selected;
    private String selectedId, workspace, formPlugin, pendingPage;
    private AutoCloseable subscription;
    private boolean updating;
    private Runnable runtimeConfigurationChanged = () -> { };
    private Function<Window, Path> packagePicker = UnifiedPluginCenterController::chooseZip;

    public UnifiedPluginCenterController(PluginManagementService service, WorkspaceApplicationService workspaces,
                                         DialogService dialogs, ManagedTaskExecutor tasks,
                                         FxDispatcher fx, ObjectMapper json, InferenceManagementApplicationService inference) {
        this.service = service;
        this.workspaces = workspaces;
        this.dialogs = dialogs;
        this.tasks = tasks;
        this.fx = fx;
        this.json = json; this.inference = inference;
        editor = new PluginConfigurationEditor(json);
        catalogAction = new UiAsyncAction<>(tasks, fx);
        detailsAction = new UiAsyncAction<>(tasks, fx);
        diagnosticsAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML private void initialize() {
        workspace = workspaces.currentWorkspaceId();
        workspaceLabel.setText("当前工作区：" + workspace);
        operations = new PluginCenterOperations(service, dialogs, tasks, fx, workspace,
                this::applied, this::showFailure, this::showProgress);
        permissionEditor = new PluginPermissionEditor(permissionsBox, operations.busyProperty(), operations::revoke);
        permissionEditor.dirtyProperty().addListener((ignored, before, after) -> updateButtons());
        configurationHost.getChildren().setAll(editor.root());
        versions = new PluginVersionSelector(service, workspace, operations, tasks, fx, this::isDirty, this::showFailure);
        versionsHost.getChildren().setAll(versions.root());
        capabilityFilter.getItems().setAll(UnifiedPluginCenterViewModel.ALL_CAPABILITIES);
        capabilityFilter.getSelectionModel().selectFirst();
        searchField.textProperty().addListener((ignored, before, after) -> filter());
        capabilityFilter.valueProperty().addListener((ignored, before, after) -> filter());
        pluginList.setCellFactory(ignored -> new PluginCell());
        pluginList.getSelectionModel().selectedItemProperty().addListener((ignored, before, after) -> {
            if (!updating && after != null && !after.id().equals(selectedId)) select(after.id());
        });
        editor.dirtyProperty().addListener((ignored, before, after) -> updateButtons());
        operations.busyProperty().addListener((ignored, before, after) -> {
            progressBar.setVisible(after); progressBar.setManaged(after);
            cancelButton.setVisible(after); cancelButton.setManaged(after);
            if (after) { errorLabel.setVisible(false); errorLabel.setManaged(false); progressBar.setProgress(-1); }
            updateButtons();
        });
        subscription = service.observeStatus(event -> {
            if ((event.workspaceId().isBlank() || workspace.equals(event.workspaceId()))
                    && refreshQueued.compareAndSet(false, true)) {
                fx.dispatchLater(() -> { refreshQueued.set(false); if (!closed.get()) refreshRequested(); });
            }
        });
        refreshRequested();
    }

    public void configure(Runnable changed) { runtimeConfigurationChanged = changed == null ? () -> { } : changed; }
    public void openPlugin(String pluginId, String page) {
        pendingPage = page; select(pluginId);
        detailTabs.getSelectionModel().select("configuration".equals(page) || "models".equals(page) || "service".equals(page) ? 1 : "permissions".equals(page) ? 2 : 0);
        if (modelPanel != null) modelPanel.openPage(page);
    }
    void setPackagePicker(Function<Window, Path> picker) { packagePicker = picker; }
    public boolean isClosed() { return closed.get(); }
    public void requestClose(Runnable accepted) { operations.leave(isDirty(), accepted, () -> { }); }
    private boolean configurationDirty() { return editor.isDirty() || modelPanel != null && modelPanel.isDirty(); }
    private boolean isDirty() { return configurationDirty() || permissionEditor != null && permissionEditor.isDirty(); }

    @FXML private void refreshRequested() {
        if (closed.get()) return;
        catalogAction.execute(TaskSpec.io("统一插件目录刷新"), task -> service.list(workspace), values -> {
            if (closed.get()) return;
            model.apply(values);
            String filter = capabilityFilter.getValue();
            updating = true;
            capabilityFilter.getItems().setAll(model.capabilityFilters());
            capabilityFilter.setValue(model.capabilityFilters().contains(filter) ? filter : UnifiedPluginCenterViewModel.ALL_CAPABILITIES);
            updating = false;
            filter();
            if (selectedId != null && values.stream().anyMatch(value -> value.id().equals(selectedId))) loadDetails(false);
            else if (selectedId != null) clearSelection();
            else if (!values.isEmpty()) select(values.getFirst().id());
        }, this::showFailure);
    }

    private void filter() {
        if (updating) return;
        updating = true;
        List<PluginSummary> values = model.filtered(searchField.getText(), capabilityFilter.getValue());
        pluginList.getItems().setAll(values);
        values.stream().filter(value -> value.id().equals(selectedId)).findFirst()
                .ifPresent(value -> pluginList.getSelectionModel().select(value));
        countLabel.setText(values.size() + " / " + model.plugins().size() + " 个插件");
        updating = false;
    }
    private void select(String id) {
        operations.leave(isDirty(), () -> {
            if (!id.equals(selectedId)) {
                selected = null; formPlugin = null; editor.close();
                closeModelPanel();
                detailPane.setVisible(false); detailPane.setManaged(false);
                emptyDetail.setVisible(true); emptyDetail.setManaged(true); updateButtons();
            }
            selectedId = id;
            loadDetails(true);
            filter();
        }, this::filter);
    }
    private void loadDetails(boolean replaceForm) {
        String id = selectedId;
        detailsAction.execute(TaskSpec.io("插件详情"), task -> service.details(workspace, id), value -> {
            if (id.equals(selectedId)) render(value, replaceForm, replaceForm);
        }, this::showFailure);
        diagnosticsAction.execute(TaskSpec.io("插件运行日志"), task -> service.diagnostics(workspace, id), value -> {
            if (id.equals(selectedId)) renderDiagnostics(value);
        }, this::showFailure);
    }
    private void render(PluginDetails details, boolean replaceForm, boolean replacePermissions) {
        selected = details;
        versions.update(details);
        PluginSummary value = details.summary();
        detailPane.setVisible(true); detailPane.setManaged(true);
        emptyDetail.setVisible(false); emptyDetail.setManaged(false);
        pluginName.setText(value.name()); pluginIdentity.setText(value.id() + " · " + value.version());
        enabledLabel.setText(value.enabled() ? "已启用" : "已停用");
        processLabel.setText("进程：" + UnifiedPluginCenterViewModel.stateText(value.state()));
        descriptionLabel.setText(value.description());
        capabilitiesLabel.setText("提供能力：" + String.join("、", value.capabilities()) + "\n贡献："
                + details.contributions().stream().map(item -> item.name() + "（" + item.capability() + "）")
                .collect(java.util.stream.Collectors.joining("、")));
        artifactLabel.setText("发布者：" + value.publisher() + " · " + (value.signatureVerified() ? "签名已验证" : "签名未验证")
                + "\nSHA-256：" + value.artifactHash());
        if (replaceForm || !value.id().equals(formPlugin) || (!editor.isDirty() && editor.revision() != details.configuration().revision())) {
            loadForm(details);
        }
        revisionLabel.setText("编辑配置版本 " + editor.revision() + " · 当前版本 " + details.configuration().revision());
        permissionEditor.apply(details, replacePermissions);
        renderModelPanel(details);
        updating = true; autoStart.setSelected(value.startupPolicy() == StartupPolicy.AUTO_START); updating = false;
        updateButtons();
    }
    private void loadForm(PluginDetails value) {
        var config = value.configuration();
        editor.load(config.revision(), config.schemaJson(), value.uiSchemaJson(), config.configurationJson(), config.configuredSecrets());
        formPlugin = value.summary().id();
    }
    private void renderModelPanel(PluginDetails value) {
        if (modelPanel != null && !value.summary().id().equals(modelPanelHost.getUserData())) closeModelPanel();
        if (!value.summary().capabilities().contains("MODEL") || inference == null) return;
        if (modelPanel == null) {
            modelPanel = new PluginInferencePanel(value, workspace, service, inference, workspaces, dialogs, tasks, fx,
                    json, () -> runtimeConfigurationChanged.run(), this::showFailure,
                    (accepted, rejected) -> operations.leave(isDirty(), accepted, rejected));
            modelPanelHost.getChildren().setAll(modelPanel.root()); modelPanelHost.setUserData(value.summary().id());
            modelPanel.dirtyProperty().addListener((ignored, before, after) -> updateButtons());
        } else modelPanel.update(value); if (pendingPage != null) { modelPanel.openPage(pendingPage); pendingPage = null; }
    }
    private void closeModelPanel() {
        try { if (modelPanel != null) modelPanel.close(); }
        finally { modelPanel = null; modelPanelHost.getChildren().clear(); }
    }
    private void renderDiagnostics(Diagnostics value) {
        instanceLabel.setText("当前实例：" + (value.instanceId() == null ? "尚未创建" : value.instanceId()) + " · PID " + value.pid());
        healthLabel.setText("健康状态：" + value.health());
        lastErrorLabel.setText(value.lastError() == null ? "" : value.lastError());
        logsArea.setText(String.join("\n", value.logs()));
    }

    @FXML private void addLocalRequested() { choosePackage(null); }
    @FXML private void upgradeRequested() { if (selected != null) choosePackage(selected.summary().id()); }
    private void choosePackage(String expectedId) {
        Path path = packagePicker.apply(owner());
        if (path != null) operations.leave(isDirty(), () -> operations.install(path, expectedId), () -> { });
    }
    @FXML private void addUrlRequested() {
        try { URI source = URI.create(downloadUrl.getText().strip());
            operations.leave(isDirty(), () -> operations.install(source, null), () -> { });
        } catch (IllegalArgumentException invalid) { showFailure(invalid); }
    }
    @FXML private void enabledRequested() { if (selected != null) operations.enable(selected); }
    @FXML private void saveRequested() {
        if (selected == null) return;
        try {
            Map<String, SecretChange> secrets = new LinkedHashMap<>();
            editor.secretChanges().forEach((name, edit) -> secrets.put(name,
                    new SecretChange(SecretAction.valueOf(edit.action().name()), edit.value())));
            operations.save(selectedId, new ConfigurationUpdate(editor.revision(), editor.configurationJson(),
                    secrets, selected.configuration().grantedPermissions()), false);
        } catch (IllegalArgumentException invalid) { showFailure(invalid); }
    }
    @FXML private void grantRequested() {
        if (selected == null || configurationDirty()) return;
        Set<String> granted = permissionEditor.grantedPermissions();
        var config = selected.configuration();
        operations.save(selectedId, new ConfigurationUpdate(permissionEditor.revision(), config.configurationJson(), Map.of(), granted), true);
    }
    @FXML private void discardRequested() {
        if (selected != null) operations.leave(isDirty(), () -> { loadForm(selected); permissionEditor.apply(selected, true); if (modelPanel != null) modelPanel.discard(); }, () -> { });
    }
    @FXML private void uninstallRequested() {
        if (selected != null) { String id = selectedId; operations.leave(isDirty(), () -> operations.uninstall(id), () -> { }); }
    }
    @FXML private void startRequested() { command("启动", id -> service.start(workspace, id)); }
    @FXML private void stopRequested() { command("停止", id -> service.stop(workspace, id)); }
    @FXML private void forceStopRequested() { if (selected != null) operations.forceStop(selectedId); }
    @FXML private void restartRequested() { command("重启", id -> service.restart(workspace, id)); }
    @FXML private void unquarantineRequested() { command("解除隔离", id -> service.unquarantine(workspace, id)); }
    @FXML private void startupPolicyRequested() {
        if (!updating) { StartupPolicy policy = autoStart.isSelected() ? StartupPolicy.AUTO_START : StartupPolicy.MANUAL;
            command("更新启动策略", id -> service.setStartupPolicy(workspace, id, policy)); }
    }
    private void command(String name, java.util.function.Consumer<String> command) {
        if (selected != null) { String id = selectedId; operations.command(id, name, () -> command.accept(id)); }
    }
    @FXML private void cancelRequested() { operations.cancel(); statusLabel.setText("操作已取消"); }
    @FXML private void closeRequested() { requestClose(() -> { if (owner() != null) owner().hide(); }); }

    private void applied(PluginCenterOperations.Result result) {
        if (closed.get()) return;
        statusLabel.setText(result.message());
        if (result.details() != null) { selectedId = result.details().summary().id(); render(result.details(), result.replaceForm(), result.replacePermissions()); }
        if (result.details() != null || result.message().startsWith("卸载已提交")) runtimeConfigurationChanged.run();
        refreshRequested();
    }
    private void updateButtons() {
        if (operations == null) return;
        boolean busy = operations.busyProperty().get(), absent = selected == null;
        boolean permissionsDirty = permissionEditor != null && permissionEditor.isDirty();
        configurationHost.setDisable(busy || permissionsDirty); permissionsBox.setDisable(busy); modelPanelHost.setDisable(busy || permissionsDirty);
        if (permissionEditor != null) permissionEditor.setChoicesDisabled(busy || configurationDirty());
        dirtyLabel.setVisible(isDirty()); dirtyLabel.setManaged(isDirty());
        addLocalButton.setDisable(busy); addUrlButton.setDisable(busy);
        pluginList.setDisable(busy);
        for (Button button : List.of(upgradeButton, uninstallButton, enabledButton, startButton, stopButton, restartButton, forceStopButton)) button.setDisable(busy || absent);
        saveButton.setDisable(busy || absent || !editor.isDirty()); discardButton.setDisable(busy || !isDirty());
        grantButton.setDisable(busy || absent || configurationDirty() || !permissionsDirty); autoStart.setDisable(busy || absent);
        unquarantineButton.setDisable(busy || absent);
        if (!absent) {
            enabledButton.setText(selected.summary().enabled() ? "停用" : "启用");
            startButton.setDisable(busy || !selected.summary().enabled() || selected.summary().state() == State.READY);
            unquarantineButton.setVisible(selected.summary().state() == State.QUARANTINED);
            unquarantineButton.setManaged(unquarantineButton.isVisible());
        }
    }
    private void showProgress(InstallProgress value) {
        progressBar.setProgress(value.totalBytes() > 0 ? Math.min(1, (double) value.completedBytes() / value.totalBytes()) : -1);
        statusLabel.setText(value.phase() + " · " + value.message());
    }
    private void showFailure(Throwable failure) {
        if (closed.get()) return;
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (failure instanceof PluginManagementService.PluginException pluginFailure && "UNKNOWN_OUTCOME".equals(pluginFailure.code())) {
            message = "执行结果未知，请到“运行与日志”页检查日志，并核对业务记录；不会自动重放。\n" + message;
        }
        errorLabel.setText(message); errorLabel.setVisible(true); errorLabel.setManaged(true);
        statusLabel.setText("操作失败");
    }
    private void clearSelection() { selectedId = null; selected = null; formPlugin = null; editor.close(); permissionEditor.close(); closeModelPanel(); detailPane.setVisible(false); detailPane.setManaged(false); emptyDetail.setVisible(true); emptyDetail.setManaged(true); }
    private Window owner() { return root.getScene() == null ? null : root.getScene().getWindow(); }
    private static Path chooseZip(Window owner) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("选择插件 ZIP 包");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("插件包", "*.zip"));
        java.io.File selected = chooser.showOpenDialog(owner);
        return selected == null ? null : selected.toPath();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        catalogAction.close(); detailsAction.close(); diagnosticsAction.close(); editor.close(); if (permissionEditor != null) permissionEditor.close(); if (versions != null) versions.close();
        try { closeModelPanel(); }
        finally {
            if (operations != null) operations.close();
            if (subscription != null) try { subscription.close(); } catch (Exception failure) { throw new IllegalStateException("关闭插件状态订阅失败", failure); }
        }
    }
    private static final class PluginCell extends ListCell<PluginSummary> {
        @Override protected void updateItem(PluginSummary plugin, boolean empty) {
            super.updateItem(plugin, empty);
            setText(empty || plugin == null ? null : plugin.name() + "  " + plugin.version() + "\n"
                    + (plugin.enabled() ? "已启用" : "已停用") + " · " + UnifiedPluginCenterViewModel.stateText(plugin.state())
                    + "\n" + String.join("、", plugin.capabilities()));
        }
    }
}
