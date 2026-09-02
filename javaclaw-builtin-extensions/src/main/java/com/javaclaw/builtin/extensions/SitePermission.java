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
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;

/** Site Bundle 的静态权限上限；具体主机由 Turn 配置和 Site 文档再次求交。 */
final class SitePermission {
    private SitePermission() {}

    static PermissionProfile create() {
        return new PermissionProfile(
                BuiltinExtensionIds.SITE + ".ceiling",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(NetworkPermission.ANY_PORT), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of("site_search", "site_snapshot"), ToolRisk.NETWORK, ApprovalRequirement.NONE),
                new ResourceLimits(256L * 1024 * 1024, 4L * 1024 * 1024, 1, 32));
    }
}
