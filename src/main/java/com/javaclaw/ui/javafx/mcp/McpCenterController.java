package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.mcp.McpManagementApplicationService;
import com.javaclaw.application.mcp.McpManagementApplicationService.OperationResult;
import com.javaclaw.application.mcp.McpManagementApplicationService.Server;
import com.javaclaw.application.mcp.McpManagementApplicationService.Snapshot;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** MCP 中心 Controller：协调 FXML 事件、应用用例与动态卡片生命周期。 */
public final class McpCenterController implements AutoCloseable {

    @FXML private HBox root;
    @FXML private VBox sidebar;
    @FXML private Button allFilter;
    @FXML private Button runningFilter;
    @FXML private Button failedFilter;
    @FXML private Button stoppedFilter;
    @FXML private Label sidebarStorageLabel;
    @FXML private Label contentTitle;
    @FXML private HBox overviewBar;
    @FXML private Label overviewText;
    @FXML private Label overviewFailure;
    @FXML private TextField searchField;
    @FXML private Label resultCountLabel;
    @FXML private HBox embeddedCreateBar;
    @FXML private VBox serverList;
    @FXML private VBox emptyState;
    @FXML private Label emptyTitle;
    @FXML private Label emptyHint;
    @FXML private Button emptyAddButton;
    @FXML private Button clearSearchButton;
    @FXML private Label embeddedStorageLabel;
    @FXML private Label embeddedStatusLabel;
    @FXML private HBox footer;
    @FXML private Label footerStatusLabel;
    @FXML private StackPane loadingOverlay;

    private final McpManagementApplicationService useCases;
    private final DialogService dialogs;
    private final FxDispatcher fx;
    private final McpServerCardFactory cards;
    private final McpServerEditorFactory editors;
    private final McpTemplateDialogFactory templates;
    private final McpImportDialogFactory imports;
    private final McpLogDialogFactory logs;
    private final UiAsyncAction<Snapshot> loadAction;
    private final UiAsyncAction<OperationResult> mutationAction;
    private final McpCenterViewModel viewModel = new McpCenterViewModel();
    private final List<McpServerCardFactory.Card> cardViews = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private AutoCloseable runtimeSubscription;
    private Runnable onConfigurationChanged = () -> { };
    private boolean standalone;

