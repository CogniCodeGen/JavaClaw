package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.Workspace;

/**
 * Workspace 管理页的不可变状态。
 *
 * @param phase 异步阶段
 * @param workspaces Workspace 目录
 * @param profiles 可用于新绑定的活动 Agent Profile
 * @param selected 当前 Workspace
 * @param binding 当前 Workspace 直接绑定
 * @param draftName 名称草稿
 * @param draftProfile 默认 Profile 草稿
 * @param message 状态说明
 * @param epoch 请求代次
 */
public record WorkspaceSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        List<AgentProfile> profiles,
        Optional<Workspace> selected,
        Optional<ProfileBinding> binding,
        String draftName,
        Optional<AgentProfile> draftProfile,
        String message,
        long epoch) {
    /** 复制集合并校验草稿。 */
    public WorkspaceSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        selected = Objects.requireNonNull(selected, "selected");
        binding = Objects.requireNonNull(binding, "binding");
        draftName = Objects.requireNonNullElse(draftName, "");
        draftProfile = Objects.requireNonNull(draftProfile, "draftProfile");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 空初始状态 */
    public static WorkspaceSettingsState initial() {
        return new WorkspaceSettingsState(
                SettingsLoadState.INITIAL,
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                "",
                Optional.empty(),
                "",
                0);
    }

    /** @return Workspace 名称草稿是否改变 */
    public boolean nameDirty() {
        return selected.map(workspace -> !workspace.name().equals(draftName.strip()))
                .orElse(false);
    }

    /** @return 默认 Profile 草稿是否改变 */
    public boolean profileDirty() {
        if (draftProfile.isEmpty()) {
            return false;
        }
        Optional<com.javaclaw.api.AgentProfileRef> current = binding.map(ProfileBinding::profile);
        Optional<com.javaclaw.api.AgentProfileRef> draft =
                draftProfile.map(profile -> new com.javaclaw.api.AgentProfileRef(profile.id(), profile.revision()));
        return !current.equals(draft);
    }

    /** @return 页面是否有任意未保存草稿 */
    public boolean dirty() {
        return nameDirty() || profileDirty();
    }
}
