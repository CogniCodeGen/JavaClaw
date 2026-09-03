package com.javaclaw.api;

import java.util.List;
import java.util.Objects;

/**
 * PermissionProfile 预设针对固定 Workspace 的服务端预览。
 *
 * @param preset 实际解析的精确预设
 * @param workspaceId 固定 Workspace
 * @param proposedProfile 尚未持久化的普通 PermissionProfile revision 1
 * @param warnings 实例化前需要用户确认的边界或收窄提示
 */
public record PermissionPresetPreview(
        PermissionPresetDescriptor preset,
        WorkspaceId workspaceId,
        PermissionProfile proposedProfile,
        List<String> warnings) {
    /** 复制警告并校验预览。 */
    public PermissionPresetPreview {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(proposedProfile, "proposedProfile");
        if (proposedProfile.version() != 1) {
            throw new IllegalArgumentException("proposed profile must use revision 1");
        }
        Objects.requireNonNull(warnings, "warnings");
        if (warnings.size() > 100) {
            throw new IllegalArgumentException("warnings must not exceed 100 entries");
        }
        warnings = warnings.stream()
                .map(warning -> Preconditions.boundedText(warning, "warning", 1_000))
                .toList();
    }
}
