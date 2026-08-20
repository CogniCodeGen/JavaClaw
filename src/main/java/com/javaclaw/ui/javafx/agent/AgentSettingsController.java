package com.javaclaw.ui.javafx.agent;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.agent.AgentManagementApplicationService;
import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.application.agent.AgentManagementApplicationService.Catalog;
import com.javaclaw.application.agent.AgentManagementApplicationService.ChangeResult;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.agent.AgentRowFactory.AgentRowView;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 智能体设置主 Controller：只协调目录、编辑器事件和托管异步动作。 */
public final class AgentSettingsController implements AutoCloseable {

    @FXML private HBox root;
    @FXML private Button addButton;
    @FXML private VBox builtInRows;
    @FXML private VBox customRows;
    @FXML private Label customEmptyLabel;
    @FXML private VBox emptyPanel;
    @FXML private VBox editorPanel;
    @FXML private AgentEditorController editorPanelController;
    @FXML private StackPane loadingOverlay;

    private final AgentManagementApplicationService useCases;
    private final DialogService dialogs;
    private final FxDispatcher fx;
    private final AgentRowFactory rows;
    private final UiAsyncAction<Catalog> loadAction;
    private final UiAsyncAction<ChangeResult> createAction;
    private final UiAsyncAction<Catalog> mutationAction;
    private final UiAsyncAction<String> optimizeAction;
    private final AgentSettingsViewModel viewModel = new AgentSettingsViewModel();
    private final List<AgentRowView> rowViews = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable onConfigChanged = () -> {};

