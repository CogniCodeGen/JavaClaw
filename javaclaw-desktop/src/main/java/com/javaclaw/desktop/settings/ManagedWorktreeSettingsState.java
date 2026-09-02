package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.Workspace;

/**
 * Worktree 恢复中心的不可变状态。
 *
 * @param phase 异步阶段
 * @param workspaces Workspace 目录
 * @param workspace 当前 Workspace
 * @param includeCleaned 是否显示已清理历史
 * @param worktrees 受管 Worktree 最新快照
 * @param selected 当前 Worktree
 * @param artifact 最近生成的 Patch 或 Backup Attachment
 * @param message 状态说明
 * @param epoch 请求代次
 */
public record ManagedWorktreeSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> workspace,
        boolean includeCleaned,
        List<ManagedWorktree> worktrees,
        Optional<ManagedWorktree> selected,
        Optional<ManagedWorktreeArtifact> artifact,
        String message,
        long epoch) {
    /** 复制集合并校验状态。 */
    public ManagedWorktreeSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        workspace = Objects.requireNonNull(workspace, "workspace");
        worktrees = List.copyOf(Objects.requireNonNull(worktrees, "worktrees"));
        selected = Objects.requireNonNull(selected, "selected");
        artifact = Objects.requireNonNull(artifact, "artifact");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 空初始状态 */
    public static ManagedWorktreeSettingsState initial() {
        return new ManagedWorktreeSettingsState(
                SettingsLoadState.INITIAL,
                List.of(),
                Optional.empty(),
                false,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                "",
                0);
    }
}
