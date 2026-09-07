package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionContractsTest {
    @Test
    void permissionRecordsNormalizeInputsAndRejectInvalidLimits() {
        FilePermission files =
                new FilePermission(List.of(Path.of("."), Path.of(".").toAbsolutePath()), List.of(), true, false);
        NetworkPermission network = new NetworkPermission(Set.of("EXAMPLE.COM"), Set.of(443), true);
        ProcessPermission processes = new ProcessPermission(Set.of(" java "), true, Duration.ofSeconds(2));
        ToolPermission tools =
                new ToolPermission(Set.of(" read "), ToolRisk.WORKSPACE_WRITE, ApprovalRequirement.RISKY);
        ResourceLimits resources = new ResourceLimits(10, 20, 1, 2);
        PermissionProfile profile = new PermissionProfile(" default ", 1, files, network, processes, tools, resources);

        assertEquals(1, files.readRoots().size());
        assertTrue(network.allowsHost("example.com"));
        assertFalse(network.allowsHost("other.example"));
        assertEquals(Set.of("java"), processes.executables());
        assertEquals(Set.of("read"), tools.allowedTools());
        assertEquals("default", profile.id());
        NetworkPermission ceiling =
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(NetworkPermission.ANY_PORT), true);
        assertTrue(ceiling.allowsPort(8_443));
        assertThrows(IllegalArgumentException.class, () -> ceiling.allowsPort(0));
        assertThrows(IllegalArgumentException.class, () -> new NetworkPermission(Set.of("x"), Set.of(-1), false));
        assertThrows(IllegalArgumentException.class, () -> new NetworkPermission(Set.of("x"), Set.of(65_536), false));
        assertThrows(IllegalArgumentException.class, () -> new ProcessPermission(Set.of(), false, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> new ProcessPermission(Set.of(), false, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ProcessPermission(Set.of(), false, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolPermission(Set.of(), null, ApprovalRequirement.EVERY_CALL));
        assertThrows(IllegalArgumentException.class, () -> new ToolPermission(Set.of(), ToolRisk.READ_ONLY, null));
        assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(1, 1, 1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionProfile("x", 1, null, network, processes, tools, resources));
    }

    @Test
    void resolverReturnsOnlyCapabilitiesAllowedByEveryProfile() {
        PermissionProfile broad = ApiFixtures.profile("broad", 1);
        PermissionProfile narrow = new PermissionProfile(
                "narrow",
                3,
                new FilePermission(
                        List.of(Path.of("/workspace/project"), Path.of("/private")),
                        List.of(Path.of("/workspace/project")),
                        false,
                        false),
                new NetworkPermission(Set.of("api.example.com"), Set.of(443), true),
                new ProcessPermission(Set.of("java"), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of("read"), ToolRisk.READ_ONLY, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512, 1_024, 2, 32));
        PermissionProfile effective = PermissionResolver.intersect(List.of(broad, narrow));

        assertEquals("effective", effective.id());
        assertEquals(3, effective.version());
        assertEquals(List.of(Path.of("/workspace/project")), effective.files().readRoots());
        assertEquals(Set.of("api.example.com"), effective.network().hosts());
        assertEquals(Set.of(443), effective.network().ports());
        assertTrue(effective.network().tlsOnly());
        assertEquals(Set.of("java"), effective.processes().executables());
        assertFalse(effective.processes().allowPty());
        assertEquals(Duration.ofSeconds(30), effective.processes().maxRunTime());
        assertEquals(ToolRisk.READ_ONLY, effective.tools().maximumRisk());
        assertEquals(ApprovalRequirement.EVERY_CALL, effective.tools().approvalRequirement());
        assertEquals(512, effective.resources().memoryBytes());
        assertEquals(512, effective.resources().outputBytes());
        assertEquals(2, effective.resources().childProcesses());
        assertEquals(16, effective.resources().openFiles());
    }

    @Test
    void resolverHandlesReverseWildcardsAndDisjointSets() {
        PermissionProfile left = new PermissionProfile(
                "left",
                1,
                new FilePermission(List.of(Path.of("/a/deep")), List.of(Path.of("/a/deep")), false, false),
                new NetworkPermission(Set.of("one.example"), Set.of(80), false),
                new ProcessPermission(Set.of("one"), false, Duration.ofSeconds(1)),
                new ToolPermission(Set.of("one"), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.NONE),
                new ResourceLimits(1, 1, 1, 1));
        PermissionProfile right = new PermissionProfile(
                "right",
                1,
                new FilePermission(List.of(Path.of("/a")), List.of(Path.of("/other")), false, false),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(443), false),
                new ProcessPermission(Set.of("two"), false, Duration.ofSeconds(2)),
                new ToolPermission(Set.of("two"), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.NONE),
                new ResourceLimits(2, 2, 2, 2));
        PermissionProfile effective = PermissionResolver.intersect(List.of(left, right));

        assertEquals(List.of(Path.of("/a/deep")), effective.files().readRoots());
        assertTrue(effective.files().writeRoots().isEmpty());
        assertEquals(Set.of("one.example"), effective.network().hosts());
        assertTrue(effective.network().ports().isEmpty());
        assertTrue(effective.processes().executables().isEmpty());
        assertTrue(effective.tools().allowedTools().isEmpty());
        assertEquals(Duration.ofSeconds(1), effective.processes().maxRunTime());
        assertThrows(IllegalArgumentException.class, () -> PermissionResolver.intersect(null));
        assertThrows(IllegalArgumentException.class, () -> PermissionResolver.intersect(List.of()));
    }

    @Test
    void resolverNarrowsSystemPortCeilingToExplicitCallerPorts() {
        PermissionProfile ceiling = ApiFixtures.profile("ceiling", 1);
        ceiling = new PermissionProfile(
                ceiling.id(),
                ceiling.version(),
                ceiling.files(),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(NetworkPermission.ANY_PORT), true),
                ceiling.processes(),
                ceiling.tools(),
                ceiling.resources());
        PermissionProfile caller = ApiFixtures.profile("caller", 1);

        PermissionProfile effective = PermissionResolver.intersect(List.of(ceiling, caller));

        assertEquals(caller.network().ports(), effective.network().ports());
        assertFalse(effective.network().ports().contains(NetworkPermission.ANY_PORT));
    }

    @Test
    void turnBudgetEnforcesPlatformBounds() {
        assertEquals(4, new TurnBudget(1, 1, 1, 4, Duration.ofSeconds(1)).childThreads());
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(0, 1, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 0, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 1, -1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 1, 1, -1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 1, 1, 5, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new TurnBudget(1, 1, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 1, 1, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TurnBudget(1, 1, 1, 0, Duration.ofSeconds(-1)));
    }
}
