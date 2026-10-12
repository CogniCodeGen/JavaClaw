package com.javaclaw.application.workspace;

import com.javaclaw.application.workspace.WorkspaceApplicationService.WorkspaceSummary;

import java.util.List;

/** Persistence boundary for workspace metadata; implementations publish only durable results. */
public interface WorkspaceManagementPort {

    List<WorkspaceSummary> list();

    String currentWorkspaceId();

    WorkspaceSummary create(String name);

    boolean delete(String workspaceId);
}
