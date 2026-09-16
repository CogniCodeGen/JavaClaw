package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionTrust;

/** 发行版明确审核的内置目录升级链；冻结描述不得随当前 Bundle 的权限工厂变化。 */
public final class BuiltinCatalogMigrations {
    private static final Set<String> SITE_BROWSER_V6_TOOLS = Set.of(
            "site_search",
            "site_snapshot",
            "site_accounts",
            "site_read",
            "site_list",
            "browser_open",
            "browser_act",
            "browser_screenshot",
            "browser_tabs",
            "browser_fill_account");
    private static final ExtensionDescriptor SITE_V6 =
            siteDescriptor("6.0.0", 2, siteCeiling(2, SITE_BROWSER_V6_TOOLS, ToolRisk.EXTERNAL_EFFECT));
    private static final List<ExtensionDescriptor> SITE_PREDECESSORS = List.of(
            siteDescriptor("5.0.0", 1, siteCeiling(1, Set.of("site_search", "site_snapshot"), ToolRisk.NETWORK)),
            siteDescriptor("5.0.0", 1, siteCeiling(1, SITE_BROWSER_V6_TOOLS, ToolRisk.EXTERNAL_EFFECT)));
    private static final ExtensionDescriptor CODING_V2 = codingDescriptor(2);
    private static final List<ExtensionDescriptor> CODING_PREDECESSORS = List.of(codingDescriptor(1));

    private BuiltinCatalogMigrations() {}

    /**
     * 返回发行版允许迁入指定完整描述的已知前驱。
     *
     * <p>只接受本发行版审核的 Site 与 Coding 完整描述。历史权限在此独立固定，不能随当前权限工厂变化。 调用方仍须核验数据库行身份与完整前驱相等，原子提升版本并保留启停状态及用户授权。
     *
     * @param target 当前发行版的完整非空描述
     * @return 不可变的已审核前驱列表；没有对应升级链时返回空列表
     */
    public static List<ExtensionDescriptor> predecessors(ExtensionDescriptor target) {
        Objects.requireNonNull(target, "target");
        if (SITE_V6.equals(target)) {
            return SITE_PREDECESSORS;
        }
        return CODING_V2.equals(target) ? CODING_PREDECESSORS : List.of();
    }

    private static ExtensionDescriptor codingDescriptor(long revision) {
        PermissionProfile ceiling = new PermissionProfile(
                "com.javaclaw.coding.ceiling",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(64L * 1024 * 1024, 4L * 1024 * 1024, 1, 16));
        return new ExtensionDescriptor(
                new ExtensionId("com.javaclaw.coding"),
                "Coding",
                "6.0.0",
                revision,
                Set.of(ContributionKind.QUERY, ContributionKind.COMMAND, ContributionKind.TOOL),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, ceiling));
    }

    private static ExtensionDescriptor siteDescriptor(String version, long revision, PermissionProfile ceiling) {
        return new ExtensionDescriptor(
                new ExtensionId("com.javaclaw.site"),
                "站点",
                version,
                revision,
                Set.of(
                        ContributionKind.QUERY,
                        ContributionKind.COMMAND,
                        ContributionKind.VIEW,
                        ContributionKind.TOOL,
                        ContributionKind.SERVICE),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, ceiling));
    }

    private static PermissionProfile siteCeiling(long revision, Set<String> tools, ToolRisk risk) {
        return new PermissionProfile(
                "com.javaclaw.site.ceiling",
                revision,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(NetworkPermission.ANY_PORT), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(tools, risk, ApprovalRequirement.NONE),
                new ResourceLimits(256L * 1024 * 1024, 4L * 1024 * 1024, 1, 32));
    }
}
