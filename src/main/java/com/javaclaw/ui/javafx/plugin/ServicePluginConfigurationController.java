package com.javaclaw.ui.javafx.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ApiKeyRotation;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.settings.InferencePluginConfigurationFactory;
import com.javaclaw.inference.api.InferenceModelProfile;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Full-width declarative service-plugin configuration page in Plugin Center. */
public final class ServicePluginConfigurationController implements AutoCloseable,
        ServicePluginDeclarativePageRenderer.Actions {
    @FXML private VBox root;
    @FXML private Label pluginNameLabel;
    @FXML private Label pluginMetadataLabel;
    @FXML private Label pluginStateLabel;
    @FXML private HBox pageTabs;
    @FXML private Label pageTitleLabel;
    @FXML private Label pageDescriptionLabel;
    @FXML private VBox pageContent;

    private final ServicePluginManagementApplicationService services;
    private final DialogService dialogs;
    private final UiAsyncAction<List<ServicePluginInfo>> refresh;
    private final UiAsyncAction<MutationResult> mutation;
    private final ServicePluginDeclarativePageRenderer renderer;
    private Runnable back = () -> { };
    private Runnable missing = () -> { };
    private Consumer<String> status = ignored -> { };
    private BiConsumer<String, Throwable> failures = (ignored, failure) -> { };
    private Runnable runtimeConfigurationChanged = () -> { };
    private ServicePluginInfo plugin;
    private String pluginId;
    private String requestedPage;
    private List<ServicePluginDeclarativePageRenderer.Page> availablePages = List.of();
    private ServicePluginDeclarativePageRenderer.PendingInferenceAsset pendingInferenceAsset;
    private ServicePluginDeclarativePageRenderer.RenderedPage activePage;
    private boolean closed;

    public ServicePluginConfigurationController(
            ServicePluginManagementApplicationService services,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            ObjectMapper json,
            InferencePluginConfigurationFactory inference) {
        this.services = Objects.requireNonNull(services, "services");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        refresh = new UiAsyncAction<>(tasks, fx);
        mutation = new UiAsyncAction<>(tasks, fx);
        renderer = new ServicePluginDeclarativePageRenderer(json, inference, this);
    }

    void configure(
            Runnable back,
            Runnable missing,
            Consumer<String> status,
            BiConsumer<String, Throwable> failures) {
        this.back = Objects.requireNonNull(back, "back");
        this.missing = Objects.requireNonNull(missing, "missing");
        this.status = Objects.requireNonNull(status, "status");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    void setOnRuntimeConfigurationChanged(Runnable callback) {
        runtimeConfigurationChanged = Objects.requireNonNull(callback, "callback");
    }

    ObservableBooleanValue busyProperty() {
        return Bindings.or(refresh.busyProperty(), mutation.busyProperty());
    }

    boolean isShowing() { return root.isVisible(); }
    void refreshPage() { refresh(); }

    void show(String id, String pageId) {
        if (closed) return;
        pluginId = Objects.requireNonNull(id, "id");
        requestedPage = pageId;
        visible(true);
        refresh();
    }

    void hide() {
        visible(false);
        refresh.cancel();
        mutation.cancel();
        closeActivePage();
        pageTabs.getChildren().clear();
        pageContent.getChildren().clear();
        availablePages = List.of();
        pendingInferenceAsset = null;
        plugin = null;
    }

    @FXML private void backRequested() { hide(); back.run(); }
    @FXML private void refreshRequested() { refresh(); }

    private void refresh() {
        if (closed || pluginId == null) return;
        closeActivePage();
        pageContent.getChildren().setAll(hint("正在读取插件配置…"));
        refresh.execute(TaskSpec.io("刷新服务插件配置"), context -> services.list(),
                this::apply, failure -> fail("刷新服务插件配置失败", failure));
    }

    private void apply(List<ServicePluginInfo> values) {
        ServicePluginInfo value = values.stream().filter(item -> item.id().equals(pluginId))
                .findFirst().orElse(null);
        if (value == null) {
            hide();
            status.accept("请先批准并注册该服务插件");
            missing.run();
            return;
        }
        plugin = value;
        pluginNameLabel.setText(value.name());
        pluginMetadataLabel.setText("版本 " + value.version() + " · 发布者 "
                + (value.publisher().isBlank() ? "未知" : value.publisher()));
        pluginStateLabel.setText(stateText(value));
        pluginStateLabel.getStyleClass().removeAll(
                "jc-badge-ok", "jc-badge-amber", "jc-badge-fail", "jc-badge-stopped");
        pluginStateLabel.getStyleClass().add(stateClass(value));
        List<ServicePluginDeclarativePageRenderer.Page> pages = renderer.pages(value);
        availablePages = pages;
        String target = pages.stream().anyMatch(page -> page.id().equals(requestedPage))
                ? requestedPage : pages.getFirst().id();
        renderTabs(pages, target);
        showPage(pages.stream().filter(page -> page.id().equals(target)).findFirst().orElseThrow());
    }

    private void renderTabs(
            List<ServicePluginDeclarativePageRenderer.Page> pages, String selected) {
        pageTabs.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        for (ServicePluginDeclarativePageRenderer.Page page : pages) {
            ToggleButton button = new ToggleButton(page.title());
            button.setId("servicePluginPageTab-" + page.id());
            button.setToggleGroup(group);
            button.setSelected(page.id().equals(selected));
            button.getStyleClass().addAll("settings-tab", "service-plugin-page-tab");
            button.setOnAction(ignored -> showPage(page));
            pageTabs.getChildren().add(button);
        }
    }

    private void showPage(ServicePluginDeclarativePageRenderer.Page page) {
        if (plugin == null) return;
        closeActivePage();
        requestedPage = page.id();
        pageTabs.getChildren().forEach(child -> {
            if (child instanceof ToggleButton button) {
                button.setSelected(("servicePluginPageTab-" + page.id()).equals(button.getId()));
            }
        });
        pageTitleLabel.setText(page.title());
        pageDescriptionLabel.setText(page.description());
        pageDescriptionLabel.setVisible(!page.description().isBlank());
        pageDescriptionLabel.setManaged(pageDescriptionLabel.isVisible());
        try {
            activePage = renderer.render(plugin, page);
            pageContent.getChildren().setAll(activePage.root());
        } catch (RuntimeException failure) {
            activePage = null;
            pageContent.getChildren().setAll(pageFailure(page, failure));
            fail("加载插件配置页面失败", failure);
        }
    }

    @Override
    public void saveResources(ResourceConfiguration resources) {
        mutate("保存服务插件资源", "资源配置已保存",
                () -> services.updateResourcesAndRestart(pluginId, resources));
    }

    @Override
    public void saveEndpoints(List<EndpointConfiguration> endpoints) {
        mutate("保存服务插件接口", "接口配置已保存",
                () -> services.updateEndpointsAndRestart(pluginId, endpoints));
    }

    @Override
    public void saveConfiguration(Map<String, String> patch) {
        mutate("保存插件配置", "插件配置已保存",
                () -> services.patchPluginConfigurationAndRestart(pluginId, patch));
    }

    @Override
    public void runtime(ServicePluginConfigurationPane.RuntimeAction action) {
        mutate("服务插件运行操作", switch (action) {
            case START -> "服务插件已启动";
            case STOP -> "服务插件已停止";
            case RESTART -> "服务插件已重启";
            case UNQUARANTINE -> "服务插件已解除隔离";
        }, () -> {
            switch (action) {
                case START -> services.start(pluginId);
                case STOP -> services.stop(pluginId);
                case RESTART -> services.restart(pluginId);
                case UNQUARANTINE -> services.unquarantine(pluginId);
            }
        });
    }

    @Override
    public void runtimeConfigurationChanged() {
        runtimeConfigurationChanged.run();
    }

    @Override
    public void openInferenceService(UUID assetId, InferenceModelProfile.Kind kind) {
        pendingInferenceAsset = new ServicePluginDeclarativePageRenderer.PendingInferenceAsset(
                Objects.requireNonNull(assetId, "assetId"), Objects.requireNonNull(kind, "kind"));
        navigateToSection(com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSectionType
                .INFERENCE_SERVICE, "插件没有声明模型服务页面");
    }

    @Override
    public void openInferenceCatalog() {
        navigateToSection(com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSectionType
                .INFERENCE_CATALOG, "插件没有声明模型目录页面");
    }

    @Override
    public ServicePluginDeclarativePageRenderer.PendingInferenceAsset
            consumePendingInferenceAsset() {
        ServicePluginDeclarativePageRenderer.PendingInferenceAsset value = pendingInferenceAsset;
        pendingInferenceAsset = null;
        return value;
    }

    private void navigateToSection(
            com.javaclaw.plugin.api.PluginDescriptor.ConfigurationSectionType type,
            String missingMessage) {
        var target = availablePages.stream().filter(page -> page.descriptor() != null
                        && page.descriptor().sections().stream()
                        .anyMatch(section -> section.type() == type))
                .findFirst();
        if (target.isPresent()) showPage(target.orElseThrow());
        else status.accept(missingMessage);
    }

    @Override
    public void retryPage() {
        refresh();
    }

    @Override
    public void reportFailure(String operation, Throwable failure) {
        fail(operation, failure);
    }

    private void mutate(String task, String success, Runnable operation) {
        mutation.execute(TaskSpec.io(task), context -> {
            operation.run();
            return new MutationResult(services.list(), null);
        }, result -> {
            apply(result.plugins());
            status.accept(success);
        }, failure -> fail("服务插件操作失败", failure));
    }

    @Override
    public void rotateKey(String endpointId) {
        mutation.execute(TaskSpec.io("轮换服务插件 API Key"), context -> {
            var decision = dialogs.confirm(new ConfirmRequest(
                    "重建服务插件 API Key", "凭据轮换",
                    "旧 API Key 将立即失效；运行中的插件会安全重启。新 Key 只显示一次，确定继续？",
                    ConfirmKind.CONFIRM, 60, "", false));
            ApiKeyRotation rotation = decision.isAllow()
                    ? services.rotateEndpointApiKey(pluginId, endpointId) : null;
            return new MutationResult(services.list(), rotation);
        }, result -> {
            apply(result.plugins());
            if (result.rotation() != null) {
                ServicePluginOneTimeKeyDialog.show(root, plugin.name(), result.rotation());
                status.accept("API Key 已重建；请立即保存新密钥");
            }
        }, failure -> fail("重建服务插件 API Key 失败", failure));
    }

    private void fail(String prefix, Throwable failure) {
        failures.accept(prefix, failure);
        dialogs.notify(new ToastRequest(prefix, PluginCenterCleanup.failureMessage(failure)));
    }

    private void closeActivePage() {
        if (activePage == null) return;
        ServicePluginDeclarativePageRenderer.RenderedPage value = activePage;
        activePage = null;
        pageContent.getChildren().clear();
        try {
            value.close();
        } catch (RuntimeException failure) {
            fail("释放插件配置页面失败", failure);
        }
    }

    private VBox pageFailure(
            ServicePluginDeclarativePageRenderer.Page page, Throwable failure) {
        Label title = new Label(page.title() + "加载失败");
        title.getStyleClass().add("settings-group-title");
        Label detail = hint(PluginCenterCleanup.failureMessage(failure));
        detail.getStyleClass().add("service-plugin-error");
        Button retry = new Button("重试加载");
        retry.setId("servicePluginPageRetry");
        retry.getStyleClass().addAll("jc-btn", "jc-btn-sm");
        retry.setOnAction(ignored -> refresh());
        VBox error = new VBox(8, title, detail, retry);
        error.setId("servicePluginPageError");
        error.getStyleClass().addAll("jc-card", "service-plugin-error-state");
        return error;
    }

    private void visible(boolean value) {
        root.setVisible(value);
        root.setManaged(value);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-hint");
        return label;
    }

    private static String stateText(ServicePluginInfo value) {
        return switch (value.state()) {
            case HEALTHY -> "运行正常";
            case DEGRADED -> "降级运行";
            case STARTING -> "启动中";
            case STOPPING -> "停止中";
            case FAILED -> "启动失败";
            case QUARANTINED -> "已隔离";
            default -> "已停止";
        };
    }

    private static String stateClass(ServicePluginInfo value) {
        return switch (value.state()) {
            case HEALTHY -> "jc-badge-ok";
            case STARTING, STOPPING, DEGRADED -> "jc-badge-amber";
            case FAILED, QUARANTINED -> "jc-badge-fail";
            default -> "jc-badge-stopped";
        };
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        runtimeConfigurationChanged = () -> { };
        hide();
        refresh.close();
        mutation.close();
    }

    private record MutationResult(List<ServicePluginInfo> plugins, ApiKeyRotation rotation) { }
}
