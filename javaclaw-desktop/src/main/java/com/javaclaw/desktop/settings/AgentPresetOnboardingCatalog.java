package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfilePreset;
import com.javaclaw.api.PermissionPresetDescriptor;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProviderEndpoint;

/**
 * 首次智能体向导一次读取到的服务端权威目录。
 *
 * @param profilePresets 只读 Profile 预设
 * @param permissionPresets 只读权限预设
 * @param providers 最新 Provider 目录
 * @param availableProviderVersions 经精确读取和本地状态检查后当前可用的 Provider 版本
 * @param profiles 最新 Agent Profile 目录
 * @param permissions 最新 PermissionProfile 目录
 * @param workspaceBinding 固定 Workspace 的直接默认绑定
 */
public record AgentPresetOnboardingCatalog(
        List<AgentProfilePreset> profilePresets,
        List<PermissionPresetDescriptor> permissionPresets,
        List<ProviderEndpoint> providers,
        List<ProviderEndpoint> availableProviderVersions,
        List<AgentProfile> profiles,
        List<PermissionProfile> permissions,
        Optional<ProfileBinding> workspaceBinding) {
    /** 复制目录并校验可空语义。 */
    public AgentPresetOnboardingCatalog {
        profilePresets = List.copyOf(Objects.requireNonNull(profilePresets, "profilePresets"));
        permissionPresets = List.copyOf(Objects.requireNonNull(permissionPresets, "permissionPresets"));
        providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        availableProviderVersions =
                List.copyOf(Objects.requireNonNull(availableProviderVersions, "availableProviderVersions"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        workspaceBinding = Objects.requireNonNull(workspaceBinding, "workspaceBinding");
    }

    /** @return 尚未读取目录时使用的空快照 */
    public static AgentPresetOnboardingCatalog empty() {
        return new AgentPresetOnboardingCatalog(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Optional.empty());
    }
}
