package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.WorktreeRpcContracts;

/** 父子 Thread 受管 Worktree 恢复中心的强类型 facade。 */
public final class WorktreeClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public WorktreeClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出 Workspace 的受管 Worktree。
     *
     * @param workspaceId Workspace
     * @param includeCleaned 是否包含已清理记录
     * @return 稳定排序的快照
     */
    public List<ManagedWorktree> list(WorkspaceId workspaceId, boolean includeCleaned) {
        return connection
                .query(
                        "worktree/list",
                        new WorktreeRpcContracts.ListPayload(workspaceId, includeCleaned),
                        WorktreeRpcContracts.ListResult.class)
                .worktrees();
    }

    /**
     * 读取一个受管 Worktree。
     *
     * @param worktreeId Worktree
     * @return 当前快照
     */
    public ManagedWorktree read(WorktreeId worktreeId) {
        return connection.query(
                "worktree/read", new WorktreeRpcContracts.ReadPayload(worktreeId), ManagedWorktree.class);
    }

    /**
     * 中断绑定的子 Thread。
     *
     * @param worktreeId Worktree
     * @param reason 脱敏原因
     * @param options 幂等键与 Worktree expected revision
     * @return 已中断快照
     */
    public ManagedWorktree interrupt(WorktreeId worktreeId, String reason, CommandOptions options) {
        return connection.command(
                "worktree/interrupt",
                new WorktreeRpcContracts.InterruptPayload(worktreeId, reason),
                options,
                ManagedWorktree.class);
    }

    /**
     * 导出有界 Git Patch Attachment，不修改真实 index。
     *
     * @param worktreeId Worktree
     * @param options 幂等键与 Worktree expected revision
     * @return Patch Artifact
     */
    public ManagedWorktreeArtifact exportPatch(WorktreeId worktreeId, CommandOptions options) {
        return artifact("worktree/patch/export", worktreeId, options);
    }

    /**
     * 创建 cleanup 前必须具备的可验证 Backup Attachment。
     *
     * @param worktreeId Worktree
     * @param options 幂等键与 Worktree expected revision
     * @return Backup Artifact
     */
    public ManagedWorktreeArtifact backup(WorktreeId worktreeId, CommandOptions options) {
        return artifact("worktree/backup", worktreeId, options);
    }

    /**
     * 永久清理已备份且非活动的 Worktree。
     *
     * @param worktreeId Worktree
     * @param confirmation 固定危险确认文本
     * @param options 幂等键与 Worktree expected revision
     * @return CLEANED 快照
     */
    public ManagedWorktree cleanup(WorktreeId worktreeId, String confirmation, CommandOptions options) {
        return connection.command(
                "worktree/cleanup",
                new WorktreeRpcContracts.CleanupPayload(worktreeId, confirmation),
                options,
                ManagedWorktree.class);
    }

    private ManagedWorktreeArtifact artifact(String method, WorktreeId worktreeId, CommandOptions options) {
        return connection.command(
                method, new WorktreeRpcContracts.ArtifactPayload(worktreeId), options, ManagedWorktreeArtifact.class);
    }
}
