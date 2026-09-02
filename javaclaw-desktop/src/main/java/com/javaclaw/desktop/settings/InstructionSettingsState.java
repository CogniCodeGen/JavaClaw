package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceInstructionSettings;

/**
 * 项目约定页面状态。
 *
 * @param phase 加载阶段
 * @param workspaces Workspace 目录
 * @param workspace 当前 Workspace
 * @param worktrees 当前 Workspace 未清理的受管 Worktree
 * @param worktree 当前 execution root；为空表示 Workspace 根
 * @param settings Workspace fallback 设置
 * @param fallbackDraft fallback basename 草稿；空白表示关闭
 * @param resolution 脱敏解析结果
 * @param feedback 状态消息与请求 epoch
 */
public record InstructionSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> workspace,
        List<ManagedWorktree> worktrees,
        Optional<ManagedWorktree> worktree,
        Optional<WorkspaceInstructionSettings> settings,
        String fallbackDraft,
        Optional<InstructionResolution> resolution,
        Feedback feedback) {
    /** 复制集合并校验完整快照。 */
    public InstructionSettingsState {
        Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(workspaces);
        workspace = Objects.requireNonNull(workspace, "workspace");
        worktrees = List.copyOf(worktrees);
        worktree = Objects.requireNonNull(worktree, "worktree");
        settings = Objects.requireNonNull(settings, "settings");
        fallbackDraft = Objects.requireNonNullElse(fallbackDraft, "");
        resolution = Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(feedback, "feedback");
    }

    /** @return 初始加载状态 */
    public static InstructionSettingsState initial() {
        return new InstructionSettingsState(
                SettingsLoadState.LOADING,
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                "",
                Optional.empty(),
                new Feedback("", 0));
    }

    /** @return fallback 草稿是否不同于当前权威值 */
    public boolean dirty() {
        String persisted =
                settings.flatMap(WorkspaceInstructionSettings::fallbackBasename).orElse("");
        return !persisted.equals(fallbackDraft.strip());
    }

    /**
     * @param message 用户可读状态
     * @param epoch 最新请求序号
     */
    public record Feedback(String message, long epoch) {
        /** 规范化消息并校验 epoch。 */
        public Feedback {
            message = Objects.requireNonNullElse(message, "");
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must not be negative");
            }
        }
    }
}
