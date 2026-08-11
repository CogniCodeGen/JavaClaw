package com.javaclaw.application.workspace;

import com.javaclaw.application.error.ValidationException;

import java.util.List;
import java.util.Objects;

/** Validates workspace commands and delegates durable state changes through the workspace port. */
public final class WorkspaceUseCase implements WorkspaceApplicationService {

    private final WorkspaceManagementPort workspaces;

    public WorkspaceUseCase(WorkspaceManagementPort workspaces) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @Override
    public List<WorkspaceSummary> list() {
        return List.copyOf(workspaces.list());
    }

    @Override
    public String currentWorkspaceId() {
        return workspaces.currentWorkspaceId();
    }

    @Override
    public WorkspaceSummary create(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) throw new ValidationException("工作区名称不能为空");
        return workspaces.create(normalized);
    }

    @Override
    public boolean delete(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ValidationException("工作区 id 不能为空");
        }
        return workspaces.delete(workspaceId);
    }
}
