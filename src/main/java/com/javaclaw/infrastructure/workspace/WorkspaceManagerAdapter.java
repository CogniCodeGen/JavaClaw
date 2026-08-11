package com.javaclaw.infrastructure.workspace;

import com.javaclaw.application.workspace.WorkspaceApplicationService.WorkspaceSummary;
import com.javaclaw.application.workspace.WorkspaceManagementPort;
import com.javaclaw.config.Workspace;
import com.javaclaw.config.WorkspaceManager;

import java.util.List;
import java.util.Objects;

/** Adapts the durable legacy workspace store to the application workspace port. */
public final class WorkspaceManagerAdapter implements WorkspaceManagementPort {

    private final WorkspaceManager manager;

    public WorkspaceManagerAdapter(WorkspaceManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public List<WorkspaceSummary> list() {
        return manager.getWorkspaces().stream().map(WorkspaceManagerAdapter::summary).toList();
    }

    @Override
    public String currentWorkspaceId() {
        return manager.getCurrentWorkspaceId();
    }

    @Override
    public WorkspaceSummary create(String name) {
        return summary(manager.createWorkspace(name));
    }

    @Override
    public boolean delete(String workspaceId) {
        return manager.deleteWorkspace(workspaceId);
    }

    private static WorkspaceSummary summary(Workspace workspace) {
        return new WorkspaceSummary(
                workspace.getId(), workspace.getName(), workspace.getCreatedAt());
    }
}
