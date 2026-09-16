package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionTrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinCatalogMigrationsTest {
    @Test
    void 站点目录和全部工具在权限扩展后发布新版本() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        try (SiteExtension bundle = new SiteExtension()) {
            ExtensionDescriptor descriptor = bundle.descriptor();
            assertEquals("6.0.0", descriptor.version());
            assertEquals(2, descriptor.revision());
            assertEquals(2, descriptor.requirements().permissionCeiling().version());
            var tools = support.start(bundle).contributions().stream()
                    .filter(ExtensionContributions.Tool.class::isInstance)
                    .map(ExtensionContributions.Tool.class::cast)
                    .toList();
            assertEquals(10, tools.size());
            assertTrue(
                    tools.stream().allMatch(tool -> tool.descriptor().identity().revision() == 2));
        }
    }

    @Test
    void 仅两份完整历史站点描述被列为新目录的前驱() {
        List<ExtensionDescriptor> previous = BuiltinCatalogMigrations.predecessors(new SiteExtension().descriptor());
        assertEquals(2, previous.size());
        for (ExtensionDescriptor descriptor : previous) {
            assertFrozenCommonDescriptor(descriptor);
        }
        var oldTools = previous.get(0).requirements().permissionCeiling().tools();
        assertEquals(Set.of("site_search", "site_snapshot"), oldTools.allowedTools());
        assertEquals(ToolRisk.NETWORK, oldTools.maximumRisk());
        var browserTools = previous.get(1).requirements().permissionCeiling().tools();
        assertEquals(
                Set.of(
                        "site_search",
                        "site_snapshot",
                        "site_accounts",
                        "site_read",
                        "site_list",
                        "browser_open",
                        "browser_act",
                        "browser_screenshot",
                        "browser_tabs",
                        "browser_fill_account"),
                browserTools.allowedTools());
        assertEquals(ToolRisk.EXTERNAL_EFFECT, browserTools.maximumRisk());
        assertThrows(UnsupportedOperationException.class, () -> previous.remove(0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> browserTools.allowedTools().add("unknown_tool"));
    }

    @Test
    void 目标身份版本与贡献点改变时不继承升级资格() {
        ExtensionDescriptor current = new SiteExtension().descriptor();
        List<ExtensionDescriptor> changed = List.of(
                new ExtensionDescriptor(
                        new ExtensionId("com.javaclaw.other"),
                        "站点",
                        "6.0.0",
                        2,
                        current.contributionKinds(),
                        current.requirements()),
                new ExtensionDescriptor(
                        current.id(), "其他站点", "6.0.0", 2, current.contributionKinds(), current.requirements()),
                new ExtensionDescriptor(
                        current.id(), "站点", "6.0.1", 2, current.contributionKinds(), current.requirements()),
                new ExtensionDescriptor(
                        current.id(), "站点", "6.0.0", 3, current.contributionKinds(), current.requirements()),
                new ExtensionDescriptor(
                        current.id(), "站点", "6.0.0", 2, Set.of(ContributionKind.TOOL), current.requirements()));
        assertTrue(changed.stream()
                .allMatch(value -> BuiltinCatalogMigrations.predecessors(value).isEmpty()));
        for (ExtensionDescriptor predecessor : BuiltinCatalogMigrations.predecessors(current)) {
            assertTrue(BuiltinCatalogMigrations.predecessors(predecessor).isEmpty());
        }
        assertThrows(NullPointerException.class, () -> BuiltinCatalogMigrations.predecessors(null));
    }

    @Test
    void 权限或运行约束改变时必须另行审核迁移目标() {
        ExtensionDescriptor current = new SiteExtension().descriptor();
        PermissionProfile ceiling = current.requirements().permissionCeiling();
        PermissionProfile changedNetwork = new PermissionProfile(
                ceiling.id(),
                ceiling.version(),
                ceiling.files(),
                new NetworkPermission(Set.of("different.example"), Set.of(443), true),
                ceiling.processes(),
                ceiling.tools(),
                ceiling.resources());
        List<ExtensionRequirements> requirements = List.of(
                new ExtensionRequirements(ExtensionTrust.THIRD_PARTY, ExtensionAvailability.OPTIONAL, 2, ceiling),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.REQUIRED, 2, ceiling),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 3, ceiling),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, changedNetwork));
        for (ExtensionRequirements changed : requirements) {
            ExtensionDescriptor target = new ExtensionDescriptor(
                    current.id(),
                    current.displayName(),
                    current.version(),
                    current.revision(),
                    current.contributionKinds(),
                    changed);
            assertTrue(BuiltinCatalogMigrations.predecessors(target).isEmpty());
        }
    }

    @Test
    void 其他内置目录不获得隐式升级且托管资源版本保持原样() {
        for (ExtensionBundle bundle : BuiltinExtensions.create()) {
            if (!Set.of(BuiltinExtensionIds.SITE, BuiltinExtensionIds.CODING)
                    .contains(bundle.descriptor().id().value())) {
                assertTrue(BuiltinCatalogMigrations.predecessors(bundle.descriptor())
                        .isEmpty());
            }
        }
        List<ExtensionBundle> managed = List.of(
                new PlanExtension(),
                new LoopExtension(),
                new WorkflowExtension(),
                new SddExtension(),
                new ScheduleExtension());
        for (ExtensionBundle bundle : managed) {
            assertEquals("5.0.0", bundle.descriptor().version());
            assertEquals(1, bundle.descriptor().revision());
        }
    }

    @Test
    void Coding升级只接受完整旧目录且不会扩展Bundle权限上限() {
        var current = new CodingExtension().descriptor();
        var predecessors = BuiltinCatalogMigrations.predecessors(current);
        assertEquals(2, current.revision());
        assertEquals(1, predecessors.size());
        var previous = predecessors.getFirst();
        assertEquals(1, previous.revision());
        assertEquals(current.requirements(), previous.requirements());
        assertTrue(current.requirements()
                .permissionCeiling()
                .processes()
                .executables()
                .isEmpty());
        var changed = new ExtensionDescriptor(
                current.id(),
                "其他名称",
                current.version(),
                current.revision(),
                current.contributionKinds(),
                current.requirements());
        assertTrue(BuiltinCatalogMigrations.predecessors(changed).isEmpty());
        assertTrue(BuiltinCatalogMigrations.predecessors(previous).isEmpty());
    }

    private static void assertFrozenCommonDescriptor(ExtensionDescriptor descriptor) {
        assertEquals(new ExtensionId("com.javaclaw.site"), descriptor.id());
        assertEquals("站点", descriptor.displayName());
        assertEquals("5.0.0", descriptor.version());
        assertEquals(1, descriptor.revision());
        assertEquals(
                Set.of(
                        ContributionKind.QUERY,
                        ContributionKind.COMMAND,
                        ContributionKind.VIEW,
                        ContributionKind.TOOL,
                        ContributionKind.SERVICE),
                descriptor.contributionKinds());
        assertEquals(ExtensionTrust.BUILT_IN, descriptor.requirements().trust());
        assertEquals(ExtensionAvailability.OPTIONAL, descriptor.requirements().availability());
        assertEquals(2, descriptor.requirements().minimumProtocolVersion());
        PermissionProfile ceiling = descriptor.requirements().permissionCeiling();
        assertEquals("com.javaclaw.site.ceiling", ceiling.id());
        assertEquals(1, ceiling.version());
        assertEquals(new FilePermission(List.of(), List.of(), false, false), ceiling.files());
        assertEquals(
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(NetworkPermission.ANY_PORT), true),
                ceiling.network());
        assertEquals(new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)), ceiling.processes());
        assertEquals(ApprovalRequirement.NONE, ceiling.tools().approvalRequirement());
        assertEquals(new ResourceLimits(268_435_456, 4_194_304, 1, 32), ceiling.resources());
    }
}
