package com.javaclaw.desktop.settings;

import java.util.Objects;

import com.javaclaw.api.Workspace;

/**
 * 首次智能体初始化 Dialog 的完整不可变状态。
 *
 * @param phase 当前异步阶段
 * @param workspace 打开向导时冻结的 Workspace
 * @param catalog 最近一次服务端权威目录
 * @param selection 用户的精确模型和工具选择
 * @param permissions 权限预览、确认与候选
 * @param message 当前结论或可执行提示
 * @param epoch 请求代次；旧响应不得覆盖新状态
 */
public record AgentPresetOnboardingState(
        AgentPresetOnboardingPhase phase,
        Workspace workspace,
        AgentPresetOnboardingCatalog catalog,
        AgentPresetOnboardingSelection selection,
        AgentPresetPermissionSetup permissions,
        String message,
        long epoch) {
    /** 校验状态快照。 */
    public AgentPresetOnboardingState {
        phase = Objects.requireNonNull(phase, "phase");
        workspace = Objects.requireNonNull(workspace, "workspace");
        catalog = Objects.requireNonNull(catalog, "catalog");
        selection = Objects.requireNonNull(selection, "selection");
        permissions = Objects.requireNonNull(permissions, "permissions");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /**
     * 创建固定 Workspace 的初始状态。
     *
     * @param workspace 打开向导时的 Workspace 快照
     * @return 初始状态
     */
    public static AgentPresetOnboardingState initial(Workspace workspace) {
        return new AgentPresetOnboardingState(
                AgentPresetOnboardingPhase.INITIAL,
                workspace,
                AgentPresetOnboardingCatalog.empty(),
                AgentPresetOnboardingSelection.empty(),
                AgentPresetPermissionSetup.empty(),
                "",
                0);
    }

    /** @return 是否正在读取或写入 */
    public boolean pending() {
        return phase == AgentPresetOnboardingPhase.LOADING || phase == AgentPresetOnboardingPhase.APPLYING;
    }

    /** @return 初始化闭环是否已经由权威状态确认完成 */
    public boolean completed() {
        return phase == AgentPresetOnboardingPhase.COMPLETED;
    }

    /** @return 是否因确定性标识冲突而停止 */
    public boolean conflict() {
        return phase == AgentPresetOnboardingPhase.CONFLICT;
    }
}
