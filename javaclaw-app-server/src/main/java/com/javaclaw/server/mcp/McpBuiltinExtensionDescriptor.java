package com.javaclaw.server.mcp;

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
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionTrust;

/** 把平台 MCP Host 声明为可选内置能力，使其与 Bundle 共用实时目录治理。 */
public final class McpBuiltinExtensionDescriptor {
    /** MCP 平台描述 revision；只在协议或贡献内容变化时递增。 */
    public static final long REVISION = 1;

    private McpBuiltinExtensionDescriptor() {}

    /**
     * 创建不可变 MCP 平台描述。
     *
     * <p>动态 Endpoint Tool 仍由 Workspace PermissionProfile、Catalog revision 与 Network Broker 收窄；此处只声明平台 Host 的最大能力边界。
     *
     * @return MCP 平台描述
     */
    public static ExtensionDescriptor create() {
        return new ExtensionDescriptor(
                new ExtensionId(BuiltinExtensionIds.MCP),
                "MCP",
                "5.0.0",
                REVISION,
                Set.of(ContributionKind.MCP, ContributionKind.TOOL, ContributionKind.COMMAND, ContributionKind.QUERY),
                new ExtensionRequirements(
                        ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, permissionCeiling()));
    }

    private static PermissionProfile permissionCeiling() {
        return new PermissionProfile(
                BuiltinExtensionIds.MCP + ".ceiling",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(NetworkPermission.ANY_PORT), true),
                new ProcessPermission(Set.of(), false, Duration.ofMinutes(2)),
                new ToolPermission(Set.of(), ToolRisk.NETWORK, ApprovalRequirement.RISKY),
                new ResourceLimits(256L * 1024 * 1024, 16L * 1024 * 1024, 1, 32));
    }
}
