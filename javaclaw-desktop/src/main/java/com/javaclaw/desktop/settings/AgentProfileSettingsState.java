package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderEndpoint;

/**
 * Agent Profile 页面的不可变状态。
 *
 * @param phase 异步阶段
 * @param profiles 最新 Profile 目录
 * @param providers 最新 Provider 目录
 * @param permissions 最新权限目录
 * @param selected 当前权威 Profile
 * @param baseline 草稿比较基线
 * @param draft 当前草稿
 * @param message 状态或错误说明
 * @param revisionConflict 是否发生 revision 冲突
 * @param epoch 请求代次
 */
public record AgentProfileSettingsState(
        SettingsLoadState phase,
        List<AgentProfile> profiles,
        List<ProviderEndpoint> providers,
        List<PermissionProfile> permissions,
        Optional<AgentProfile> selected,
        AgentProfileDraft baseline,
        AgentProfileDraft draft,
        String message,
        boolean revisionConflict,
        long epoch) {
    /** 复制目录并校验状态。 */
    public AgentProfileSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        profiles = List.copyOf(profiles);
        providers = List.copyOf(providers);
        permissions = List.copyOf(permissions);
        selected = Objects.requireNonNull(selected, "selected");
        baseline = Objects.requireNonNull(baseline, "baseline");
        draft = Objects.requireNonNull(draft, "draft");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 初始状态 */
    public static AgentProfileSettingsState initial() {
        AgentProfileDraft empty = AgentProfileDraft.empty();
        return new AgentProfileSettingsState(
                SettingsLoadState.INITIAL,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                empty,
                empty,
                "",
                false,
                0);
    }

    /** @return 草稿是否尚未保存 */
    public boolean dirty() {
        return !baseline.equals(draft);
    }

    /** @return 是否正在后台读取或写入 */
    public boolean pending() {
        return phase == SettingsLoadState.LOADING || phase == SettingsLoadState.SAVING;
    }
}