    public AgentSettingsController(
            AgentManagementApplicationService useCases,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            AgentRowFactory rows) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.rows = Objects.requireNonNull(rows, "rows");
        loadAction = new UiAsyncAction<>(tasks, fx);
        createAction = new UiAsyncAction<>(tasks, fx);
        mutationAction = new UiAsyncAction<>(tasks, fx);
        optimizeAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        viewModel.loadingProperty().bind(loadAction.busyProperty());
        viewModel.mutatingProperty().bind(Bindings.or(
                createAction.busyProperty(), mutationAction.busyProperty()));
        viewModel.optimizingProperty().bind(optimizeAction.busyProperty());
        loadingOverlay.visibleProperty().bind(Bindings.or(
                viewModel.loadingProperty(), Bindings.or(
                        viewModel.mutatingProperty(), viewModel.optimizingProperty())));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        addButton.disableProperty().bind(viewModel.mutatingProperty());
        editorPanelController.configure(
                this::saveSelected, this::optimizePrompt, this::deleteSelected);
        editorPanelController.bind(viewModel);
    }

    /** 面板首次可见后再读取目录，FXML 构造阶段不触发数据访问。 */
    public void activate() {
        if (closed.get()) return;
        requestCatalog();
    }

    public void deactivate() { loadAction.cancel(); }

    void configure(Runnable configChanged) {
        onConfigChanged = Objects.requireNonNull(configChanged, "configChanged");
    }

    @FXML
    private void createRequested() {
        createAction.execute(
                TaskSpec.io("custom-agent-create"),
                context -> useCases.create(),
                result -> {
                    viewModel.selectedIdProperty().set(result.agent().id());
                    applyCatalog(result.catalog());
                    viewModel.showStatus("已创建，请完善配置后保存", false);
                    fx.dispatchLater(editorPanelController::focusName);
                },
                failure -> showFailure("创建智能体失败", failure));
    }

    private void requestCatalog() {
        loadAction.execute(
                TaskSpec.io("agent-catalog-load"),
                context -> useCases.catalog(),
                this::applyCatalog,
                failure -> showFailure("加载智能体目录失败", failure));
    }

    private void saveSelected() {
        var command = editorPanelController.saveCommand();
        mutationAction.execute(
                TaskSpec.io("custom-agent-save-" + command.id()),
                context -> useCases.save(command),
                catalog -> {
                    applyCatalog(catalog);
                    viewModel.showStatus("已保存", false);
                    onConfigChanged.run();
                },
                failure -> showFailure("保存智能体失败", failure));
    }

    private void optimizePrompt() {
        var command = editorPanelController.optimizeCommand();
        viewModel.showStatus("正在调用模型生成提示词…", false);
        optimizeAction.execute(
                TaskSpec.io("custom-agent-optimize-prompt"),
                context -> useCases.optimize(command),
                prompt -> {
                    editorPanelController.setGeneratedPrompt(prompt);
                    viewModel.showStatus("已生成提示词，记得点击保存", false);
                },
                failure -> showFailure("优化失败", failure));
    }

    private void deleteSelected() {
        Agent selected = editorPanelController.current();
        if (selected == null || selected.builtIn()) return;
        mutationAction.execute(
                TaskSpec.io("custom-agent-delete-" + selected.id()),
                context -> confirmDelete(selected),
                catalog -> {
                    if (catalog == null) return;
                    viewModel.selectedIdProperty().set(null);
                    applyCatalog(catalog);
                    viewModel.showStatus("智能体已删除", false);
                    onConfigChanged.run();
                },
                failure -> showFailure("删除智能体失败", failure));
    }

    private Catalog confirmDelete(Agent selected) {
        ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                "确认删除", "删除智能体",
                "删除智能体「" + selected.name() + "」后不可恢复，确认继续？",
                ConfirmKind.CONFIRM, 60, "", false));
        return decision.isAllow() ? useCases.delete(selected.id()) : null;
    }

    private void applyCatalog(Catalog catalog) {
        if (closed.get()) return;
        viewModel.apply(catalog);
        renderRows();
        viewModel.selectedAgent().ifPresentOrElse(
                this::showEditor, this::showEmptyEditor);
    }

    private void selectAgent(String id) {
        viewModel.selectedIdProperty().set(id);
        renderRows();
        viewModel.selectedAgent().ifPresentOrElse(
                this::showEditor, this::showEmptyEditor);
        viewModel.showStatus("", false);
    }

    private void showEditor(Agent agent) {
        emptyPanel.setVisible(false);
        emptyPanel.setManaged(false);
        editorPanelController.show(agent, viewModel.catalogProperty().get().forms());
    }

    private void showEmptyEditor() {
        editorPanelController.hide();
        emptyPanel.setVisible(true);
        emptyPanel.setManaged(true);
    }

    private void renderRows() {
        closeRows();
        String selected = viewModel.selectedIdProperty().get();
        List<Agent> custom = new ArrayList<>();
        for (Agent agent : viewModel.catalogProperty().get().agents()) {
            if (!agent.builtIn()) {
                custom.add(agent);
                continue;
            }
            addRow(builtInRows, agent, Objects.equals(selected, agent.id()));
        }
        custom.forEach(agent -> addRow(
                customRows, agent, Objects.equals(selected, agent.id())));
        customEmptyLabel.setVisible(custom.isEmpty());
        customEmptyLabel.setManaged(custom.isEmpty());
    }

    private void addRow(VBox container, Agent agent, boolean selected) {
        AgentRowView row = rows.create(agent, selected, this::selectAgent);
        rowViews.add(row);
        container.getChildren().add(row.root());
    }

    private void closeRows() {
        RuntimeException failure = null;
        for (int index = rowViews.size() - 1; index >= 0; index--) {
            try {
                rowViews.get(index).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        rowViews.clear();
        if (builtInRows != null) builtInRows.getChildren().clear();
        if (customRows != null) customRows.getChildren().clear();
        if (failure != null) throw failure;
    }

    private void showFailure(String prefix, Throwable failure) {
        String detail = failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank() ? "未知错误" : failure.getMessage();
        viewModel.showStatus(prefix + "：" + detail, true);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loadAction.close();
        createAction.close();
        mutationAction.close();
        optimizeAction.close();
        if (editorPanelController != null) editorPanelController.close();
        closeRows();
        viewModel.loadingProperty().unbind();
        viewModel.mutatingProperty().unbind();
        viewModel.optimizingProperty().unbind();
        if (loadingOverlay != null) {
            loadingOverlay.visibleProperty().unbind();
            loadingOverlay.managedProperty().unbind();
        }
        if (addButton != null) addButton.disableProperty().unbind();
    }

    boolean isClosed() { return closed.get(); }
}
