package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingDataBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsExecutionRootsInsideOrContainingPlatformData() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        Path project = Files.createDirectory(temporary.resolve("project"));
        assertDoesNotThrow(() -> CodingDataBoundary.requireOutside(project, permission(project), data));
        for (Path bad : List.of(data, data.resolve("coding/caches"), temporary)) {
            assertThrows(
                    SecurityException.class, () -> CodingDataBoundary.requireOutside(bad, permission(project), data));
        }
        assertThrows(
                SecurityException.class, () -> CodingDataBoundary.requireOutside(project, permission(temporary), data));
    }

    @Test
    void onlyAuthoritativeManagedWorktreeIdentityCanNarrowlyAccessItsOwnRoot() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        Path worktree = Files.createDirectories(data.resolve("worktrees/owned"));
        assertThrows(
                SecurityException.class, () -> CodingDataBoundary.requireOutside(worktree, permission(worktree), data));
        assertDoesNotThrow(() -> CodingDataBoundary.requireOutside(worktree, permission(worktree), data, true));
        assertThrows(
                SecurityException.class,
                () -> CodingDataBoundary.requireOutside(worktree, permission(data.resolve("worktrees")), data, true));
        assertThrows(
                SecurityException.class,
                () -> CodingDataBoundary.requireOutside(worktree, permission(data.resolve("coding")), data, true));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void canonicalizesSymlinkAliasesBeforeCheckingTheManagedBoundary() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        Path alias = Files.createSymbolicLink(temporary.resolve("project-alias"), data);
        Path project = Files.createDirectory(temporary.resolve("project"));
        assertThrows(
                SecurityException.class, () -> CodingDataBoundary.requireOutside(alias, permission(project), data));
        assertThrows(
                SecurityException.class,
                () -> CodingDataBoundary.requireOutside(project, permission(alias.resolve("not-created")), data));
    }

    private static PermissionProfile permission(Path root) {
        return new PermissionProfile(
                "boundary-test",
                1,
                new FilePermission(List.of(root), List.of(root), true, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of("java"), false, Duration.ofSeconds(5)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 4096, 4, 128));
    }
}
