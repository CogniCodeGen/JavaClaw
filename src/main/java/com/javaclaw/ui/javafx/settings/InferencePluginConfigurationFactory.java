package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Creates host-owned inference blocks for declarative service-plugin configuration pages. */
public final class InferencePluginConfigurationFactory {
    private final InferenceManagementApplicationService management;
    private final WorkspaceApplicationService workspaces;
    private final SpringFxmlLoader loader;
    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;

    public InferencePluginConfigurationFactory(
            InferenceManagementApplicationService management,
            WorkspaceApplicationService workspaces,
            SpringFxmlLoader loader,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.management = Objects.requireNonNull(management, "management");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    public Component createModels(ServicePluginInfo plugin) {
        return createModels(plugin, () -> { });
    }

    public Component createModels(ServicePluginInfo plugin, Runnable runtimeConfigurationChanged) {
        return createModels(plugin, runtimeConfigurationChanged, (ignored, failure) -> { });
    }

    public Component createModels(
            ServicePluginInfo plugin,
            Runnable runtimeConfigurationChanged,
            BiConsumer<String, Throwable> failureReporter) {
        return new ComponentImpl(plugin, EnumSet.of(Page.PROFILES, Page.ASSETS),
                ComponentMode.LEGACY_MODELS, runtimeConfigurationChanged, failureReporter,
                null, () -> { });
    }

    public Component createApi(ServicePluginInfo plugin) {
        return createApi(plugin, (ignored, failure) -> { });
    }

    public Component createApi(
            ServicePluginInfo plugin, BiConsumer<String, Throwable> failureReporter) {
        return new ComponentImpl(plugin, EnumSet.of(Page.API), ComponentMode.LEGACY_API,
                () -> { }, failureReporter, null, () -> { });
    }

    public Component createCatalog(
            ServicePluginInfo plugin,
            Runnable runtimeConfigurationChanged,
            BiConsumer<String, Throwable> failureReporter) {
        return new ComponentImpl(plugin, EnumSet.of(Page.ASSETS), ComponentMode.CATALOG,
                runtimeConfigurationChanged, failureReporter, null, () -> { });
    }

    public Component createService(
            ServicePluginInfo plugin,
            Runnable runtimeConfigurationChanged,
            BiConsumer<String, Throwable> failureReporter,
            UUID requestedAsset,
            InferenceModelProfile.Kind requestedKind,
            Runnable openCatalog,
            RuntimeControls runtimeControls) {
        return new ComponentImpl(plugin, EnumSet.allOf(Page.class), ComponentMode.SERVICE,
                runtimeConfigurationChanged, failureReporter,
                requestedAsset == null ? null : new AssetRequest(requestedAsset, requestedKind),
                openCatalog, runtimeControls);
    }

    /** Host-owned process/resource controls embedded into the Deliverance service console. */
    public record RuntimeControls(
            Node settings, Runnable start, Runnable stop, Runnable restart, Runnable unquarantine) {
        public RuntimeControls {
            settings = Objects.requireNonNull(settings, "settings");
            start = Objects.requireNonNull(start, "start");
            stop = Objects.requireNonNull(stop, "stop");
            restart = Objects.requireNonNull(restart, "restart");
            unquarantine = Objects.requireNonNull(unquarantine, "unquarantine");
        }

        static RuntimeControls none() {
            return new RuntimeControls(new VBox(), () -> { }, () -> { }, () -> { }, () -> { });
        }
    }

    /** A page-owned component; closing it cancels work and clears one-time credentials. */
    public interface Component extends AutoCloseable {
        Node root();
        ReadOnlyBooleanProperty busyProperty();
        void activate();
        void refresh();
        void cancel();
        @Override void close();
    }

    private final class ComponentImpl implements Component {
        private final ServicePluginInfo plugin;
        private final List<Page> pages;
        private final ComponentMode mode;
        private final Runnable runtimeConfigurationChanged;
        private final BiConsumer<String, Throwable> failureReporter;
        private final Runnable openCatalog;
        private final RuntimeControls runtimeControls;
        private final VBox root = new VBox(10);
        private final StackPane content = new StackPane();
        private final Label status = new Label();
        private final Label serviceState = new Label();
        private final Label modelCount = new Label();
        private final Button loadModel = new Button("＋ 加载模型");
        private final Button backToModels = new Button("← 已配置模型");
        private final Map<InferenceModelProfile.Kind, ToggleButton> kindButtons =
                new EnumMap<>(InferenceModelProfile.Kind.class);
        private final UiAsyncAction<ViewSnapshot> refresh;
        private ViewHandle<Node> activeHandle;
        private Page selected;
        private InferenceModelProfile.Kind selectedKind = InferenceModelProfile.Kind.GENERATION;
        private UUID pendingProfileAsset;
        private UUID pendingProfileEdit;
        private UUID preferredServiceProfile;
        private String statusAfterRefresh = "";
        private boolean serviceObservedRunning;
        private boolean closed;

        private ComponentImpl(
                ServicePluginInfo plugin,
                Set<Page> pages,
                ComponentMode mode,
                Runnable runtimeConfigurationChanged,
                BiConsumer<String, Throwable> failureReporter,
                AssetRequest requestedAsset,
                Runnable openCatalog) {
            this(plugin, pages, mode, runtimeConfigurationChanged, failureReporter,
                    requestedAsset, openCatalog, RuntimeControls.none());
        }

        private ComponentImpl(
                ServicePluginInfo plugin,
                Set<Page> pages,
                ComponentMode mode,
                Runnable runtimeConfigurationChanged,
                BiConsumer<String, Throwable> failureReporter,
                AssetRequest requestedAsset,
                Runnable openCatalog,
                RuntimeControls runtimeControls) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            this.pages = List.copyOf(pages);
            this.mode = Objects.requireNonNull(mode, "mode");
            this.runtimeConfigurationChanged = Objects.requireNonNull(
                    runtimeConfigurationChanged, "runtimeConfigurationChanged");
            this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
            this.pendingProfileAsset = requestedAsset == null ? null : requestedAsset.assetId();
            if (requestedAsset != null && requestedAsset.kind() != null) {
                this.selectedKind = requestedAsset.kind();
            }
            this.openCatalog = Objects.requireNonNull(openCatalog, "openCatalog");
            this.runtimeControls = Objects.requireNonNull(runtimeControls, "runtimeControls");
            refresh = new UiAsyncAction<>(tasks, fx);
            root.getStyleClass().add("service-plugin-inference-block");
            content.getStyleClass().add("service-plugin-component-content");
            status.getStyleClass().add("settings-hint");
            status.setWrapText(true);
            if (mode == ComponentMode.LEGACY_MODELS) {
                root.getChildren().add(modelToolbar());
                root.setOnKeyPressed(event -> {
                    if (event.isShortcutDown() && event.getCode() == KeyCode.L) {
                        show(Page.ASSETS);
                        event.consume();
                    }
                });
            }
            root.getChildren().addAll(content, status);
        }

        @Override public Node root() { return root; }
        @Override public ReadOnlyBooleanProperty busyProperty() { return refresh.busyProperty(); }

        @Override
        public void activate() {
            if (closed || selected != null) return;
            show(mode == ComponentMode.SERVICE && pendingProfileAsset != null
                    ? Page.PROFILES : mode.homePage);
        }

        @Override
        public void refresh() {
            if (closed || selected == null || activeHandle == null) return;
            Page requested = selected;
            status.setText("正在刷新…");
            refresh.execute(TaskSpec.io("读取插件推理配置"),
                    context -> viewSnapshot(requested),
                    value -> apply(requested, value),
                    failure -> {
                        status.setText("刷新失败：" + SettingsFieldSupport.failureMessage(failure));
                        reportFailure("刷新" + requested.label + "失败", failure);
                    });
        }

        @Override
        public void cancel() {
            refresh.cancel();
            if (activeHandle != null) InferencePluginConfigurationFactory.cancel(activeHandle);
        }

        private HBox modelToolbar() {
            HBox tabs = new HBox(6);
            tabs.setAlignment(Pos.CENTER_LEFT);
            tabs.setId("inferenceModelToolbar");
            tabs.getStyleClass().addAll("jc-card", "service-plugin-model-toolbar");
            serviceState.setId("inferenceServiceState");
            serviceObservedRunning = plugin.state() == State.HEALTHY;
            updateServiceState(false);
            tabs.getChildren().add(serviceState);
            ToggleGroup group = new ToggleGroup();
            addKindTab(tabs, group, InferenceModelProfile.Kind.GENERATION,
                    "inferenceGenerationKindTab");
            addKindTab(tabs, group, InferenceModelProfile.Kind.EMBEDDING,
                    "inferenceEmbeddingKindTab");
            kindButtons.get(selectedKind).setSelected(true);
            modelCount.setId("inferenceModelCount");
            modelCount.getStyleClass().add("settings-hint");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            backToModels.setId("inferenceBackToModelsButton");
            backToModels.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
            backToModels.setOnAction(ignored -> show(Page.PROFILES));
            loadModel.setId("inferenceLoadModelButton");
            loadModel.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
            loadModel.setOnAction(ignored -> show(Page.ASSETS));
            tabs.getChildren().addAll(modelCount, spacer, backToModels, loadModel);
            updateModelToolbar();
            return tabs;
        }

        private void addKindTab(
                HBox tabs, ToggleGroup group, InferenceModelProfile.Kind kind, String id) {
            ToggleButton button = new ToggleButton(InferenceModelPurposeClassifier.kindLabel(kind));
            button.setId(id);
            button.setToggleGroup(group);
            button.getStyleClass().addAll("settings-tab", "service-plugin-kind-tab");
            button.setOnAction(ignored -> showKind(kind));
            kindButtons.put(kind, button);
            tabs.getChildren().add(button);
        }

        private boolean modelPages() {
            return mode == ComponentMode.LEGACY_MODELS;
        }

        private void showKind(InferenceModelProfile.Kind kind) {
            if (closed || kind == null) return;
            if (kind == selectedKind) {
                ToggleButton current = kindButtons.get(kind);
                if (current != null) current.setSelected(true);
                return;
            }
            selectedKind = kind;
            ToggleButton button = kindButtons.get(kind);
            if (button != null) button.setSelected(true);
            show(selected == null ? pages.getFirst() : selected, true);
        }

        private void show(Page page) {
            show(page, false);
        }

        private void show(Page page, boolean force) {
            if (closed || !force && selected == page && activeHandle != null) return;
            selected = page;
            updateModelToolbar();
            try {
                closeActive();
            } catch (RuntimeException failure) {
                showLoadFailure(page, failure);
                return;
            }
            status.setText("正在加载" + page.label + "…");
            try {
                activeHandle = loader.load(Objects.requireNonNull(
                        getClass().getResource(page.resource), "缺少推理配置 FXML"));
                configure(page, activeHandle);
                content.getChildren().setAll(activeHandle.root());
                resetPageScroll();
                refresh();
            } catch (IOException | RuntimeException failure) {
                closeFailedHandle(failure);
                showLoadFailure(page, failure);
            }
        }

        private void showLoadFailure(Page page, Throwable failure) {
            String detail = SettingsFieldSupport.failureMessage(failure);
            Label title = new Label(page.label + "加载失败");
            title.getStyleClass().add("settings-group-title");
            Label message = new Label(detail);
            message.setWrapText(true);
            message.getStyleClass().addAll("settings-hint", "service-plugin-error");
            Button retry = new Button("重试加载");
            retry.setId("servicePluginInferenceRetry");
            retry.getStyleClass().addAll("jc-btn", "jc-btn-sm");
            retry.setOnAction(ignored -> show(page));
            VBox error = new VBox(8, title, message, retry);
            error.setId("servicePluginInferenceError");
            error.getStyleClass().addAll("jc-card", "service-plugin-error-state");
            content.getChildren().setAll(error);
            status.setText("加载失败：" + detail);
            resetPageScroll();
            reportFailure("加载" + page.label + "失败", failure);
        }

        private void reportFailure(String operation, Throwable failure) {
            try {
                failureReporter.accept(operation, failure);
            } catch (RuntimeException reportingFailure) {
                failure.addSuppressed(reportingFailure);
            }
        }

        private void resetPageScroll() {
            fx.dispatchLater(() -> {
                if (closed || selected == null) return;
                for (Parent parent = root.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof ScrollPane scroll) {
                        scroll.setVvalue(scroll.getVmin());
                        break;
                    }
                }
            });
        }

