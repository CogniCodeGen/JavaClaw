package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.ProviderEndpoint;

/**
 * Agent Studio 不可变状态；内置角色仅可读取或 clone。
 *
 * @param phase 异步阶段
 * @param roles 权威角色目录
 * @param providers 用于可选模型锁定的 Provider 目录
 * @param selected 当前角色；新建时为空
 * @param creating 是否正在创建
 * @param baseline 原始草稿
 * @param draft 当前草稿
 * @param message 用户提示
 * @param revisionConflict 是否版本冲突
 * @param epoch 请求代次
 */
public record AgentRoleSettingsState(
        SettingsLoadState phase,
        List<AgentRole> roles,
        List<ProviderEndpoint> providers,
        Optional<AgentRole> selected,
        boolean creating,
        AgentRoleDraft baseline,
        AgentRoleDraft draft,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 复制集合并校验状态。 */
    public AgentRoleSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        roles = List.copyOf(roles);
        providers = List.copyOf(providers);
        selected = Objects.requireNonNull(selected, "selected");
        baseline = Objects.requireNonNull(baseline, "baseline");
        draft = Objects.requireNonNull(draft, "draft");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 初始状态 */
    public static AgentRoleSettingsState initial() {
        AgentRoleDraft empty = AgentRoleDraft.empty();
        return new AgentRoleSettingsState(
                SettingsLoadState.INITIAL, List.of(), List.of(), Optional.empty(), false, empty, empty, "", false, 0);
    }

    /** @return 是否存在未保存草稿 */
    public boolean dirty() {
        return creating || !baseline.equals(draft);
    }

    /** @return 是否正在读取或写入 */
    public boolean pending() {
        return phase == SettingsLoadState.LOADING || phase == SettingsLoadState.SAVING;
    }

    /** @return 角色是否内置或已归档 */
    public boolean readOnly() {
        return selected.map(role -> role.builtin() || role.lifecycle() == com.javaclaw.api.RoleLifecycle.ARCHIVED)
                .orElse(false);
    }
}
