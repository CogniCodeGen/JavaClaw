package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;

/**
 * Extension Job 目录过滤条件。
 *
 * @param workspaceId 可选 Workspace
 * @param extensionId 可选精确 Extension 标识
 * @param states 状态集合；空集合表示全部
 */
public record AutomationJobFilter(
        Optional<WorkspaceId> workspaceId, Optional<String> extensionId, Set<ExecutionState> states) {
    /** 复制集合并规范化可选文本。 */
    public AutomationJobFilter {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        extensionId = Objects.requireNonNull(extensionId, "extensionId")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        states = Set.copyOf(Objects.requireNonNull(states, "states"));
    }

    /** @return 不限制 Workspace、Extension 和状态的条件 */
    public static AutomationJobFilter all() {
        return new AutomationJobFilter(Optional.empty(), Optional.empty(), Set.of());
    }
}