    public McpCenterController(
            McpManagementApplicationService useCases,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            McpServerCardFactory cards,
            McpServerEditorFactory editors,
            McpTemplateDialogFactory templates,
            McpImportDialogFactory imports,
            McpLogDialogFactory logs) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.cards = Objects.requireNonNull(cards, "cards");
        this.editors = Objects.requireNonNull(editors, "editors");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.imports = Objects.requireNonNull(imports, "imports");
        this.logs = Objects.requireNonNull(logs, "logs");
        loadAction = new UiAsyncAction<>(tasks, fx);
        mutationAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        searchField.textProperty().bindBidirectional(viewModel.queryProperty());
        embeddedStatusLabel.textProperty().bind(viewModel.statusProperty());
        footerStatusLabel.textProperty().bind(viewModel.statusProperty());
        viewModel.loadingProperty().bind(loadAction.busyProperty());
        viewModel.mutatingProperty().bind(mutationAction.busyProperty());
        loadingOverlay.visibleProperty().bind(Bindings.or(
                viewModel.loadingProperty(), viewModel.mutatingProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        searchField.textProperty().addListener((ignored, previous, value) -> render());
        runtimeSubscription = useCases.observeRuntime(() -> fx.dispatch(this::requestSnapshot));
        requestSnapshot();
    }

    void configure(boolean standalone, Runnable onConfigurationChanged) {
        this.standalone = standalone;
        this.onConfigurationChanged = onConfigurationChanged == null ? () -> { } : onConfigurationChanged;
        show(sidebar, standalone);
        show(footer, standalone);
        show(embeddedCreateBar, !standalone);
        show(embeddedStorageLabel, !standalone);
        show(embeddedStatusLabel, !standalone);
        if (standalone) root.getStyleClass().add("mcp-window-root");
        else root.getStyleClass().remove("mcp-window-root");
        render();
    }

    @FXML private void addRequested() { editors.show(owner(), null).ifPresent(this::save); }

    @FXML
    private void templateRequested() {
        templates.show(owner()).ifPresent(seed ->
                editors.showSeed(owner(), seed).ifPresent(this::save));
    }

    @FXML
    private void importRequested() {
        imports.show(owner()).ifPresent(command -> mutate("mcp-import",
                () -> useCases.importJson(command.json(), command.fallbackName()), true));
    }

    @FXML private void refreshRequested() {
        requestSnapshot();
        viewModel.showStatus("服务器状态已刷新", false);
        applyStatusStyle(false);
    }
    @FXML private void clearSearchRequested() { searchField.clear(); }
    @FXML private void allRequested() { select(McpCenterViewModel.Filter.ALL); }
    @FXML private void runningRequested() { select(McpCenterViewModel.Filter.RUNNING); }
    @FXML private void failedRequested() { select(McpCenterViewModel.Filter.FAILED); }
    @FXML private void stoppedRequested() { select(McpCenterViewModel.Filter.STOPPED); }

    @FXML
    private void copyStorageRequested() {
        ClipboardContent content = new ClipboardContent();
        content.putString(viewModel.snapshotProperty().get().storageDescription());
        Clipboard.getSystemClipboard().setContent(content);
        viewModel.showStatus("配置位置已复制", false);
        applyStatusStyle(false);
    }

    @FXML private void closeRequested() {
        Window window = owner();
        if (window != null) window.hide();
    }

    private void cardAction(String name, McpServerCardController.Action action) {
        switch (action) {
            case START -> mutate("mcp-start-" + name, () -> useCases.start(name), true);
            case RESTART -> mutate("mcp-restart-" + name, () -> useCases.restart(name), true);
            case STOP -> mutate("mcp-stop-" + name, () -> useCases.stop(name), true);
            case ENABLE -> mutate("mcp-enable-" + name, () -> useCases.setEnabled(name, true), true);
            case DISABLE -> mutate("mcp-disable-" + name, () -> useCases.setEnabled(name, false), true);
            case EDIT -> edit(name);
            case DELETE -> delete(name);
            case LOG -> logs.show(owner(), useCases.log(name));
            case COPY -> copyLaunch(name);
        }
    }

    private void edit(String name) {
        Server existing = viewModel.snapshotProperty().get().require(name);
        editors.show(owner(), existing).ifPresent(this::save);
    }

    private void save(McpManagementApplicationService.SaveCommand command) {
        mutate("mcp-save-" + command.name(), () -> useCases.save(command), true);
    }

    private void delete(String name) {
        mutate("mcp-delete-" + name, () -> {
            ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                    "删除 MCP 服务器", "确认删除",
                    "确定删除 MCP 服务器「" + name + "」？此操作不会删除服务端数据。",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.delete(name) : null;
        }, true);
    }

    private void copyLaunch(String name) {
        Server server = viewModel.snapshotProperty().get().require(name);
        ClipboardContent content = new ClipboardContent();
        content.putString(server.launchSummary());
        Clipboard.getSystemClipboard().setContent(content);
        viewModel.showStatus((server.transport() == McpManagementApplicationService.Transport.HTTP
                ? "URL" : "启动命令") + "已复制", false);
        applyStatusStyle(false);
    }

    private void requestSnapshot() {
        if (closed.get()) return;
        loadAction.execute(TaskSpec.io("mcp-snapshot"), context -> useCases.snapshot(),
                this::apply, failure -> showFailure("加载 MCP 服务器失败", failure));
    }

    private void mutate(String taskName, Callable<OperationResult> operation, boolean notifyChange) {
        mutationAction.execute(TaskSpec.io(taskName), context -> operation.call(), result -> {
            if (result == null) return;
            apply(result.snapshot());
            viewModel.showStatus(result.runtimeMessage(), !result.runtimeSucceeded());
            applyStatusStyle(!result.runtimeSucceeded());
            if (notifyChange) onConfigurationChanged.run();
        }, failure -> {
            showFailure("MCP 操作失败", failure);
            requestSnapshot();
        });
    }

    private void apply(Snapshot snapshot) {
        if (closed.get()) return;
        viewModel.apply(snapshot);
        sidebarStorageLabel.setText(snapshot.storageDescription());
        sidebarStorageLabel.setTooltip(new javafx.scene.control.Tooltip(snapshot.storageDescription()));
        embeddedStorageLabel.setText("配置位置: " + snapshot.storageDescription());
        render();
    }

    private void render() {
        if (serverList == null || closed.get()) return;
        McpCenterViewModel.Counts counts = viewModel.counts();
        contentTitle.setText(viewModel.contentTitle(standalone));
        allFilter.setText("全部服务器  " + counts.total());
        runningFilter.setText("运行中  " + counts.running());
        failedFilter.setText("需要处理  " + counts.failed());
        stoppedFilter.setText("已停止  " + counts.stopped());
        styleFilters();
        overviewText.setText("运行中 " + counts.running() + "/" + counts.enabled()
                + " 服务器 · 共 " + counts.tools() + " 个工具");
        overviewFailure.setText(counts.firstFailure().isBlank() ? "" : "⚠ " + counts.firstFailure());
        show(overviewFailure, !counts.firstFailure().isBlank());
        List<Server> visible = viewModel.visibleServers();
        resultCountLabel.setText(visible.size() == counts.total()
                ? counts.total() + " 个" : visible.size() + " / " + counts.total() + " 个");
        renderCards(visible);
        boolean empty = visible.isEmpty();
        show(emptyState, empty);
        show(serverList, !empty);
        boolean noConfiguration = counts.total() == 0;
        emptyTitle.setText(noConfiguration ? "还没有 MCP 服务器" : "没有匹配的服务器");
        emptyHint.setText(noConfiguration
                ? "添加本地 stdio 或远程 HTTP 服务，让智能体使用外部工具和数据。"
                : "尝试更换筛选条件或清空搜索关键词。");
        show(emptyAddButton, noConfiguration);
        show(clearSearchButton, !noConfiguration && !viewModel.queryProperty().get().isBlank());
    }

    private void renderCards(List<Server> servers) {
        closeCards();
        for (Server server : servers) {
            McpServerCardFactory.Card card = cards.create(server, this::cardAction);
            cardViews.add(card);
            serverList.getChildren().add(card.root());
        }
    }

    private void select(McpCenterViewModel.Filter filter) {
        viewModel.filterProperty().set(filter);
        render();
    }

    private void styleFilters() {
        List<Button> buttons = List.of(allFilter, runningFilter, failedFilter, stoppedFilter);
        buttons.forEach(button -> button.getStyleClass().remove("modal-nav-btn-selected"));
        switch (viewModel.filterProperty().get()) {
            case ALL -> allFilter.getStyleClass().add("modal-nav-btn-selected");
            case RUNNING -> runningFilter.getStyleClass().add("modal-nav-btn-selected");
            case FAILED -> failedFilter.getStyleClass().add("modal-nav-btn-selected");
            case STOPPED -> stoppedFilter.getStyleClass().add("modal-nav-btn-selected");
        }
    }

    private void showFailure(String prefix, Throwable failure) {
        viewModel.showStatus(prefix + "：" + failureMessage(failure), true);
        applyStatusStyle(true);
    }

    private void applyStatusStyle(boolean error) {
        for (Label label : List.of(embeddedStatusLabel, footerStatusLabel)) {
            label.getStyleClass().removeAll("status-success", "status-error", "status-info");
            label.getStyleClass().add(error ? "status-error" : "status-success");
        }
    }

    private Window owner() { return root.getScene() == null ? null : root.getScene().getWindow(); }

    private static String failureMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "未知错误" : failure.getMessage();
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void closeCards() {
        RuntimeException failure = null;
        for (int index = cardViews.size() - 1; index >= 0; index--) {
            try { cardViews.get(index).close(); }
            catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        cardViews.clear();
        if (serverList != null) serverList.getChildren().clear();
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loadAction.close();
        mutationAction.close();
        if (runtimeSubscription != null) {
            try { runtimeSubscription.close(); }
            catch (Exception failure) { throw new IllegalStateException("关闭 MCP 状态订阅失败", failure); }
            finally { runtimeSubscription = null; }
        }
        closeCards();
        if (searchField != null) {
            searchField.textProperty().unbindBidirectional(viewModel.queryProperty());
        }
        if (embeddedStatusLabel != null) embeddedStatusLabel.textProperty().unbind();
        if (footerStatusLabel != null) footerStatusLabel.textProperty().unbind();
        viewModel.loadingProperty().unbind();
        viewModel.mutatingProperty().unbind();
        if (loadingOverlay != null) {
            loadingOverlay.visibleProperty().unbind();
            loadingOverlay.managedProperty().unbind();
        }
        onConfigurationChanged = () -> { };
    }

    boolean isClosed() { return closed.get(); }
}
