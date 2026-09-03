package com.javaclaw.api;

import java.util.Objects;

/**
 * PermissionProfile 预设实例化结果。
 *
 * @param preset 实际使用的精确预设
 * @param workspaceId 固定 Workspace
 * @param profile 已持久化的普通 PermissionProfile
 */
public record PermissionPresetInstantiationResult(
        PermissionPresetDescriptor preset, WorkspaceId workspaceId, PermissionProfile profile) {
    /** 校验结果归属。 */
    public PermissionPresetInstantiationResult {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(profile, "profile");
        if (profile.version() != 1) {
            throw new IllegalArgumentException("instantiated profile must use revision 1");
        }
    }
}
