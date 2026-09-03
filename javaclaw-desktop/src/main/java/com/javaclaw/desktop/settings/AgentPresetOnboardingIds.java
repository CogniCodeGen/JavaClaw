package com.javaclaw.desktop.settings;

import java.util.Objects;

import com.javaclaw.api.WorkspaceId;

/**
 * 首次智能体初始化在一个 Workspace 中使用的确定性资源标识。
 *
 * @param defaultProfile 默认智能体 Profile 标识
 * @param workerProfile Worker Profile 标识
 * @param explorerProfile Explorer Profile 标识
 * @param reviewPermission 只读审阅权限标识
 * @param developerPermission 开发权限标识
 */
public record AgentPresetOnboardingIds(
        String defaultProfile,
        String workerProfile,
        String explorerProfile,
        String reviewPermission,
        String developerPermission) {
    /** 校验所有标识都属于同一份已规范化快照。 */
    public AgentPresetOnboardingIds {
        defaultProfile = requireText(defaultProfile, "defaultProfile");
        workerProfile = requireText(workerProfile, "workerProfile");
        explorerProfile = requireText(explorerProfile, "explorerProfile");
        reviewPermission = requireText(reviewPermission, "reviewPermission");
        developerPermission = requireText(developerPermission, "developerPermission");
    }

    /**
     * 从 Workspace UUID 生成跨重试稳定且不会跨 Workspace 冲突的标识。
     *
     * @param workspaceId 固定 Workspace
     * @return 确定性标识集合
     */
    public static AgentPresetOnboardingIds forWorkspace(WorkspaceId workspaceId) {
        String prefix = "workspace-" + Objects.requireNonNull(workspaceId, "workspaceId");
        return new AgentPresetOnboardingIds(
                prefix + ".agent.default",
                prefix + ".agent.worker",
                prefix + ".agent.explorer",
                prefix + ".permission.review",
                prefix + ".permission.developer");
    }

    /**
     * 返回指定内置 Profile 预设对应的确定性标识。
     *
     * @param presetId default、worker 或 explorer
     * @return Profile 标识
     */
    public String profileId(String presetId) {
        return switch (requireText(presetId, "presetId")) {
            case "default" -> defaultProfile;
            case "worker" -> workerProfile;
            case "explorer" -> explorerProfile;
            default -> throw new IllegalArgumentException("不支持的智能体预设: " + presetId);
        };
    }

    /**
     * 返回指定权限预设对应的确定性标识。
     *
     * @param presetId workspace-review 或 workspace-developer
     * @return PermissionProfile 标识
     */
    public String permissionId(String presetId) {
        return switch (requireText(presetId, "presetId")) {
            case "workspace-review" -> reviewPermission;
            case "workspace-developer" -> developerPermission;
            default -> throw new IllegalArgumentException("不支持的权限预设: " + presetId);
        };
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }
}