        private void closeFailedHandle(Throwable failure) {
            try {
                closeActive();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        private void configure(Page page, ViewHandle<Node> handle) {
            switch (page) {
                case PROFILES -> {
                    var controller = handle.controller(InferenceProfileSettingsController.class);
                    if (mode == ComponentMode.SERVICE) {
                        controller.configure(this::refresh, runtimeConfigurationChanged,
                                selectedKind, InferenceProfileLoadFlow.of(
                                        () -> show(Page.API, true), this::profileLoadCompleted));
                    } else {
                        controller.configure(this::refresh, runtimeConfigurationChanged, selectedKind);
                    }
                }
                case ASSETS -> {
                    var controller = handle.controller(InferenceAssetSettingsController.class);
                    if (mode == ComponentMode.CATALOG) {
                        controller.configure(this::refresh, selectedKind, this::modelSelected,
                                InferenceAssetSettingsController.Mode.CATALOG, () -> { });
                    } else if (mode == ComponentMode.SERVICE) {
                        controller.configure(this::refresh, selectedKind, this::modelSelected,
                                InferenceAssetSettingsController.Mode.LOCAL_ONLY_PICKER, openCatalog);
                    } else {
                        controller.configure(this::refresh, selectedKind, this::modelSelected);
                    }
                }
                case API -> {
                    var controller = handle.controller(InferenceApiSettingsController.class);
                    var presentation = new InferenceApiSettingsController.PluginPresentation(
                            plugin.state(), plugin.endpointCapabilities(), plugin.pid(),
                            plugin.recentLogs(), plugin.lastError());
                    if (mode == ComponentMode.SERVICE) {
                        controller.configure(this::refresh, () -> show(Page.ASSETS), profileId -> {
                            pendingProfileEdit = profileId;
                            show(Page.PROFILES, true);
                        }, presentation, runtimeControls);
                        controller.preferServiceProfile(preferredServiceProfile);
                        preferredServiceProfile = null;
                    } else {
                        controller.configure(this::refresh, () -> show(Page.ASSETS), presentation);
                    }
                }
            }
        }

        private void apply(Page page, ViewSnapshot view) {
            if (closed || page != selected || activeHandle == null) return;
            InferenceManagementApplicationService.Snapshot value = view.snapshot();
            long configured = value.profiles().stream()
                    .filter(profile -> profile.kind() == selectedKind).count();
            long running = value.profiles().stream()
                    .filter(profile -> profile.kind() == selectedKind)
                    .filter(profile -> view.statuses().containsKey(profile.id())).count();
            updateServiceState(running > 0);
            modelCount.setText(running > 0
                    ? running + " 个已加载 · " + configured + " 个已配置"
                    : configured + " 个已配置");
            switch (page) {
                case PROFILES -> activeHandle.controller(InferenceProfileSettingsController.class)
                        .apply(value, view.statuses());
                case ASSETS -> activeHandle.controller(InferenceAssetSettingsController.class)
                        .apply(value, view.statuses());
                case API -> activeHandle.controller(InferenceApiSettingsController.class)
                        .apply(value, view.statuses(), view.service());
            }
            if (page == Page.PROFILES && pendingProfileAsset != null) {
                UUID assetId = pendingProfileAsset;
                pendingProfileAsset = null;
                activeHandle.controller(InferenceProfileSettingsController.class).beginCreate(assetId);
            }
            if (page == Page.PROFILES && pendingProfileEdit != null) {
                UUID profileId = pendingProfileEdit;
                pendingProfileEdit = null;
                activeHandle.controller(InferenceProfileSettingsController.class).beginEdit(profileId);
            }
            status.setText(statusAfterRefresh);
            statusAfterRefresh = "";
        }

        private void profileLoadCompleted(InferenceProfileLoadFlow.Result result) {
            preferredServiceProfile = result.profileId();
            statusAfterRefresh = result.loaded() ? "模型已加载"
                    : "运行设置已保存，加载失败：" + result.failure();
            show(Page.API, true);
        }

        private void modelSelected(
                InferenceModelAsset model, Set<InferenceModelProfile.Kind> purposes) {
            if (closed || mode == ComponentMode.CATALOG) return;
            if (purposes == null || purposes.isEmpty()) {
                statusAfterRefresh = "模型已添加，但用途仍需处理";
                refresh();
                return;
            } else if (purposes.size() == 1) {
                InferenceModelProfile.Kind detected = purposes.iterator().next();
                statusAfterRefresh = "已选择"
                        + InferenceModelPurposeClassifier.kindLabel(detected) + "，确认后即可加载";
                selectedKind = detected;
                ToggleButton button = kindButtons.get(detected);
                if (button != null) button.setSelected(true);
            } else {
                statusAfterRefresh = "模型可用于推理和向量化，确认后即可加载";
            }
            pendingProfileAsset = model.id();
            show(Page.PROFILES, true);
        }

        private ViewSnapshot viewSnapshot(Page requested) {
            InferenceManagementApplicationService.Snapshot snapshot = filtered(management.snapshot(
                    workspaces.currentWorkspaceId(), requested.projection));
            Set<UUID> profileIds = snapshot.profiles().stream().map(InferenceModelProfile::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new ViewSnapshot(snapshot, management.runtimeStatuses(profileIds),
                    requested == Page.API ? management.modelServiceSnapshot()
                            : com.javaclaw.application.inference.InferenceApiServerControlPort.NOOP
                            .serviceSnapshot());
        }

        private void updateModelToolbar() {
            if (mode != ComponentMode.LEGACY_MODELS) return;
            boolean assets = selected == Page.ASSETS;
            backToModels.setVisible(assets);
            backToModels.setManaged(assets);
            loadModel.setVisible(!assets);
            loadModel.setManaged(!assets);
        }

        private void updateServiceState(boolean modelRunning) {
            serviceObservedRunning |= modelRunning;
            String text = serviceObservedRunning ? "服务运行中" : switch (plugin.state()) {
                case STARTING -> "服务启动中";
                case DEGRADED -> "服务需关注";
                default -> "服务已停止";
            };
            serviceState.setText(text);
            serviceState.getStyleClass().setAll("jc-badge",
                    serviceObservedRunning ? "jc-badge-ok" : "jc-badge-stopped");
        }

        private InferenceManagementApplicationService.Snapshot filtered(
                InferenceManagementApplicationService.Snapshot value) {
            String engine = plugin.inference() == null ? "" : plugin.inference().engine();
            if (engine.isBlank()) return value;
            var runtimes = value.runtimes().stream()
                    .filter(runtime -> engine.equalsIgnoreCase(runtime.manifest().engine())).toList();
            Set<String> runtimeIds = runtimes.stream().map(
                    runtime -> runtime.manifest().runtimeId()).collect(java.util.stream.Collectors.toSet());
            var profiles = value.profiles().stream()
                    .filter(profile -> runtimeIds.contains(profile.runtimeId())).toList();
            Set<UUID> profileIds = profiles.stream().map(InferenceModelProfile::id)
                    .collect(java.util.stream.Collectors.toSet());
            Set<UUID> usedAssets = profiles.stream().map(InferenceModelProfile::assetId)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> modelTypes = runtimes.stream().flatMap(runtime -> EnumSet.allOf(
                            InferenceModelProfile.Kind.class).stream().flatMap(kind ->
                            runtime.manifest().supportedModelTypes(kind).stream()))
                    .collect(java.util.stream.Collectors.toSet());
            var assets = value.assets().stream().filter(asset -> usedAssets.contains(asset.id())
                    || modelTypes.contains(asset.modelType()) || "unknown".equals(asset.modelType())).toList();
            Map<InferenceCatalogPort.ModelTier, UUID> bindings = value.bindings().entrySet().stream()
                    .filter(entry -> profileIds.contains(entry.getValue())).collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey, Map.Entry::getValue));
            var published = value.publishedModels().stream()
                    .filter(model -> profileIds.contains(model.profileId())).toList();
            return new InferenceManagementApplicationService.Snapshot(
                    runtimes, assets, profiles, bindings, published,
                    value.gateway(), value.apiKeys());
        }

        private void closeActive() {
            refresh.cancel();
            if (activeHandle == null) return;
            ViewHandle<Node> handle = activeHandle;
            activeHandle = null;
            content.getChildren().clear();
            handle.close();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            try {
                closeActive();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                refresh.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
    }

    private static void cancel(ViewHandle<Node> handle) {
        handle.controllers().forEach(controller -> {
            if (controller instanceof InferenceProfileSettingsController value) value.cancel();
            else if (controller instanceof InferenceAssetSettingsController value) value.cancel();
            else if (controller instanceof InferenceApiSettingsController value) value.cancel();
        });
    }

    private record ViewSnapshot(
            InferenceManagementApplicationService.Snapshot snapshot,
            Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses,
            com.javaclaw.application.inference.InferenceApiServerControlPort.ServiceSnapshot service) {
        private ViewSnapshot {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
            service = Objects.requireNonNull(service, "service");
        }
    }

    private enum Page {
        PROFILES("已配置模型", "/fxml/settings/local-inference-profiles.fxml",
                InferenceManagementApplicationService.Projection.MODEL_MANAGEMENT),
        ASSETS("添加模型", "/fxml/settings/local-inference-assets.fxml",
                InferenceManagementApplicationService.Projection.MODEL_MANAGEMENT),
        API("对外接口", "/fxml/settings/local-inference-api.fxml",
                InferenceManagementApplicationService.Projection.SERVICE_CONSOLE);

        private final String label;
        private final String resource;
        private final InferenceManagementApplicationService.Projection projection;

        Page(String label, String resource,
             InferenceManagementApplicationService.Projection projection) {
            this.label = label;
            this.resource = resource;
            this.projection = projection;
        }
    }

    private enum ComponentMode {
        LEGACY_MODELS(Page.PROFILES),
        LEGACY_API(Page.API),
        CATALOG(Page.ASSETS),
        SERVICE(Page.API);

        private final Page homePage;

        ComponentMode(Page homePage) {
            this.homePage = homePage;
        }
    }

    private record AssetRequest(UUID assetId, InferenceModelProfile.Kind kind) { }
}
