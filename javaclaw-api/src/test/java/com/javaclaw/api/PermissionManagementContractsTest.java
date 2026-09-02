package com.javaclaw.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PermissionManagementContractsTest {
    @Test
    void 差异契约只允许同一配置并复制分区() {
        PermissionProfile first = profile("developer", 1, Set.of("read"));
        PermissionProfile second = profile("developer", 2, Set.of("read", "write"));
        PermissionProfileDiff diff = new PermissionProfileDiff(first, second, Set.of(PermissionSection.TOOL));

        assertEquals(Set.of(PermissionSection.TOOL), diff.changedSections());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionProfileDiff(first, profile("other", 2, Set.of()), Set.of()));
        assertThrows(NullPointerException.class, () -> new PermissionProfileDiff(null, second, Set.of()));
    }

    @Test
    void 有效权限预览要求五层顺序和最终结果一致() {
        PermissionProfile effective = profile("effective", 1, Set.of("read"));
        List<PermissionLayerResult> layers = java.util.Arrays.stream(PermissionLayerKind.values())
                .map(layer -> new PermissionLayerResult(
                        layer,
                        Optional.empty(),
                        true,
                        effective,
                        layer == PermissionLayerKind.PROFILE ? List.of("工具范围被收窄") : List.of()))
                .toList();
        EffectivePermissionPreview preview =
                new EffectivePermissionPreview(effective, layers, List.of("PROFILE: 工具范围被收窄"));

        assertEquals(5, preview.layers().size());
        assertEquals(5, PermissionLayerKind.values().length);
        assertEquals(5, PermissionSection.values().length);
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectivePermissionPreview(effective, layers.reversed(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectivePermissionPreview(profile("effective", 2, Set.of()), layers, List.of()));
    }

    @Test
    void 未应用层不能携带拒绝原因且原因必须有界() {
        PermissionProfile effective = profile("effective", 1, Set.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionLayerResult(
                        PermissionLayerKind.TURN_GRANT, Optional.empty(), false, effective, List.of("不应拒绝")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionLayerResult(
                        PermissionLayerKind.PROFILE,
                        Optional.of(new PermissionProfileRef("profile", 1)),
                        true,
                        effective,
                        List.of("x".repeat(501))));
    }

    private static PermissionProfile profile(String id, long version, Set<String> tools) {
        return new PermissionProfile(
                id,
                version,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(tools, ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(1_024, 1_024, 1, 1));
    }
}
