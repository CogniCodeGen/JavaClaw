package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.Workspace;

/**
 * Workspace 登记表单快照，执行配置由独立面板维护。
 *
 * @param phase 读取或写入阶段
 * @param workspaces 当前作用域目录
 * @param selected 当前 Workspace，可为空
 * @param draftName 名称草稿
 * @param message 状态信息
 * @param epoch 请求代次
 */
public record WorkspaceSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> selected,
        String draftName,
        String message,
        long epoch) {
    /** 复制目录并校验状态。 */
    public WorkspaceSettingsState {
        Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(workspaces);
        selected = Objects.requireNonNull(selected, "selected");
        draftName = Objects.requireNonNullElse(draftName, "");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 空初始快照 */
    public static WorkspaceSettingsState initial() {
        return new WorkspaceSettingsState(SettingsLoadState.INITIAL, List.of(), Optional.empty(), "", "", 0);
    }

    /** @return 名称是否有未保存修改 */
    public boolean nameDirty() {
        return selected.map(workspace -> !workspace.name().equals(draftName.strip()))
                .orElse(false);
    }

    /** @return 登记表单是否有未保存修改 */
    public boolean dirty() {
        return nameDirty();
    }
}
