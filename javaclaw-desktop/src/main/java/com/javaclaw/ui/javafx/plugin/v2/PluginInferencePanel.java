package com.javaclaw.ui.javafx.plugin.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.inference.PluginScopedInferenceManagement;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginDetails;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.settings.InferenceApiSettingsController;
import com.javaclaw.ui.javafx.settings.InferenceAssetSettingsController;
import com.javaclaw.ui.javafx.settings.InferenceProfileSettingsController;
import com.javaclaw.ui.javafx.settings.InferenceRuntimeControls;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** 复用宿主模型页面；数据门面固定插件身份，全部视图与生命周期由宿主持有。 */
final class PluginInferencePanel implements AutoCloseable {
    private final VBox root = new VBox(10);
    private final TabPane pages = new TabPane();
    private final ComboBox<InferenceModelProfile.Kind> kind = new ComboBox<>();
    private final Label status = new Label();
    private final Label endpointOwner = new Label();
    private final Button takeEndpoint = new Button("接管公开接口");
    private final SimpleBooleanProperty dirty = new SimpleBooleanProperty();
    private final List<AutoCloseable> controllers = new ArrayList<>();
    private final PluginScopedInferenceManagement service;
    private final PluginManagementService plugins;
    private final String workspace, pluginId;
    private final UiAsyncAction<Snapshot> refresh;
    private final Runnable changed;
    private final Consumer<Throwable> failure;
    private final BiConsumer<Runnable, Runnable> leave;
    private final DialogService dialogs;
    private final ObjectMapper json;
    private final Set<Node> watched = Collections.newSetFromMap(new IdentityHashMap<>());
    private InferenceAssetSettingsController assets;
    private InferenceProfileSettingsController profiles;
    private InferenceApiSettingsController api;
    private PluginDetails details;
    private boolean applying, closed;

