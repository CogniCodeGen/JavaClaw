package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunObserver;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunTerminal;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunTerminalStatus;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.runtime.GraphRun;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 运行轨迹、历史、恢复与取消 Controller。 */
public final class WorkflowRuntimeController implements AutoCloseable {

    @FXML private StackPane dockBody;
    @FXML private TextArea console;
    @FXML private VBox historyPanel;
    @FXML private ListView<GraphRun> runHistory;
    @FXML private TextField resumeInput;
    @FXML private ProgressIndicator busyIndicator;

    private final WorkflowApplicationService useCases;
    private final FxDispatcher fx;
    private final WorkflowRunCellFactory cells;
    private final UiAsyncAction<List<GraphRun>> loadAction;
    private final UiAsyncAction<Void> runAction;
    private final UiAsyncAction<Boolean> cancelAction;
    private final AtomicBoolean closed = new AtomicBoolean();
    private WorkflowViewModel viewModel;
    private Consumer<String> logger = ignored -> { };

    public WorkflowRuntimeController(
            WorkflowApplicationService useCases,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx,
            WorkflowRunCellFactory cells) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.cells = Objects.requireNonNull(cells, "cells");
        loadAction = new UiAsyncAction<>(tasks, fx);
        runAction = new UiAsyncAction<>(tasks, fx);
        cancelAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        runHistory.setCellFactory(ignored -> cells.create());
        busyIndicator.visibleProperty().bind(loadAction.busyProperty()
                .or(runAction.busyProperty()).or(cancelAction.busyProperty()));
        busyIndicator.managedProperty().bind(busyIndicator.visibleProperty());
    }

    void configure(WorkflowViewModel viewModel, Consumer<String> logger) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.logger = Objects.requireNonNull(logger, "logger");
        runHistory.setItems(viewModel.runs());
        console.textProperty().bind(viewModel.consoleProperty());
    }

    void refresh() {
        if (closed.get() || viewModel == null) return;
        var selected = viewModel.selectedWorkflowProperty().get();
        if (selected == null) {
            viewModel.clearRuns();
            return;
        }
        loadAction.execute(TaskSpec.io("workflow-runs-" + selected.id()),
                context -> useCases.runs(selected.id()), viewModel::setRuns,
                failure -> logger.accept("读取运行历史失败：" + message(failure)));
    }

    @FXML private void showTrace() { show(console, historyPanel); }

    @FXML private void showHistory() { show(historyPanel, console); }

    @FXML private void resumeRequested() { resume(false); }

    @FXML private void retryRequested() { resume(true); }

    @FXML
    private void cancelRequested() {
        GraphRun run = runHistory.getSelectionModel().getSelectedItem();
        if (run == null) {
            logger.accept("请先选择运行记录");
            return;
        }
        cancelAction.execute(TaskSpec.io("workflow-cancel-" + run.id()),
                context -> useCases.cancelRun(run.id()), cancelled -> {
                    logger.accept(cancelled ? "已取消：" + run.id() : "运行已结束或不存在");
                    refresh();
                }, failure -> logger.accept("取消运行失败：" + message(failure)));
    }

    @FXML private void refreshRequested() { refresh(); }

    RunObserver observer() {
        return new RunObserver() {
            @Override
            public void onTrace(String trace) {
                fx.dispatch(() -> {
                    if (!closed.get()) logger.accept(trace);
                });
            }

            @Override
            public void onTerminal(RunTerminal terminal) {
                fx.dispatch(() -> {
                    if (closed.get()) return;
                    if (terminal.status() == RunTerminalStatus.COMPLETED) {
                        logger.accept("运行结束");
                    } else if (terminal.status() == RunTerminalStatus.CANCELLED) {
                        logger.accept("已取消：" + terminal.detail());
                    } else {
                        logger.accept("失败：" + terminal.detail());
                    }
                    refresh();
                });
            }
        };
    }

    private void resume(boolean confirmed) {
        GraphRun run = runHistory.getSelectionModel().getSelectedItem();
        if (run == null) {
            logger.accept("请先选择运行记录");
            return;
        }
        if (run.status().terminal()) {
            logger.accept("终态运行不可恢复：" + run.status());
            return;
        }
        String input = resumeInput.getText() == null ? "" : resumeInput.getText().strip();
        if (run.status() == RunStatus.WAITING_INPUT && input.isBlank()) {
            logger.accept("人工中断恢复必须填写输入");
            return;
        }
        runAction.execute(TaskSpec.io("workflow-resume-" + run.id()), context -> {
            useCases.resumeRun(run.id(), input, confirmed, observer());
            return null;
        }, ignored -> logger.accept("已提交恢复：" + run.id()),
                failure -> logger.accept("恢复失败：" + message(failure)));
    }

    private static void show(javafx.scene.Node shown, javafx.scene.Node hidden) {
        shown.setVisible(true);
        shown.setManaged(true);
        hidden.setVisible(false);
        hidden.setManaged(false);
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        console.textProperty().unbind();
        busyIndicator.visibleProperty().unbind();
        busyIndicator.managedProperty().unbind();
        loadAction.close();
        runAction.close();
        cancelAction.close();
    }
}
