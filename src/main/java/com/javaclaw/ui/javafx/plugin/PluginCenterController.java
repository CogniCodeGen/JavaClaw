package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Catalog;
import com.javaclaw.application.plugin.PluginManagementApplicationService.InstallResult;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
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
    @FXML private ToggleButton installedTab;
    @FXML private ToggleButton marketTab;
    @FXML private Button refreshButton;
    @FXML private Button installButton;
    @FXML private VBox listView;
    @FXML private TextField searchField;
    @FXML private Label countLabel;
    @FXML private ScrollPane cardScroll;
    @FXML private FlowPane cardGrid;
    @FXML private VBox marketPlaceholder;
    @FXML private VBox emptyPlaceholder;
    @FXML private Label emptyMessage;
    @FXML private StackPane loadingOverlay;
    @FXML private ScrollPane detailPanel;
    @FXML private PluginDetailController detailPanelController;
    @FXML private Label directoryLabel;
    @FXML private Label statusLabel;

    private final PluginManagementApplicationService useCases;
    private final PluginJarPicker jarPicker;
    private final DialogService dialogs;
    private final FxDispatcher fx;
    private final PluginComponentFactory components;
    private final UiAsyncAction<Catalog> refreshAction;
    private final UiAsyncAction<Catalog> toggleAction;
    private final UiAsyncAction<InstallResult> installAction;
    private final PluginCenterViewModel viewModel = new PluginCenterViewModel();
    private final List<PluginChildView<VBox>> cardViews = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private AutoCloseable catalogSubscription;

    public PluginCenterController(
            PluginManagementApplicationService useCases,
            PluginJarPicker jarPicker,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            PluginComponentFactory components) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.jarPicker = Objects.requireNonNull(jarPicker, "jarPicker");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.components = Objects.requireNonNull(components, "components");
        refreshAction = new UiAsyncAction<>(tasks, fx);
        toggleAction = new UiAsyncAction<>(tasks, fx);
        installAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        directoryLabel.setText(useCases.pluginsDirectory().toString());
        searchField.textProperty().bindBidirectional(viewModel.queryProperty());
        statusLabel.textProperty().bind(viewModel.statusProperty());
        statusLabel.visibleProperty().bind(viewModel.statusProperty().isNotEmpty());
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        viewModel.loadingProperty().bind(refreshAction.busyProperty());
        viewModel.mutatingProperty().bind(Bindings.or(
                toggleAction.busyProperty(), installAction.busyProperty()));
        loadingOverlay.visibleProperty().bind(Bindings.or(
                viewModel.loadingProperty(), viewModel.mutatingProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        refreshButton.disableProperty().bind(viewModel.loadingProperty());
        installButton.disableProperty().bind(viewModel.mutatingProperty());
        searchField.textProperty().addListener((ignored, oldValue, newValue) -> renderCards());
        detailPanelController.configure(this::showList, this::applyCatalog);
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
    private void refreshRequested() { requestRefresh(); }

    @FXML
    private void installRequested() {
        jarPicker.choose(owner()).ifPresent(this::install);
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
                    viewModel.select(result.pluginId());
                    applyCatalog(result.catalog());
                    showDetail(result.pluginId());
                    viewModel.showStatus("插件已安装");
                },
                failure -> {
                    showFailure("安装失败", failure);
                    dialogs.notify(new ToastRequest("安装失败", failureMessage(failure)));
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
        renderCards();
    }

    private void showDetail(String pluginId) {
        viewModel.select(pluginId);
        listView.setVisible(false);
        listView.setManaged(false);
        detailPanelController.showPlugin(pluginId);
    }

    private void renderCards() {
        if (cardGrid == null || closed.get()) return;
        closeCards();
        PluginCenterViewModel.Tab tab = viewModel.tabProperty().get();
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
                    plugin, this::showDetail, this::togglePlugin);
            cardViews.add(card);
            cardGrid.getChildren().add(card.root());
        }
    }

    private void showFailure(String prefix, Throwable failure) {
        viewModel.showFailure(prefix, failure);
    }

    private static String failureMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "未知错误" : failure.getMessage();
    }

    private Window owner() {
        Scene scene = root == null ? null : root.getScene();
        return scene == null ? null : scene.getWindow();
    }

    private void closeCards() {
        RuntimeException failure = null;
        for (int i = cardViews.size() - 1; i >= 0; i--) {
            try {
                cardViews.get(i).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        cardViews.clear();
        if (cardGrid != null) cardGrid.getChildren().clear();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        failure = closeStep(failure, refreshAction::close);
        failure = closeStep(failure, toggleAction::close);
        failure = closeStep(failure, installAction::close);
        if (detailPanelController != null) {
            failure = closeStep(failure, detailPanelController::close);
        }
        failure = closeStep(failure, this::closeSubscription);
        failure = closeStep(failure, this::closeCards);
        failure = closeStep(failure, this::unbindViewState);
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

    private void unbindViewState() {
        if (searchField == null) return;
        searchField.textProperty().unbindBidirectional(viewModel.queryProperty());
        statusLabel.textProperty().unbind();
        statusLabel.visibleProperty().unbind();
        statusLabel.managedProperty().unbind();
        viewModel.loadingProperty().unbind();
        viewModel.mutatingProperty().unbind();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
        refreshButton.disableProperty().unbind();
        installButton.disableProperty().unbind();
    }

    private static RuntimeException closeStep(RuntimeException current, Runnable step) {
        try {
            step.run();
            return current;
        } catch (RuntimeException failure) {
            if (current == null) return failure;
            current.addSuppressed(failure);
            return current;
        }
    }

    boolean isClosed() { return closed.get(); }

    ScrollPane detailRoot() { return detailPanel; }
}
