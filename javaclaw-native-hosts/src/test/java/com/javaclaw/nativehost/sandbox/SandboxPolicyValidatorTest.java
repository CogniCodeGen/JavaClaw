package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxPolicyValidatorTest {
    @TempDir
    Path temporaryDirectory;

    private Path executable;

    @BeforeEach
    void resolveExecutable() throws Exception {
        executable = existing("/bin/echo", "/usr/bin/echo");
    }

    @Test
    void validationNormalizesExecutableEnvironmentAndTimeout() throws Exception {
        SandboxCommand command = command(
                executable,
                temporaryDirectory,
                Map.of("LANG", "C"),
                new byte[] {1},
                SandboxMode.BATCH,
                Duration.ofSeconds(10));

        ValidatedSandboxCommand validated = SandboxPolicyValidator.validate(
                command,
                permission(true, Duration.ofSeconds(2), List.of(temporaryDirectory), Set.of()),
                SandboxMode.BATCH);

        assertEquals(executable.toRealPath().toString(), validated.argv().getFirst());
        assertEquals(Duration.ofSeconds(2), validated.timeout());
        assertEquals(Map.of("LANG", "C"), validated.environment());
        assertEquals(temporaryDirectory.toRealPath(), validated.workingDirectory());
    }

    @Test
    void rejectsModeMismatchAndPtyWithoutPermission() {
        SandboxCommand batch = command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH);
        SandboxCommand pty = command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.PTY);

        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxPolicyValidator.validate(batch, permission(true), SandboxMode.PTY));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(pty, permission(false), SandboxMode.PTY));
    }

    @Test
    void rejectsRawNetworkAndOversizedStandardInput() {
        SandboxCommand command = command(executable, temporaryDirectory, Map.of(), new byte[128], SandboxMode.BATCH);

        assertThrows(
                UnsupportedOperationException.class,
                () -> SandboxPolicyValidator.validate(
                        command, permission(true, Set.of("example.com"), Set.of()), SandboxMode.BATCH));
        assertThrows(
                UnsupportedOperationException.class,
                () -> SandboxPolicyValidator.validate(
                        command, permission(true, Set.of(), Set.of(443)), SandboxMode.BATCH));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(command, permission(true, 64), SandboxMode.BATCH));
    }

    @Test
    void rejectsRelativeDirectoryAndDisallowedExecutables() throws Exception {
        SandboxCommand relative =
                command(Path.of("echo"), temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH);
        SandboxCommand directory =
                command(executable.getParent(), temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH);

        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(relative, permission(true), SandboxMode.BATCH));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(directory, permission(true), SandboxMode.BATCH));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(
                        command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH),
                        permission(true, Duration.ofSeconds(5), List.of(temporaryDirectory), Set.of("other")),
                        SandboxMode.BATCH));
    }

    @Test
    void rejectsInvalidRootsAndWorkingDirectory() throws Exception {
        Path fileRoot = Files.createFile(temporaryDirectory.resolve("not-a-directory"));
        SandboxCommand outside =
                command(executable, temporaryDirectory.getParent(), Map.of(), new byte[0], SandboxMode.BATCH);

        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(
                        command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH),
                        permission(true, Duration.ofSeconds(5), List.of(fileRoot), Set.of()),
                        SandboxMode.BATCH));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(outside, permission(true), SandboxMode.BATCH));
    }

    @Test
    void rejectsEnvironmentInjectionChannels() {
        for (String name : List.of("1INVALID", "CLASSPATH", "JAVA_TOOL_OPTIONS", "DYLD_INSERT_LIBRARIES")) {
            SandboxCommand command =
                    command(executable, temporaryDirectory, Map.of(name, "value"), new byte[0], SandboxMode.BATCH);
            assertThrows(
                    SecurityException.class,
                    () -> SandboxPolicyValidator.validate(command, permission(true), SandboxMode.BATCH));
        }
        SandboxCommand nul = command(
                executable, temporaryDirectory, Map.of("SAFE", "before\0after"), new byte[0], SandboxMode.BATCH);
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(nul, permission(true), SandboxMode.BATCH));
    }

    private SandboxCommand command(
            Path program, Path working, Map<String, String> environment, byte[] input, SandboxMode mode) {
        return command(program, working, environment, input, mode, Duration.ofSeconds(3));
    }

    private SandboxCommand command(
            Path program,
            Path working,
            Map<String, String> environment,
            byte[] input,
            SandboxMode mode,
            Duration timeout) {
        return new SandboxCommand(
                "validation", List.of(program.toString(), "hello"), working, environment, input, mode, timeout);
    }

    private PermissionProfile permission(boolean allowPty) {
        return permission(allowPty, Duration.ofSeconds(5), List.of(temporaryDirectory), Set.of());
    }

    private PermissionProfile permission(boolean allowPty, long outputBytes) {
        return permission(
                allowPty,
                Duration.ofSeconds(5),
                List.of(temporaryDirectory),
                Set.of(),
                Set.of(),
                Set.of(),
                outputBytes);
    }

    private PermissionProfile permission(boolean allowPty, Set<String> hosts, Set<Integer> ports) {
        return permission(allowPty, Duration.ofSeconds(5), List.of(temporaryDirectory), Set.of(), hosts, ports, 4096);
    }

    private PermissionProfile permission(
            boolean allowPty, Duration maxRunTime, List<Path> roots, Set<String> executables) {
        Set<String> allowed =
                executables.isEmpty() ? Set.of(executable.getFileName().toString()) : executables;
        return permission(allowPty, maxRunTime, roots, allowed, Set.of(), Set.of(), 4096);
    }

    private PermissionProfile permission(
            boolean allowPty,
            Duration maxRunTime,
            List<Path> roots,
            Set<String> executables,
            Set<String> hosts,
            Set<Integer> ports,
            long outputBytes) {
        return new PermissionProfile(
                "validation",
                1,
                new FilePermission(roots, List.of(), false, false),
                new NetworkPermission(hosts, ports, true),
                new ProcessPermission(executables, allowPty, maxRunTime),
                new ToolPermission(Set.of(), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(256L * 1024 * 1024, outputBytes, 2, 32));
    }

    private static Path existing(String... candidates) throws Exception {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return path.toRealPath();
            }
        }
        throw new IllegalStateException("test executable is unavailable");
    }
}
