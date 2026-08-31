package com.javaclaw.sandbox.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxPolicyTest {
    @Test
    void intersectionCanOnlyRemoveAuthority() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        SandboxPolicy broad = new SandboxPolicy(
                SandboxMode.WORKSPACE_WRITE,
                Set.of(root),
                Set.of(root.resolve("src")),
                Set.of(root.resolve(".git")),
                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                Set.of("PATH", "TOKEN"),
                Duration.ofMinutes(10),
                4096);
        SandboxPolicy narrow = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(root),
                Set.of(),
                Set.of(root.resolve(".git")),
                NetworkPolicy.disabled(),
                Set.of("PATH"),
                Duration.ofSeconds(30),
                1024);

        SandboxPolicy result = broad.intersect(narrow);

        assertEquals(SandboxMode.READ_ONLY, result.mode());
        assertEquals(Set.of(), result.writableRoots());
        assertEquals(NetworkPolicy.Mode.DISABLED, result.network().mode());
        assertEquals(Set.of("PATH"), result.inheritedEnvironment());
        assertEquals(Duration.ofSeconds(30), result.timeout());
        assertEquals(1024, result.outputLimitBytes());
    }

    @Test
    void protectedChildrenMayCarveExceptionsOutOfWritableRoots() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        new SandboxPolicy(
                SandboxMode.WORKSPACE_WRITE,
                Set.of(root),
                Set.of(root),
                Set.of(root.resolve(".git")),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(1),
                1);
    }

    @Test
    void writableRootCannotBeInsideAProtectedRoot() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxPolicy(
                        SandboxMode.WORKSPACE_WRITE,
                        Set.of(root),
                        Set.of(root.resolve(".git/hooks")),
                        Set.of(root.resolve(".git")),
                        NetworkPolicy.disabled(),
                        Set.of(),
                        Duration.ofSeconds(1),
                        1));
    }

    @Test
    void pathIntersectionKeepsTheNarrowerNestedRoot() {
        Path root = Path.of(".").toAbsolutePath().normalize();
        SandboxPolicy broad = SandboxPolicy.workspaceWrite(Set.of(root), Set.of(root), Set.of(root.resolve(".git")));
        SandboxPolicy narrow =
                SandboxPolicy.workspaceWrite(Set.of(root.resolve("src")), Set.of(root.resolve("src")), Set.of());

        SandboxPolicy result = broad.intersect(narrow);

        assertEquals(Set.of(root.resolve("src")), result.readableRoots());
        assertEquals(Set.of(root.resolve("src")), result.writableRoots());
        assertEquals(Set.of(root.resolve(".git")), result.protectedRoots());
    }

    @Test
    void policyRootsResolveSymlinkAncestors(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path target = Files.createDirectories(temporary.resolve("target"));
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("host does not permit symbolic links: " + unsupported.getMessage());
        }

        SandboxPolicy policy = SandboxPolicy.readOnly(Set.of(link.resolve("future")), Set.of());

        assertEquals(Set.of(target.toRealPath().resolve("future")), policy.readableRoots());
        assertTrue(policy.readableRoots().stream().noneMatch(path -> path.startsWith(link)));
    }
}
