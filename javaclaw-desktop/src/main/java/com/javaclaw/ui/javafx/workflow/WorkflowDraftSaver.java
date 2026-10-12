package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.animation.PauseTransition;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** 保存当前草稿并隔离切换后的迟到结果；显式后续操作等待最新一版草稿落盘。 */
final class WorkflowDraftSaver {
    private final WorkflowViewModel viewModel;
    private final UiAsyncAction<Void> action;
    private final PauseTransition autosave;
    private final com.javaclaw.application.workflow.WorkflowApplicationService useCases;
    private boolean awaitingSave;

    WorkflowDraftSaver(WorkflowViewModel viewModel, UiAsyncAction<Void> action, PauseTransition autosave,
                       com.javaclaw.application.workflow.WorkflowApplicationService useCases) {
        this.viewModel = viewModel;
        this.action = action;
        this.autosave = autosave;
        this.useCases = useCases;
    }

    void save(Runnable success, Consumer<Throwable> failureHandler) {
        if (awaitingSave) {
            if (success != null) failureHandler.accept(new IllegalStateException("正在保存工作流，请稍后重试"));
            return;
        }
        continueSaving(success, failureHandler);
    }

    private void continueSaving(Runnable success, Consumer<Throwable> failureHandler) {
        if (viewModel.editor() == null || viewModel.readOnlyProperty().get()) {
            awaitingSave = false;
            if (success != null) success.run();
            return;
        }
        var graph = viewModel.currentGraph();
        awaitingSave = success != null;
        action.execute(TaskSpec.io("workflow-save-" + graph.id()), context -> {
            useCases.saveDraft(graph);
            return null;
        }, ignored -> {
            var current = viewModel.currentGraph();
            if (current != graph) {
                if (success != null && current != null && current.id().equals(graph.id())) {
                    autosave.stop();
                    continueSaving(success, failureHandler);
                } else if (success != null) {
                    awaitingSave = false;
                    failureHandler.accept(new CancellationException("当前工作流已切换，原操作已取消"));
                }
                return;
            }
            awaitingSave = false;
            viewModel.markSaved();
            if (success != null) success.run();
        }, failure -> {
            awaitingSave = false;
            var current = viewModel.currentGraph();
            if (current != null && current.id().equals(graph.id()) && viewModel.dirtyProperty().get())
                viewModel.markSaveFailed();
            failureHandler.accept(failure);
        });
    }
}
