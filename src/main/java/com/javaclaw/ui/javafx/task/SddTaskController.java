package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.application.task.SddTaskApplicationService.Event;
import com.javaclaw.application.task.SddTaskApplicationService.Snapshot;
import com.javaclaw.application.task.SddTaskApplicationService.Task;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.task.sdd.run.SddTaskState;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDD 主 Controller：协调 FXML 事件、Application UseCase 与可取消 UI 异步动作。 */
public final class SddTaskController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SddTaskController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FXML private HBox root;
    @FXML private Label listSubtitle;
    @FXML private ListView<Task> taskList;
    @FXML private StackPane contentArea;
    @FXML private VBox emptyPanel;
    @FXML private VBox detailPanel;
    @FXML private SddTaskDetailController detailPanelController;
    @FXML private VBox createPanel;
    @FXML private SddTaskCreateController createPanelController;
    @FXML private Label statusLabel;
    @FXML private HBox actionBox;
    @FXML private Button startButton;
    @FXML private Button pauseButton;
    @FXML private Button cancelButton;
    @FXML private Button resumeButton;
    @FXML private Button budgetButton;
    @FXML private Button resumeBudgetButton;
    @FXML private Button rerunButton;
    @FXML private Button deleteButton;
    @FXML private StackPane loadingOverlay;

    private final SddTaskApplicationService useCases;
    private final SddTaskCellFactory cells;
    private final SddBudgetDialogFactory budgets;
    private final FxDispatcher fx;
    private final SddTaskViewModel viewModel = new SddTaskViewModel();
    private final UiAsyncAction<Snapshot> loadAction;
    private final UiAsyncAction<Detail> detailAction;
    private final UiAsyncAction<Task> createAction;
    private final UiAsyncAction<Object> operationAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private AutoCloseable subscription;
    private Runnable closeAction = () -> { };
    private String selectedId = "";
    private int selectedProgress = -1;
    private OpenSpecChange currentChange;

    public SddTaskController(
            SddTaskApplicationService useCases,
            SddTaskCellFactory cells,
            SddBudgetDialogFactory budgets,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.fx = Objects.requireNonNull(fx, "fx");
        loadAction = new UiAsyncAction<>(tasks, fx);
        detailAction = new UiAsyncAction<>(tasks, fx);
        createAction = new UiAsyncAction<>(tasks, fx);
        operationAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        taskList.setItems(viewModel.tasks());
        taskList.setCellFactory(ignored -> cells.create(this::select));
        listSubtitle.textProperty().bind(viewModel.subtitleProperty());
        statusLabel.textProperty().bind(viewModel.statusProperty());
        loadingOverlay.visibleProperty().bind(loadAction.busyProperty()
                .or(detailAction.busyProperty()).or(createAction.busyProperty())
                .or(operationAction.busyProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        actionBox.disableProperty().bind(operationAction.busyProperty());
        createPanelController.configure(this::create, this::cancelCreate);
        subscription = useCases.observe(this::runtimeEvent);
        showPanel(emptyPanel);
        configureActions(null);
    }

    void configure(Runnable closeAction) {
        this.closeAction = closeAction == null ? () -> { } : closeAction;
    }

    void prepare() { requestSnapshot(); }

    void openCreate(String description) {
        createPanelController.prepare(description);
        showPanel(createPanel);
        configureActions(null);
    }

    void showTask(String taskId) {
        requestSnapshot(taskId);
    }

    @FXML private void createRequested() { openCreate(null); }
    @FXML private void closeRequested() { closeAction.run(); }
    @FXML private void startRequested() { operate("start", task -> { useCases.start(task.id(), now()); return null; }); }
    @FXML private void pauseRequested() { operate("pause", task -> { useCases.pause(task.id()); return null; }); }
    @FXML private void cancelRequested() { operate("cancel", task -> { useCases.cancel(task.id()); return null; }); }
    @FXML private void resumeRequested() { operate("resume", task -> { useCases.resume(task.id(), now()); return null; }); }
    @FXML private void rerunRequested() { operate("rerun", task -> { useCases.resume(task.id(), now()); return null; }); }
    @FXML private void deleteRequested() {
        operate("delete", task -> { useCases.delete(task.id()); return Deleted.INSTANCE; });
    }
    @FXML private void budgetRequested() { editBudget(false); }
    @FXML private void resumeBudgetRequested() { editBudget(true); }

    private void select(Task task) {
        if (task == null) return;
        boolean switched = !task.id().equals(selectedId);
        selectedId = task.id();
        selectedProgress = task.progress();
        viewModel.select(task);
        taskList.getSelectionModel().select(task);
        if (switched) detailPanelController.clearLogs();
        requestDetail(task);
    }

    private void requestDetail(Task task) {
        detailAction.execute(TaskSpec.io("sdd-detail-" + task.id()),
                context -> new Detail(useCases.require(task.id()), useCases.specification(task.id())),
                detail -> {
                    if (!detail.task().id().equals(selectedId)) return;
                    currentChange = detail.change().orElse(null);
                    showDetail(detail.task());
                }, failure -> failed("加载任务详情失败", failure));
    }

    private void showDetail(Task task) {
        viewModel.select(task);
        detailPanelController.show(task, currentChange);
        showPanel(detailPanel);
        configureActions(task);
    }

    private void create(SddTaskCreateController.Draft draft) {
        createPanelController.setBusy(true);
        createAction.execute(TaskSpec.io("sdd-create"), context -> {
            String title = draft.title().isBlank()
                    ? useCases.generateTitle(draft.description()) : draft.title();
            String stamp = now();
            Task task = useCases.create(new SddTaskApplicationService.CreateCommand(
                    title, draft.description(), draft.capabilities(), draft.workDir(),
                    draft.tokenBudget(), draft.notificationChannel(), stamp));
            useCases.start(task.id(), stamp);
            return task;
        }, task -> {
            createPanelController.setBusy(false);
            viewModel.showStatus("已创建并启动任务「" + task.title() + "」");
            requestSnapshot(task.id());
        }, failure -> {
            createPanelController.setBusy(false);
            failed("创建托管任务失败", failure);
        });
    }

    private void cancelCreate() {
        Task selected = viewModel.selectedProperty().get();
        if (selected == null) showPanel(emptyPanel);
        else showDetail(selected);
    }

    private void operate(String name, Operation operation) {
        Task selected = viewModel.selectedProperty().get();
        if (selected == null) return;
        operationAction.execute(TaskSpec.io("sdd-" + name + "-" + selected.id()),
                context -> operation.run(selected), result -> {
                    if (result == Deleted.INSTANCE) {
                        selectedId = "";
                        currentChange = null;
                        viewModel.clearSelection();
                        detailPanelController.clear();
                        showPanel(emptyPanel);
                    }
                    requestSnapshot();
                }, failure -> failed("任务操作失败", failure));
    }

    private void editBudget(boolean resumeAfterSave) {
        Task selected = viewModel.selectedProperty().get();
        if (selected == null) return;
        var result = budgets.show(root.getScene().getWindow(), selected);
        if (result.isEmpty() || result.getAsLong() == selected.tokenBudget()) return;
        operate("budget", task -> {
            Task updated = useCases.updateTokenBudget(task.id(), result.getAsLong());
            if (resumeAfterSave) useCases.resume(task.id(), now());
            return updated;
        });
    }

    private void requestSnapshot() { requestSnapshot(null); }

    private void requestSnapshot(String selectId) {
        if (closed.get()) return;
        loadAction.execute(TaskSpec.io("sdd-snapshot"), context -> useCases.snapshot(), snapshot -> {
            viewModel.apply(snapshot);
            String wanted = selectId == null ? selectedId : selectId;
            Task selected = snapshot.tasks().stream()
                    .filter(task -> task.id().equals(wanted)).findFirst().orElse(null);
            if (selected == null) {
                if (viewModel.selectedProperty().get() == null) showPanel(emptyPanel);
                configureActions(null);
            } else select(selected);
        }, failure -> failed("加载托管任务失败", failure));
    }

    private void runtimeEvent(Event event) {
        fx.dispatch(() -> {
            if (closed.get()) return;
            if (event instanceof Event.Log log) {
                if (log.taskId().equals(selectedId)) detailPanelController.appendLog(log.message());
                return;
            }
            Task task = ((Event.Changed) event).task();
            Snapshot snapshot = merge(task);
            viewModel.apply(snapshot);
            if (!task.id().equals(selectedId)) return;
            boolean progressChanged = task.progress() != selectedProgress;
            selectedProgress = task.progress();
            viewModel.select(task);
            if (progressChanged) requestDetail(task);
            else showDetail(task);
        });
    }

    private Snapshot merge(Task changed) {
        var items = new java.util.ArrayList<>(viewModel.tasks());
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(changed.id())) { index = i; break; }
        }
        if (index < 0) items.add(changed); else items.set(index, changed);
        return new Snapshot(items);
    }

    private void configureActions(Task task) {
        for (Button button : actionButtons()) visible(button, false);
        if (task == null) return;
        switch (task.state()) {
            case PENDING -> show(startButton, deleteButton);
            case RUNNING -> show(budgetButton, pauseButton, cancelButton);
            case NEEDS_HUMAN -> show(resumeBudgetButton, resumeButton, deleteButton);
            case PAUSED -> show(resumeButton, budgetButton, cancelButton, deleteButton);
            case COMPLETED, FAILED, CANCELLED -> show(rerunButton, deleteButton);
        }
    }

    private java.util.List<Button> actionButtons() {
        return java.util.List.of(startButton, pauseButton, cancelButton, resumeButton,
                budgetButton, resumeBudgetButton, rerunButton, deleteButton);
    }

    private void show(Button... buttons) {
        for (Button button : buttons) visible(button, true);
    }

    private void showPanel(Node panel) {
        for (Node node : java.util.List.of(emptyPanel, detailPanel, createPanel)) {
            visible(node, node == panel);
        }
    }

    private void failed(String operation, Throwable failure) {
        String message = failure.getMessage();
        viewModel.showStatus(operation + "：" + (message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message));
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    private static String now() { return LocalDateTime.now().format(TS); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loadAction.close();
        detailAction.close();
        createAction.close();
        operationAction.close();
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception failure) {
                log.debug("关闭 SDD 页面事件订阅失败", failure);
            }
            subscription = null;
        }
        if (taskList != null) {
            taskList.setCellFactory(null);
            taskList.setItems(null);
        }
        if (listSubtitle != null) listSubtitle.textProperty().unbind();
        if (statusLabel != null) statusLabel.textProperty().unbind();
        if (loadingOverlay != null) {
            loadingOverlay.visibleProperty().unbind();
            loadingOverlay.managedProperty().unbind();
        }
        if (actionBox != null) actionBox.disableProperty().unbind();
        closeAction = () -> { };
    }

    private record Detail(Task task, Optional<OpenSpecChange> change) {}
    private enum Deleted { INSTANCE }
    @FunctionalInterface private interface Operation { Object run(Task task); }
}
