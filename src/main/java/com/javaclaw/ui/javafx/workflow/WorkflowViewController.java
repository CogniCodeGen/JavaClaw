package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService;
import com.javaclaw.application.workflow.WorkflowApplicationService.OperationResult;
import com.javaclaw.application.workflow.WorkflowApplicationService.PublishResult;
import com.javaclaw.application.workflow.WorkflowApplicationService.Snapshot;
import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.runtime.ValidationIssue;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 工作流窗口 Controller：协调用户事件、应用服务与各 FXML 子控制器。 */
public final class WorkflowViewController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private ListView<WorkflowItem> workflowList;
    @FXML private Label titleLabel;
    @FXML private Label titleHint;
    @FXML private Label saveStateLabel;
    @FXML private MenuButton nodePalette;
    @FXML private Button publishButton;
    @FXML private Button testButton;
    @FXML private StackPane loadingOverlay;
    @FXML private WorkflowCanvasController canvasController;
    @FXML private WorkflowInspectorController inspectorController;
    @FXML private WorkflowRuntimeController runtimeController;

    private final WorkflowApplicationService useCases;
    private final WorkflowDefinitionCellFactory cells;
    private final WorkflowInputDialogFactory inputDialogs;
    private final FxDispatcher fx;
    private final WorkflowViewModel viewModel = new WorkflowViewModel();
    private final UiAsyncAction<Snapshot> loadAction;
    private final UiAsyncAction<OperationResult> definitionAction;
    private final UiAsyncAction<Void> saveAction;
    private final UiAsyncAction<PublishResult> publishAction;
    private final UiAsyncAction<List<ValidationIssue>> validateAction;
    private final UiAsyncAction<Void> testAction;
    private final PauseTransition autosave = new PauseTransition(Duration.millis(500));
    private final AtomicBoolean closed = new AtomicBoolean();
    private Consumer<WorkflowItem> onPublished = ignored -> { };
    private Runnable closeAction = () -> { };
    private boolean restoringSelection;

    public WorkflowViewController(
            WorkflowApplicationService useCases,
            WorkflowDefinitionCellFactory cells,
            WorkflowInputDialogFactory inputDialogs,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.inputDialogs = Objects.requireNonNull(inputDialogs, "inputDialogs");
        this.fx = Objects.requireNonNull(fx, "fx");
        loadAction = new UiAsyncAction<>(tasks, fx);
        definitionAction = new UiAsyncAction<>(tasks, fx);
        saveAction = new UiAsyncAction<>(tasks, fx);
        publishAction = new UiAsyncAction<>(tasks, fx);
        validateAction = new UiAsyncAction<>(tasks, fx);
        testAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        workflowList.setItems(viewModel.workflows());
        workflowList.setCellFactory(ignored -> cells.create());
        workflowList.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> {
                    if (!restoringSelection && selected != null) selectionRequested(selected);
                });
        titleLabel.textProperty().bind(viewModel.titleProperty());
        titleHint.textProperty().bind(viewModel.titleHintProperty());
        saveStateLabel.textProperty().bind(viewModel.saveStateProperty());
        viewModel.saveStateProperty().addListener(
                (ignored, previous, value) -> updateSaveStateStyle(value));
        nodePalette.disableProperty().bind(viewModel.readOnlyProperty()
                .or(viewModel.selectedWorkflowProperty().isNull()));
        publishButton.disableProperty().bind(viewModel.readOnlyProperty()
                .or(viewModel.selectedWorkflowProperty().isNull())
                .or(publishAction.busyProperty()));
        testButton.disableProperty().bind(viewModel.selectedWorkflowProperty().isNull()
                .or(testAction.busyProperty()));
        loadingOverlay.visibleProperty().bind(loadAction.busyProperty()
                .or(definitionAction.busyProperty()).or(publishAction.busyProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        canvasController.configure(viewModel, this::changed, this::log);
        inspectorController.configure(viewModel, this::changed,
                canvasController::beginConnection, this::log);
        runtimeController.configure(viewModel, this::log);
        autosave.setOnFinished(event -> saveDraft(null));
    }

    void configure(Consumer<WorkflowItem> onPublished, Runnable closeAction) {
        this.onPublished = onPublished == null ? ignored -> { } : onPublished;
        this.closeAction = closeAction == null ? () -> { } : closeAction;
    }

    void prepare() {
        requestSnapshot(null);
    }

    CompletableFuture<Boolean> prepareClose() {
        autosave.stop();
        if (!viewModel.dirtyProperty().get()) return CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        saveDraft(() -> result.complete(true), failure -> result.complete(false));
        return result;
    }

    @FXML
    private void createRequested() {
        afterDraftSaved(() -> definitionAction.execute(TaskSpec.io("workflow-create"),
                context -> useCases.createDraft(), this::apply,
                failure -> failed("新建工作流失败", failure)));
    }

    @FXML
    private void cloneRequested() {
        WorkflowItem selected = viewModel.selectedWorkflowProperty().get();
        if (selected == null) return;
        afterDraftSaved(() -> definitionAction.execute(
                TaskSpec.io("workflow-clone-" + selected.id()),
                context -> useCases.cloneDraft(selected.id()), this::apply,
                failure -> failed("复制工作流失败", failure)));
    }

    @FXML private void undoRequested() { edit(() -> viewModel.editor().undo()); }

    @FXML private void redoRequested() { edit(() -> viewModel.editor().redo()); }

    @FXML private void addAgent() { add(NodeType.AGENT); }

    @FXML private void addTool() { add(NodeType.TOOL); }

    @FXML private void addCondition() { add(NodeType.CONDITION); }

    @FXML private void addTransform() { add(NodeType.TRANSFORM); }

    @FXML private void addHumanInput() { add(NodeType.HUMAN_INPUT); }

    @FXML private void addOutput() { add(NodeType.OUTPUT); }

    @FXML
    private void validateRequested() {
        if (!viewModel.hasSelection()) return;
        var graph = viewModel.currentGraph();
        validateAction.execute(TaskSpec.io("workflow-validate-" + graph.id()),
                context -> useCases.validate(graph), issues -> {
                    if (issues.isEmpty()) log("校验通过");
                    else issues.forEach(this::logIssue);
                }, failure -> failed("校验失败", failure));
    }

    @FXML
    private void publishRequested() {
        if (!viewModel.hasSelection() || viewModel.readOnlyProperty().get()) return;
        autosave.stop();
        var graph = viewModel.currentGraph();
        publishAction.execute(TaskSpec.io("workflow-publish-" + graph.id()),
                context -> useCases.publish(graph), result -> {
                    viewModel.markSaved();
                    applySnapshot(result.snapshot(), result.published().id());
                    log("发布成功");
                    try {
                        onPublished.accept(result.published());
                    } catch (RuntimeException callbackFailure) {
                        log("聊天模式列表刷新失败，可重新展开工作流下拉框重试："
                                + callbackFailure.getMessage());
                    }
                }, failure -> failed("发布失败", failure));
    }

    @FXML
    private void testRequested() {
        if (!viewModel.hasSelection()) return;
        inputDialogs.show(root.getScene() == null ? null : root.getScene().getWindow())
                .ifPresent(input -> {
                    var graph = viewModel.currentGraph();
                    testAction.execute(TaskSpec.io("workflow-test-" + graph.id()), context -> {
                        useCases.testRun(graph, input, runtimeController.observer());
                        return null;
                    }, ignored -> log("已启动测试运行"),
                            failure -> failed("启动失败", failure));
                });
    }

    @FXML private void closeRequested() { closeAction.run(); }

    void cancelConnection() { canvasController.cancelConnection(); }

    private void requestSnapshot(String preferredId) {
        loadAction.execute(TaskSpec.io("workflow-snapshot"), context -> useCases.snapshot(),
                snapshot -> applySnapshot(snapshot, preferredId),
                failure -> failed("加载工作流失败", failure));
    }

    private void selectionRequested(WorkflowItem requested) {
        WorkflowItem current = viewModel.selectedWorkflowProperty().get();
        if (current != null && current.id().equals(requested.id())) return;
        restoreListSelection(current);
        afterDraftSaved(() -> select(requested));
    }

    private void select(WorkflowItem item) {
        canvasController.cancelConnection();
        viewModel.select(item);
        restoreListSelection(item);
        runtimeController.refresh();
    }

    private void apply(OperationResult result) {
        applySnapshot(result.snapshot(), result.selectedId());
    }

    private void applySnapshot(Snapshot snapshot, String preferredId) {
        String selectedId = preferredId;
        if ((selectedId == null || selectedId.isBlank())
                && viewModel.selectedWorkflowProperty().get() != null) {
            selectedId = viewModel.selectedWorkflowProperty().get().id();
        }
        viewModel.apply(snapshot);
        WorkflowItem selected = selectedId == null ? null : snapshot.find(selectedId);
        if (selected == null && !snapshot.workflows().isEmpty()) {
            selected = snapshot.workflows().getFirst();
        }
        select(selected);
    }

    private void restoreListSelection(WorkflowItem item) {
        restoringSelection = true;
        if (item == null) workflowList.getSelectionModel().clearSelection();
        else workflowList.getSelectionModel().select(item);
        restoringSelection = false;
    }

    private void add(NodeType type) {
        if (!editable()) return;
        long count = viewModel.currentGraph().nodes().stream()
                .filter(node -> node.type() != NodeType.START && node.type() != NodeType.END)
                .count();
        viewModel.editor().addNode(type, 310 + (count % 2) * 230,
                240 + (count / 2) * 130);
        changed();
    }

    private void edit(Runnable edit) {
        if (!editable()) return;
        edit.run();
        changed();
    }

    private boolean editable() {
        return viewModel.editor() != null && !viewModel.readOnlyProperty().get();
    }

    private void changed() {
        viewModel.changed();
        autosave.playFromStart();
    }

    private void afterDraftSaved(Runnable continuation) {
        if (!viewModel.dirtyProperty().get()) {
            continuation.run();
            return;
        }
        autosave.stop();
        saveDraft(continuation);
    }

    private void saveDraft(Runnable success) {
        saveDraft(success, failure -> failed("草稿保存失败", failure));
    }

    private void saveDraft(Runnable success, Consumer<Throwable> failureHandler) {
        if (viewModel.editor() == null || viewModel.readOnlyProperty().get()) {
            if (success != null) success.run();
            return;
        }
        var graph = viewModel.currentGraph();
        saveAction.execute(TaskSpec.io("workflow-save-" + graph.id()), context -> {
            useCases.saveDraft(graph);
            return null;
        }, ignored -> {
            viewModel.markSaved();
            if (success != null) success.run();
        }, failure -> {
            viewModel.markSaveFailed();
            failureHandler.accept(failure);
        });
    }

    private void logIssue(ValidationIssue issue) {
        log(issue.severity() + " [" + issue.elementId() + "] " + issue.message());
    }

    private void log(String message) { viewModel.appendLog(message); }

    private void failed(String operation, Throwable failure) {
        log(operation + "：" + (failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage()));
    }

    private void updateSaveStateStyle(String value) {
        saveStateLabel.getStyleClass().removeAll(
                "workflow-save-pending", "workflow-save-readonly");
        if ("只读".equals(value)) saveStateLabel.getStyleClass().add("workflow-save-readonly");
        else if ("保存中…".equals(value) || "保存失败".equals(value)) {
            saveStateLabel.getStyleClass().add("workflow-save-pending");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        autosave.stop();
        loadAction.close();
        definitionAction.close();
        saveAction.close();
        publishAction.close();
        validateAction.close();
        testAction.close();
        titleLabel.textProperty().unbind();
        titleHint.textProperty().unbind();
        saveStateLabel.textProperty().unbind();
        nodePalette.disableProperty().unbind();
        publishButton.disableProperty().unbind();
        testButton.disableProperty().unbind();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
    }
}
