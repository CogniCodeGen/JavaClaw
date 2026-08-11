package com.javaclaw.application.workspace;

import java.util.List;

/**
 * Workspace commands and queries exposed to presentation adapters.
 *
 * <p>Calls are synchronous and must run on the JavaFX thread or another caller-controlled serial
 * context. Creation and deletion are durable before returning. A workspace switch is deliberately
 * absent: {@code ApplicationKernel} owns that transactional runtime transition and rollback.</p>
 */
public interface WorkspaceApplicationService {

    List<WorkspaceSummary> list();

    String currentWorkspaceId();

    WorkspaceSummary create(String name);

    boolean delete(String workspaceId);

    record WorkspaceSummary(String id, String name, String createdAt) {
        public WorkspaceSummary {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("工作区 id 不能为空");
            name = name == null || name.isBlank() ? id : name;
            createdAt = createdAt == null ? "" : createdAt;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
