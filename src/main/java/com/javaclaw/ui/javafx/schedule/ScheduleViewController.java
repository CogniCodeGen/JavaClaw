package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.schedule.ScheduleApplicationService.Event;
import com.javaclaw.application.schedule.ScheduleApplicationService.OperationResult;
import com.javaclaw.application.schedule.ScheduleApplicationService.Snapshot;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 定时任务窗口 Controller：协调事件、应用服务和异步生命周期。 */
public final class ScheduleViewController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ScheduleViewController.class);

    @FXML private HBox root;
    @FXML private Label listSubtitle;
    @FXML private ListView<Task> taskList;
    @FXML private VBox emptyPanel;
    @FXML private ScrollPane detailScroll;
    @FXML private ScheduleDetailController detailsController;
    @FXML private Button runButton;
    @FXML private Label statusLabel;
    @FXML private Button saveButton;
    @FXML private StackPane loadingOverlay;

    private final ScheduleApplicationService useCases;
    private final DialogService dialogs;
    private final FxDispatcher fx;
    private final ScheduleTaskCellFactory cells;
    private final ScheduleViewModel viewModel = new ScheduleViewModel();
    private final UiAsyncAction<Snapshot> loadAction;
    private final UiAsyncAction<OperationResult> operationAction;
    private final UiAsyncAction<String> decisionAction;
    private final Timeline statusTimer = new Timeline();
    private final AtomicBoolean closed = new AtomicBoolean();
    private AutoCloseable subscription;
    private Runnable closeAction = () -> { };

    public ScheduleViewController(
            ScheduleApplicationService useCases,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            ScheduleTaskCellFactory cells) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.cells = Objects.requireNonNull(cells, "cells");
        loadAction = new UiAsyncAction<>(tasks, fx);
        operationAction = new UiAsyncAction<>(tasks, fx);
        decisionAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        taskList.setItems(viewModel.tasks());
        taskList.setCellFactory(ignored -> cells.create(this::selectionRequested));
        listSubtitle.textProperty().bind(viewModel.subtitleProperty());
        statusLabel.textProperty().bind(viewModel.statusProperty());
        loadingOverlay.visibleProperty().bind(loadAction.busyProperty()
                .or(operationAction.busyProperty()).or(decisionAction.busyProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        saveButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !canSave(), viewModel.selectedProperty(), operationAction.busyProperty()));
        runButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !canRun(), viewModel.selectedProperty(), operationAction.busyProperty()));
        detailsController.configure(this::toggleRequested, this::deleteRequested,
                () -> viewModel.showStatus("有尚未保存的修改"));
        statusTimer.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), event -> {
            taskList.refresh();
            detailsController.refreshClock();
        }));
        statusTimer.setCycleCount(Timeline.INDEFINITE);
        subscription = useCases.observe(this::runtimeEvent);
        requestSnapshot();
    }

    void configure(Runnable closeAction) {
        this.closeAction = closeAction == null ? () -> { } : closeAction;
    }

    ScheduleDetailController details() { return detailsController; }

    void prepare() {
        requestSnapshot();
        statusTimer.playFromStart();
    }

    @FXML
    private void createRequested() {
        resolveDraft(() -> {
            Task draft = useCases.createDraft("新定时任务");
            viewModel.beginDraft(draft);
            showSelection(draft, true);
            viewModel.showStatus("已创建，请填写触发规则与提示词后点「保存」");
            detailsController.requestNameFocus();
        });
    }

    private void selectionRequested(Task task) {
        Task selected = viewModel.selectedProperty().get();
        if (selected != null && selected.id().equals(task.id())) return;
        resolveDraft(() -> {
            viewModel.select(task);
            showSelection(task, false);
        });
    }

    @FXML
    void saveRequested() {
        saveCurrent(null);
    }

    private void saveCurrent(Runnable continuation) {
        Task selected = viewModel.selectedProperty().get();
        if (selected == null || selected.builtin()) return;
        boolean wasDraft = viewModel.selectedIsDraft();
        operationAction.execute(TaskSpec.io("schedule-save-" + selected.id()),
                context -> useCases.save(detailsController.command()), result -> {
                    if (wasDraft) viewModel.finishDraft();
                    apply(result);
                    if (continuation != null) continuation.run();
                }, failure -> failed("保存定时任务失败", failure));
    }

    private void toggleRequested(boolean enabled) {
        Task selected = viewModel.selectedProperty().get();
        if (selected == null || selected.builtin()) return;
        if (viewModel.selectedIsDraft()) {
            viewModel.showStatus("草稿将在首次保存后" + (enabled ? "启用" : "保持暂停"));
            return;
        }
        operationAction.execute(TaskSpec.io("schedule-toggle-" + selected.id()),
                context -> useCases.setEnabled(detailsController.command(), enabled),
                this::apply, failure -> {
                    failed("状态更新失败", failure);
                    requestSnapshot();
                });
    }

    @FXML
    private void runRequested() {
        Task selected = viewModel.selectedProperty().get();
        if (selected == null) return;
        if (viewModel.selectedIsDraft()) {
            saveCurrent(this::runRequested);
            return;
        }
        if (selected.active()) {
            viewModel.showStatus("任务已在运行或排队");
            return;
        }
        operationAction.execute(TaskSpec.io("schedule-run-" + selected.id()), context -> {
            if (!selected.builtin() && !selected.enabled()) {
                ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                        "运行已暂停的任务", "单次执行",
                        "定时任务「" + selected.name()
                                + "」已暂停。本次只执行一次，不会重新启用，是否继续？",
                        ConfirmKind.CONFIRM, 60, "", false));
                if (!decision.isAllow()) return null;
            }
            return useCases.runNow(selected.id(), !selected.enabled());
        }, result -> {
            if (result == null) {
                viewModel.showStatus("已取消立即运行");
                return;
            }
            apply(result);
        }, failure -> failed("立即运行失败", failure));
    }

    private void deleteRequested() {
        Task selected = viewModel.selectedProperty().get();
        if (selected == null || selected.builtin()) return;
        if (viewModel.selectedIsDraft()) {
            discardDraft();
            viewModel.showStatus("未保存草稿已丢弃");
            return;
        }
        operationAction.execute(TaskSpec.io("schedule-delete-" + selected.id()), context -> {
            ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                    "删除定时任务", "确认删除",
                    "确定要删除定时任务「" + selected.name() + "」吗？",
                    ConfirmKind.CONFIRM, 60, "", false));
            return decision.isAllow() ? useCases.delete(selected.id()) : null;
        }, result -> {
            if (result == null) return;
            viewModel.clearSelection();
            apply(result);
        }, failure -> failed("删除定时任务失败", failure));
    }

    @FXML
    void requestClose() {
        resolveDraft(closeAction);
    }

    private void resolveDraft(Runnable continuation) {
        if (!viewModel.hasDraft()) {
            continuation.run();
            return;
        }
        Task draft = viewModel.draftProperty().get();
        String draftName = viewModel.selectedIsDraft()
                ? detailsController.command().name().strip() : draft.name();
        if (draftName.isBlank()) draftName = draft.name();
        String displayName = draftName;
        decisionAction.execute(TaskSpec.io("schedule-draft-decision"), context -> dialogs.choose(
                new ChoiceRequest("未保存的定时任务",
                        "定时任务「" + displayName + "」尚未保存。请选择如何继续。",
                        List.of(new ChoiceOption("save", "保存并继续", "保存当前草稿"),
                                new ChoiceOption("discard", "放弃", "丢弃当前草稿"),
                                new ChoiceOption("cancel", "取消", "返回继续编辑")), 60)),
                decision -> {
                    if ("save".equals(decision)) saveCurrent(continuation);
                    else if ("discard".equals(decision)) {
                        discardDraft();
                        continuation.run();
                    }
                }, failure -> failed("处理未保存草稿失败", failure));
    }

    private void discardDraft() {
        viewModel.finishDraft();
        viewModel.clearSelection();
        taskList.getSelectionModel().clearSelection();
        showEmpty();
    }

    private void requestSnapshot() {
        if (closed.get()) return;
        loadAction.execute(TaskSpec.io("schedule-snapshot"), context -> useCases.snapshot(),
                this::apply, failure -> failed("加载定时任务失败", failure));
    }

    private void apply(OperationResult result) {
        apply(result.snapshot());
        viewModel.showStatus(result.message());
    }

    private void apply(Snapshot snapshot) {
        if (closed.get()) return;
        viewModel.apply(snapshot);
        Task selected = viewModel.selectedProperty().get();
        if (selected == null) showEmpty();
        else showSelection(selected, viewModel.selectedIsDraft());
    }

    private void showSelection(Task task, boolean draft) {
        emptyPanel.setVisible(false);
        emptyPanel.setManaged(false);
        detailScroll.setVisible(true);
        detailScroll.setManaged(true);
        detailsController.show(task, draft);
        if (!draft) taskList.getSelectionModel().select(task);
    }

    private void showEmpty() {
        detailsController.clear();
        detailScroll.setVisible(false);
        detailScroll.setManaged(false);
        emptyPanel.setVisible(true);
        emptyPanel.setManaged(true);
    }

    private boolean canSave() {
        Task task = viewModel.selectedProperty().get();
        return task != null && !task.builtin() && !operationAction.busyProperty().get();
    }

    private boolean canRun() {
        Task task = viewModel.selectedProperty().get();
        return task != null && !task.active() && !operationAction.busyProperty().get()
                && (!task.builtin() || task.manuallyRunnable());
    }

    private void runtimeEvent(Event event) {
        fx.dispatch(() -> {
            if (closed.get()) return;
            if (event.kind() == ScheduleApplicationService.EventKind.LOG) {
                viewModel.showStatus(event.message());
            } else {
                requestSnapshot();
            }
        });
    }

    private void failed(String operation, Throwable failure) {
        String message = failure.getMessage();
        viewModel.showStatus(operation + "：" + (message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        statusTimer.stop();
        loadAction.close();
        operationAction.close();
        decisionAction.close();
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception closeFailure) {
                log.debug("关闭定时任务事件订阅失败", closeFailure);
            }
            subscription = null;
        }
        taskList.setCellFactory(null);
        taskList.setItems(null);
        listSubtitle.textProperty().unbind();
        statusLabel.textProperty().unbind();
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
        saveButton.disableProperty().unbind();
        runButton.disableProperty().unbind();
        closeAction = () -> { };
    }
}
