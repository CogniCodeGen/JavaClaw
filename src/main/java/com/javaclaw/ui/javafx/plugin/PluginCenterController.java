package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Catalog;
import com.javaclaw.application.plugin.PluginManagementApplicationService.InstallResult;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 插件中心列表 FXML Controller：协调目录查询、安装与列表/详情导航。 */
public final class PluginCenterController implements AutoCloseable {

    @FXML private HBox root;
    @FXML private ToggleButton installedTab, marketTab, agentExtensionsTab, servicePluginsTab;
    @FXML private Button refreshButton, installButton, installAgentExtensionButton;
    @FXML private VBox listView;
    @FXML private TextField searchField;
    @FXML private Label countLabel, emptyMessage, directoryLabel, statusLabel;
    @FXML private ScrollPane cardScroll, detailPanel;
    @FXML private FlowPane cardGrid;
    @FXML private VBox marketPlaceholder, emptyPlaceholder;
    @FXML private StackPane loadingOverlay;
    @FXML private PluginDetailController detailPanelController;
    @FXML private VBox agentExtensionView, agentExtensionList;
    @FXML private Label agentExtensionCount;
    @FXML private VBox servicePluginView, servicePluginList;
    @FXML private Label servicePluginCount;
    @FXML private ServicePluginConfigurationController servicePluginConfigurationController;

    private final PluginManagementApplicationService useCases;
    private final PluginJarPicker jarPicker;
    private final DialogService dialogs;
    private final ManagedTaskExecutor tasks;
    private final FxDispatcher fx;
    private final PluginComponentFactory components;
    private final AgentExtensionManagementApplicationService agentExtensions;
    private final ServicePluginManagementApplicationService servicePlugins;
    private final UiAsyncAction<Catalog> refreshAction;
    private final UiAsyncAction<Catalog> toggleAction;
    private final ServicePluginApprovalAction approvalAction;
    private final UiAsyncAction<InstallResult> installAction;
    private final PluginCenterViewModel viewModel = new PluginCenterViewModel();
    private final List<PluginChildView<VBox>> cardViews = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private AutoCloseable catalogSubscription;
    private AgentExtensionPanel agentExtensionPanel;
    private ServicePluginWorkspace servicePluginWorkspace;
    private Runnable runtimeConfigurationChanged = () -> { };