    PluginInferencePanel(PluginDetails details, String workspace, PluginManagementService plugins,
                         InferenceManagementApplicationService inference, WorkspaceApplicationService workspaces,
                         DialogService dialogs, ManagedTaskExecutor tasks, FxDispatcher fx, ObjectMapper json,
                         Runnable changed, Consumer<Throwable> failure, BiConsumer<Runnable, Runnable> leave) {
        this.details = details; this.workspace = workspace; this.pluginId = details.summary().id(); this.plugins = plugins;
        this.changed = changed; this.failure = failure; this.leave = leave; this.dialogs = dialogs; this.json = json;
        service = new PluginScopedInferenceManagement(inference, workspace, pluginId);
        refresh = new UiAsyncAction<>(tasks, fx);
        pages.disableProperty().bind(refresh.busyProperty()); kind.disableProperty().bind(refresh.busyProperty());
        root.setId("pluginInferencePanel"); root.setUserData(pluginId);
        root.getChildren().add(new Label("模型提供者：" + pluginId + "\n仅管理本插件的档案；模型文件为共享资产，其他插件的工作区绑定保持原样。"));
        kind.getItems().setAll(InferenceModelProfile.Kind.values()); kind.setValue(InferenceModelProfile.Kind.GENERATION);
        kind.setConverter(new StringConverter<>() {
            @Override public String toString(InferenceModelProfile.Kind value) { return value == InferenceModelProfile.Kind.EMBEDDING ? "嵌入模型" : "对话模型"; }
            @Override public InferenceModelProfile.Kind fromString(String value) { throw new UnsupportedOperationException(); }
        });
        Button reload = new Button("刷新模型目录"); reload.setOnAction(event -> leave.accept(this::reload, () -> { }));
        root.getChildren().add(new HBox(10, kind, reload));
        endpointOwner.setId("endpointOwnerLabel"); takeEndpoint.setId("takeEndpointButton");
        takeEndpoint.disableProperty().bind(refresh.busyProperty()); takeEndpoint.setOnAction(event -> claimEndpoint());
        root.getChildren().add(new HBox(10, endpointOwner, takeEndpoint));
        try {
            Node assetRoot = load("local-inference-assets.fxml", type -> {
                assets = new InferenceAssetSettingsController(service, dialogs, tasks, fx, new ExternalDirectoryOpener(tasks));
                controllers.add(assets); return assets;
            });
            Node profileRoot = load("local-inference-profiles.fxml", type -> {
                profiles = new InferenceProfileSettingsController(service, workspaces, json, dialogs, tasks, fx);
                controllers.add(profiles); return profiles;
            });
            Node apiRoot = load("local-inference-api.fxml", type -> {
                api = new InferenceApiSettingsController(service, dialogs, tasks, fx); controllers.add(api); return api;
            });
            pages.getTabs().setAll(new Tab("模型文件", assetRoot), new Tab("模型档案", profileRoot), new Tab("公开接口", apiRoot));
            pages.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            configureControllers();
            kind.valueProperty().addListener((ignored, before, after) -> {
                if (!applying && after != null) leave.accept(() -> { configureControllers(); reload(); },
                        () -> { applying = true; kind.setValue(before); applying = false; });
            });
            root.getChildren().addAll(pages, status);
            watch(root, watched);
            reload();
        } catch (RuntimeException failureDuringLoad) { close(); throw failureDuringLoad; }
    }
    private Node load(String name, javafx.util.Callback<Class<?>, Object> controllerFactory) {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/settings/" + name));
        loader.setControllerFactory(controllerFactory);
        try { return loader.load(); }
        catch (IOException exception) { throw new IllegalStateException("加载宿主模型页面失败: " + name, exception); }
    }
    private void configureControllers() {
        api.configureProvider(pluginId);
        if (presentation().endpointCapabilities().isEmpty()) { takeEndpoint.setVisible(false); takeEndpoint.setManaged(false); }
        assets.configure(this::reload, kind.getValue(), (asset, purposes) -> {
            leave.accept(() -> { profiles.beginCreate(asset.id()); pages.getSelectionModel().select(1); }, () -> { });
        }, InferenceAssetSettingsController.Mode.PLUGIN_MODELS, () -> pages.getSelectionModel().selectFirst());
        profiles.configurePlugin(pluginId, this::reload, () -> { changed.run(); reload(); }, kind.getValue());
        api.configure(this::reload, () -> pages.getSelectionModel().selectFirst(), id -> {
            leave.accept(() -> { profiles.beginEdit(id); pages.getSelectionModel().select(1); }, () -> { });
        }, presentation(), new InferenceRuntimeControls(new Label("进程启停和权限由插件中心的运行页统一管理。"),
                () -> lifecycle(() -> plugins.start(workspace, pluginId)),
                () -> lifecycle(() -> plugins.stop(workspace, pluginId)),
                () -> lifecycle(() -> plugins.restart(workspace, pluginId)),
                () -> lifecycle(() -> plugins.unquarantine(workspace, pluginId))));
    }
    private void lifecycle(Runnable action) {
        leave.accept(() -> refresh.execute(TaskSpec.io("MODEL 插件生命周期"), task -> { action.run(); return readSnapshot(); }, this::apply, failure), () -> { });
    }
    private void claimEndpoint() {
        leave.accept(() -> refresh.execute(TaskSpec.io("接管公开接口"), task -> {
            var current = service.snapshot(workspace).gateway();
            String owner = current.providerId().isBlank() ? "尚未指定" : current.providerId();
            if (!dialogs.confirm(new ConfirmRequest("inference.endpoint.owner", "接管公开接口",
                    "公开接口当前归属：" + owner + "。将改由 " + pluginId + " 提供，先关闭旧实例的监听，再应用新监听。"
                            + "\n已发布别名仍分别属于各自插件；共享 API Key 与监听配置保留。",
                    ConfirmKind.CONFIRM, 0, "", true)).isAllow()) return null;
            task.cancellation().throwIfCancellationRequested(); service.saveGateway(current.withProvider(pluginId));
            return readSnapshot();
        }, value -> { if (value != null) { apply(value); changed.run(); } }, failure), () -> { });
    }
    private InferenceApiSettingsController.PluginPresentation presentation() {
        return new InferenceApiSettingsController.PluginPresentation(details.summary().state(),
                PluginModelUiCapabilities.endpoints(details, json), 0, List.of(), details.summary().lastError());
    }
    void update(PluginDetails value) { details = value; configureControllers(); }
    Node root() { return root; }
    ReadOnlyBooleanProperty dirtyProperty() { return dirty; }
    boolean isDirty() { return dirty.get(); }
    void discard() { reload(); }
    void openPage(String page) { pages.getSelectionModel().select("service".equals(page) ? 2 : "profiles".equals(page) ? 1 : 0); }
    private void reload() {
        if (closed) return;
        refresh.execute(TaskSpec.io("插件模型目录"), task -> readSnapshot(), this::apply, failure);
    }
    private Snapshot readSnapshot() {
        var snapshot = service.snapshot(workspace);
        Set<UUID> ids = snapshot.profiles().stream().map(InferenceModelProfile::id).collect(java.util.stream.Collectors.toSet());
        return new Snapshot(snapshot, service.runtimeStatuses(ids), service.modelServiceSnapshot());
    }
    private void apply(Snapshot value) {
        if (closed) return;
        applying = true;
        try {
            assets.apply(value.catalog(), value.statuses()); profiles.apply(value.catalog(), value.statuses());
            api.apply(value.catalog(), value.statuses(), value.service());
            String owner = value.catalog().gateway().providerId(); boolean owned = pluginId.equals(owner);
            endpointOwner.setText("公开接口提供者：" + (owner.isBlank() ? "尚未指定（编辑前请确认接管）" : owner));
            boolean declared = !presentation().endpointCapabilities().isEmpty();
            takeEndpoint.setVisible(!owned && declared); takeEndpoint.setManaged(takeEndpoint.isVisible());
            watch(root, watched);
            status.setText("公开接口配置与 API Key 由宿主标准目录统一保存；模型发布限定当前插件。");
            dirty.set(false);
        } finally { applying = false; }
    }
    private void watch(Node node, Set<Node> seen) {
        boolean firstVisit = seen.add(node);
        if (firstVisit && node instanceof TextInputControl text && text.isEditable()) text.textProperty().addListener((ignored, before, after) -> modified());
        if (firstVisit && node instanceof ComboBox<?> combo && combo != kind) combo.valueProperty().addListener((ignored, before, after) -> modified());
        if (firstVisit && node instanceof CheckBox checkbox) checkbox.selectedProperty().addListener((ignored, before, after) -> modified());
        if (node instanceof TabPane tabs) tabs.getTabs().forEach(tab -> { if (tab.getContent() != null) watch(tab.getContent(), seen); });
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> watch(child, seen));
    }
    private void modified() { if (!applying && !closed) dirty.set(true); }
    @Override public void close() {
        if (closed) return; closed = true; refresh.close();
        RuntimeException failureDuringClose = null;
        for (int index = controllers.size() - 1; index >= 0; index--) {
            try { controllers.get(index).close(); }
            catch (Exception problem) { if (failureDuringClose == null) failureDuringClose = new IllegalStateException("关闭模型页面失败", problem); else failureDuringClose.addSuppressed(problem); }
        }
        controllers.clear(); root.getChildren().clear(); watched.clear(); dirty.set(false);
        if (failureDuringClose != null) throw failureDuringClose;
    }
    private record Snapshot(InferenceManagementApplicationService.Snapshot catalog,
                            Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses,
                            com.javaclaw.application.inference.InferenceApiServerControlPort.ServiceSnapshot service) { }
}
