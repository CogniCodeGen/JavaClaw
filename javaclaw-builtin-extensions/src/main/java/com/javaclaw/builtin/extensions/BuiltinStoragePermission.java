package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

/** 仅使用托管存储的内置扩展权限上限。 */
final class BuiltinStoragePermission {
    private BuiltinStoragePermission() {}

    static PermissionProfile create(String extensionId) {
        return new PermissionProfile(
                extensionId + ".ceiling",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(64L * 1024 * 1024, 4L * 1024 * 1024, 1, 16));
    }
}