    public PluginCenterController(
            PluginManagementApplicationService useCases,
            PluginJarPicker jarPicker,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            PluginComponentFactory components,
            AgentExtensionManagementApplicationService agentExtensions,
            ServicePluginManagementApplicationService servicePlugins) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.jarPicker = Objects.requireNonNull(jarPicker, "jarPicker");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.components = Objects.requireNonNull(components, "components");
        this.agentExtensions = Objects.requireNonNull(agentExtensions, "agentExtensions");
        this.servicePlugins = Objects.requireNonNull(servicePlugins, "servicePlugins");
        refreshAction = new UiAsyncAction<>(tasks, fx);
        toggleAction = new UiAsyncAction<>(tasks, fx);
        approvalAction = new ServicePluginApprovalAction(useCases, dialogs, tasks, fx,
                this::applyCatalog, this::servicePluginsTabRequested, viewModel::showStatus,
                this::showFailure, this::requestRefresh);
        installAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        agentExtensionPanel = new AgentExtensionPanel(
                agentExtensions, dialogs, tasks, fx,
                agentExtensionView, agentExtensionList, agentExtensionCount,
                installAgentExtensionButton, viewModel::showStatus, this::showFailure);
        servicePluginWorkspace = new ServicePluginWorkspace(servicePlugins, tasks, fx,
                servicePluginView, servicePluginList, servicePluginCount,
                servicePluginConfigurationController, viewModel::showStatus,
                this::showFailure, () -> { installedTabRequested();
                    viewModel.showStatus("请先批准并注册 Deliverance 服务插件"); });
        servicePluginWorkspace.setOnRuntimeConfigurationChanged(runtimeConfigurationChanged);
        String pluginsDirectory = useCases.pluginsDirectory().toString();
        directoryLabel.setText("插件目录  ·  " + pluginsDirectory);
        directoryLabel.setTooltip(new javafx.scene.control.Tooltip(pluginsDirectory));
        searchField.textProperty().bindBidirectional(viewModel.queryProperty());
        statusLabel.textProperty().bind(viewModel.statusProperty());
        statusLabel.visibleProperty().bind(viewModel.statusProperty().isNotEmpty());
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        viewModel.loadingProperty().bind(refreshAction.busyProperty());
        viewModel.mutatingProperty().bind(Bindings.or(
                Bindings.or(Bindings.or(toggleAction.busyProperty(), approvalAction.busyProperty()),
                        Bindings.or(installAction.busyProperty(), servicePluginWorkspace.busyProperty())),
                agentExtensionPanel.busyProperty()));
        loadingOverlay.visibleProperty().bind(Bindings.or(
                viewModel.loadingProperty(), viewModel.mutatingProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        refreshButton.disableProperty().bind(viewModel.loadingProperty());
        installButton.disableProperty().bind(viewModel.mutatingProperty());
        searchField.textProperty().addListener((ignored, oldValue, newValue) -> renderCards());
        detailPanelController.configure(
                this::showList, this::applyCatalog, approvalAction::execute);
        catalogSubscription = useCases.onCatalogChanged(
                () -> fx.dispatch(this::requestRefresh));
        requestRefresh();
    }

    @FXML
    private void installedTabRequested() {
        installedTab.setSelected(true);
        viewModel.tabProperty().set(PluginCenterViewModel.Tab.INSTALLED);
        showList();
    }

    @FXML
    private void marketTabRequested() {
        marketTab.setSelected(true);
        viewModel.tabProperty().set(PluginCenterViewModel.Tab.MARKET);
        viewModel.selectedIdProperty().set(null);
        showList();
    }

    @FXML
    private void agentExtensionsTabRequested() {
        agentExtensionsTab.setSelected(true);
        viewModel.tabProperty().set(PluginCenterViewModel.Tab.AGENT_EXTENSIONS);
        viewModel.selectedIdProperty().set(null);
        detailPanelController.hide();
        servicePluginWorkspace.hide();
        listView.setVisible(false);
        listView.setManaged(false);
        agentExtensionPanel.show();
    }

    @FXML
    private void servicePluginsTabRequested() {
        servicePluginsTab.setSelected(true);
        viewModel.tabProperty().set(PluginCenterViewModel.Tab.SERVICE_PLUGINS);
        viewModel.selectedIdProperty().set(null);
        detailPanelController.hide();
        agentExtensionPanel.hide();
        listView.setVisible(false);
        listView.setManaged(false);
        servicePluginWorkspace.showList();
    }

    @FXML
    private void refreshRequested() {
        if (viewModel.tabProperty().get() == PluginCenterViewModel.Tab.AGENT_EXTENSIONS) {
            agentExtensionPanel.refresh();
        } else if (viewModel.tabProperty().get() == PluginCenterViewModel.Tab.SERVICE_PLUGINS) {
            servicePluginWorkspace.refresh();
        } else {
            requestRefresh();
        }
    }

    @FXML
    private void installRequested() {
        jarPicker.choose(owner()).ifPresent(this::install);
    }

    @FXML
    private void installAgentExtensionRequested() {
        jarPicker.choose(owner()).ifPresent(agentExtensionPanel::install);
    }

    @FXML
    private void closeRequested() {
        Window window = owner();
        if (window != null) window.hide();
    }

    private void requestRefresh() {
        if (closed.get()) return;
        refreshAction.execute(
                TaskSpec.io("plugin-catalog-refresh"),
                context -> useCases.refresh(),
                this::applyCatalog,
                failure -> showFailure("刷新插件目录失败", failure));
    }

    private void install(Path jar) {
        installAction.execute(
                TaskSpec.io("plugin-install"),
                context -> useCases.install(jar),
                result -> {
                    applyCatalog(result.catalog());
                    if (result.servicePlugin()) {
                        servicePluginsTabRequested();
                        viewModel.showStatus("服务插件已安装，默认保持手动启动");
                    } else {
                        viewModel.select(result.pluginId());
                        showDetail(result.pluginId());
                        viewModel.showStatus("插件已安装");
                    }
                },
                failure -> {
                    showFailure("安装失败", failure);
                    dialogs.notify(new ToastRequest("安装失败",
                            PluginCenterCleanup.failureMessage(failure)));
                });
    }

    private void togglePlugin(String pluginId, boolean enabled) {
        toggleAction.execute(
                TaskSpec.io("plugin-toggle-" + pluginId),
                context -> useCases.setEnabled(pluginId, enabled),
                catalog -> {
                    applyCatalog(catalog);
                    viewModel.showStatus(enabled ? "插件已启用" : "插件已停用");
                },
                failure -> {
                    showFailure("插件状态更新失败", failure);
                    requestRefresh();
                });
    }

    private void applyCatalog(Catalog catalog) {
        if (closed.get()) return;
        viewModel.apply(catalog);
        countLabel.setText(viewModel.plugins().size() + " 个已安装");
        renderCards();
        if (viewModel.tabProperty().get() == PluginCenterViewModel.Tab.AGENT_EXTENSIONS
                || viewModel.tabProperty().get() == PluginCenterViewModel.Tab.SERVICE_PLUGINS) {
            return;
        }
        String selected = viewModel.selectedIdProperty().get();
        if (selected != null && detailPanelController.isShowing()) {
            showDetail(selected);
        } else if (selected == null) {
            detailPanelController.hide();
            listView.setVisible(true);
            listView.setManaged(true);
        }
    }

    private void showList() {
        viewModel.selectedIdProperty().set(null);
        detailPanelController.hide();
        listView.setVisible(true);
        listView.setManaged(true);
        agentExtensionPanel.hide();
        servicePluginWorkspace.hide();
        renderCards();
    }

    private void showDetail(String pluginId) {
        viewModel.select(pluginId);
        listView.setVisible(false);
        listView.setManaged(false);
        detailPanelController.showPlugin(pluginId);
    }

    void openServicePluginConfiguration(String pluginId, String pageId) {
        servicePluginsTabRequested(); servicePluginWorkspace.open(pluginId, pageId); }

    void configure(Runnable runtimeConfigurationChanged) {
        this.runtimeConfigurationChanged = Objects.requireNonNull(
                runtimeConfigurationChanged, "runtimeConfigurationChanged");
        if (servicePluginWorkspace != null) {
            servicePluginWorkspace.setOnRuntimeConfigurationChanged(runtimeConfigurationChanged);
        }
    }

    private void renderCards() {
        if (cardGrid == null || closed.get()) return;
        closeCards();
        PluginCenterViewModel.Tab tab = viewModel.tabProperty().get();
        if (tab == PluginCenterViewModel.Tab.AGENT_EXTENSIONS
                || tab == PluginCenterViewModel.Tab.SERVICE_PLUGINS) {
            return;
        }
        boolean market = tab == PluginCenterViewModel.Tab.MARKET;
        marketPlaceholder.setVisible(market);
        marketPlaceholder.setManaged(market);
        cardScroll.setVisible(!market);
        cardScroll.setManaged(!market);
        searchField.setDisable(market);
        countLabel.setText(market ? "" : viewModel.plugins().size() + " 个已安装");
        if (market) return;

        List<Plugin> visible = viewModel.filteredPlugins();
        boolean empty = visible.isEmpty();
        emptyPlaceholder.setVisible(empty);
        emptyPlaceholder.setManaged(empty);
        emptyMessage.setText(viewModel.plugins().isEmpty()
                ? "plugins/ 目录暂无插件\n点「从文件安装…」或把插件子目录放入该目录后刷新"
                : "没有匹配的插件");
        if (empty) return;
        for (Plugin plugin : visible) {
            PluginChildView<VBox> card = components.card(
                    plugin, this::showDetail, approvalAction::execute, this::togglePlugin);
            cardViews.add(card);
            cardGrid.getChildren().add(card.root());
        }
    }

    private void showFailure(String prefix, Throwable failure) {
        viewModel.showFailure(prefix, failure);
    }

    private Window owner() {
        Scene scene = root == null ? null : root.getScene();
        return scene == null ? null : scene.getWindow();
    }

    private void closeCards() {
        PluginCenterCleanup.closeCards(cardViews, cardGrid);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        runtimeConfigurationChanged = () -> { };
        RuntimeException failure = null;
        failure = PluginCenterCleanup.closeStep(failure, refreshAction::close);
        failure = PluginCenterCleanup.closeStep(failure, toggleAction::close);
        failure = PluginCenterCleanup.closeStep(failure, approvalAction::close);
        failure = PluginCenterCleanup.closeStep(failure, installAction::close);
        if (servicePluginWorkspace != null) {
            failure = PluginCenterCleanup.closeStep(failure, servicePluginWorkspace::close);
        }
        if (agentExtensionPanel != null) {
            failure = PluginCenterCleanup.closeStep(failure, agentExtensionPanel::close);
        }
        if (detailPanelController != null) {
            failure = PluginCenterCleanup.closeStep(failure, detailPanelController::close);
        }
        failure = PluginCenterCleanup.closeStep(failure, this::closeSubscription);
        failure = PluginCenterCleanup.closeStep(failure, this::closeCards);
        failure = PluginCenterCleanup.closeStep(failure, () -> PluginCenterCleanup.unbindViewState(
                searchField, statusLabel, viewModel, loadingOverlay, refreshButton, installButton));
        if (failure != null) throw failure;
    }

    private void closeSubscription() {
        if (catalogSubscription == null) return;
        try {
            catalogSubscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("关闭插件目录订阅失败", failure);
        } finally {
            catalogSubscription = null;
        }
    }

    boolean isClosed() { return closed.get(); }

    ScrollPane detailRoot() { return detailPanel; }
}
