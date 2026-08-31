package com.javaclaw.agent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;

/** Workspace commands and queries exposed by the runtime application boundary. */
public interface WorkspaceUseCases {
    /** 登记并规范化工作区根目录，返回持久 Workspace；幂等键可为空，不创建用户项目文件。 */
    Workspace createWorkspace(String name, Path root, String idempotencyKey);

    /** 按标识读取工作区；不存在时返回 Optional.empty，不扫描用户目录内容。 */
    Optional<Workspace> readWorkspace(WorkspaceId id);

    /** 返回已登记的 Workspace 列表，不从文件系统推测新工作区。 */
    List<Workspace> listWorkspaces();

    /** 按 expectedRevision 更新工作区名称，不改变根目录；不携带幂等键。 */
    default Workspace updateWorkspace(WorkspaceId id, String name, long expectedRevision) {
        return updateWorkspace(id, name, expectedRevision, null);
    }

    /** 按 expectedRevision 更新名称；相同幂等键可重放，版本冲突拒绝覆盖。 */
    Workspace updateWorkspace(WorkspaceId id, String name, long expectedRevision, String idempotencyKey);

    /** 按版本删除工作区登记；不删除用户项目目录，不携带幂等键。 */
    default void deleteWorkspace(WorkspaceId id, long expectedRevision) {
        deleteWorkspace(id, expectedRevision, null);
    }

    /** 按版本删除工作区登记并支持幂等；仍有关联 Thread 时必须先处理关联，不删除项目目录。 */
    void deleteWorkspace(WorkspaceId id, long expectedRevision, String idempotencyKey);
}
