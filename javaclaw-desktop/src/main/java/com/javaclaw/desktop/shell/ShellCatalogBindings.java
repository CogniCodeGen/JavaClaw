package com.javaclaw.desktop.shell;

import java.util.List;
import java.util.Objects;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;

import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.ThreadState;

/** 只在目录或选择变化时更新原生列表；由主壳在屏蔽选择意图的渲染区间调用。 */
final class ShellCatalogBindings {
    private final ComboBox<Workspace> workspaces;
    private final ListView<ConversationThread> threads;
    private final ListView<ApprovalRecord> approvals;
    private ThreadState previous;
    private List<ApprovalRecord> previousApprovals;

    ShellCatalogBindings(
            ComboBox<Workspace> workspaces, ListView<ConversationThread> threads, ListView<ApprovalRecord> approvals) {
        this.workspaces = workspaces;
        this.threads = threads;
        this.approvals = approvals;
    }

    void render(DesktopState state) {
        ThreadState next = state.threads();
        boolean workspaceChanged = previous == null || !previous.workspaces().equals(next.workspaces());
        if (workspaceChanged) {
            workspaces.getItems().setAll(next.workspaces());
        }
        if (workspaceChanged
                || !Objects.equals(
                        workspaces.getValue(), next.selectedWorkspace().orElse(null))) {
            workspaces.setValue(next.selectedWorkspace().orElse(null));
        }
        boolean threadsChanged = previous == null || !previous.threads().equals(next.threads());
        if (threadsChanged) {
            threads.getItems().setAll(next.threads());
        }
        if (threadsChanged
                || !Objects.equals(
                        threads.getSelectionModel().getSelectedItem(),
                        next.selectedThread().orElse(null))) {
            threads.getSelectionModel().select(next.selectedThread().orElse(null));
        }
        previous = next;
        List<ApprovalRecord> pending = state.interaction().pendingApprovals();
        if (!pending.equals(previousApprovals)) {
            ApprovalRecord selected = approvals.getSelectionModel().getSelectedItem();
            approvals.getItems().setAll(pending);
            if (selected != null) {
                pending.stream()
                        .filter(value ->
                                value.request().id().equals(selected.request().id()))
                        .findFirst()
                        .ifPresent(value -> approvals.getSelectionModel().select(value));
            }
            previousApprovals = pending;
        }
    }
}
