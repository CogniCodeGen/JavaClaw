package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.WorkspaceId;

/**
 * 工具目录选择器的不可变异步状态。
 *
 * @param phase 异步阶段
 * @param workspaceId 固定 Workspace
 * @param permissionProfile 精确权限版本
 * @param agentProfile 可选精确 Agent Profile
 * @param result 权威目录版本和当前有界切片
 * @param query 当前查询词
 * @param message 用户可读状态
 * @param epoch 请求代次
 */
public record ToolCatalogSelectionState(
        SettingsLoadState phase,
        Optional<WorkspaceId> workspaceId,
        Optional<PermissionProfileRef> permissionProfile,
        Optional<AgentProfileRef> agentProfile,
        Optional<ToolCatalogQueryResult> result,
        String query,
        String message,
        long epoch) {
    /** 校验状态完整性。 */
    public ToolCatalogSelectionState {
        phase = Objects.requireNonNull(phase, "phase");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        permissionProfile = Objects.requireNonNull(permissionProfile, "permissionProfile");
        agentProfile = Objects.requireNonNull(agentProfile, "agentProfile");
        result = Objects.requireNonNull(result, "result");
        query = Objects.requireNonNullElse(query, "");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0 || workspaceId.isPresent() != permissionProfile.isPresent()) {
            throw new IllegalArgumentException("工具目录状态的作用域或 epoch 不合法");
        }
        if (agentProfile.isPresent() && permissionProfile.isEmpty()) {
            throw new IllegalArgumentException("Agent Profile 查询必须同时绑定 PermissionProfile");
        }
    }

    /** @return 尚未绑定任何 Workspace 的初始状态 */
    public static ToolCatalogSelectionState initial() {
        return new ToolCatalogSelectionState(
                SettingsLoadState.INITIAL,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "",
                "",
                0);
    }

    /** @return 目录已读取且可以安全选择 */
    public boolean ready() {
        return phase == SettingsLoadState.READY && result.isPresent();
    }
}
