package com.javaclaw.agent.runtime.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;

/** Persistence port for canonical workspaces. */
public interface WorkspaceRepository {
    /** 登记并规范化工作区根目录，返回持久 Workspace；幂等键可为空，不创建用户项目文件。 */
    Workspace create(String name, Path root, String idempotencyKey);

    /** 按标识读取工作区；不存在时返回 Optional.empty，不扫描用户目录内容。 */
    Optional<Workspace> find(WorkspaceId id);

    /** 按规范化根目录查找已登记工作区，不存在时返回 Optional.empty。 */
    Optional<Workspace> findByRoot(Path root);

    /** 返回已登记的 Workspace 列表，不从文件系统推测新工作区。 */
    List<Workspace> list();

    /** 按 expectedRevision 更新工作区名称，不改变根目录；不携带幂等键。 */
    Workspace update(WorkspaceId id, String name, long expectedRevision);

    /** 按 expectedRevision 更新名称；相同幂等键可重放，版本冲突拒绝覆盖。 */
    default Workspace update(WorkspaceId id, String name, long expectedRevision, String idempotencyKey) {
        return update(id, name, expectedRevision);
    }

    /** 按版本保存安全锁状态与原因；只修改登记状态，不自行放宽 OS 权限。 */
    Workspace setLocked(WorkspaceId id, boolean locked, String reason, long expectedRevision);

    /** 按版本删除工作区登记；不删除用户项目目录，不携带幂等键。 */
    void delete(WorkspaceId id, long expectedRevision);

    /** 按版本删除工作区登记并支持幂等；仍有关联 Thread 时必须先处理关联，不删除项目目录。 */
    default void delete(WorkspaceId id, long expectedRevision, String idempotencyKey) {
        delete(id, expectedRevision);
    }
}
