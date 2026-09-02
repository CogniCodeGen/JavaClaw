package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.Workspace;

/**
 * Schedule 无人值守 Tool Grant 页的不可变状态。
 *
 * @param phase 异步阶段
 * @param workspaces Workspace 目录
 * @param workspace 当前 Workspace
 * @param grants 授权及独立使用余额
 * @param selected 当前授权
 * @param draft 新授权草稿
 * @param decisions 脱敏权限决策
 * @param message 状态说明
 * @param epoch 请求代次
 */
public record UnattendedToolGrantSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> workspace,
        List<UnattendedToolGrantStatus> grants,
        Optional<UnattendedToolGrantStatus> selected,
        UnattendedToolGrantForm draft,
        List<PermissionDecisionTrace> decisions,
        String message,
        long epoch) {
    /** 复制集合并校验状态。 */
    public UnattendedToolGrantSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        workspace = Objects.requireNonNull(workspace, "workspace");
        grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
        selected = Objects.requireNonNull(selected, "selected");
        draft = Objects.requireNonNull(draft, "draft");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 空初始状态 */
    public static UnattendedToolGrantSettingsState initial() {
        return new UnattendedToolGrantSettingsState(
                SettingsLoadState.INITIAL,
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                UnattendedToolGrantForm.empty(),
                List.of(),
                "",
                0);
    }

    /** @return 是否存在未提交草稿 */
    public boolean dirty() {
        return draft.dirty();
    }
}
