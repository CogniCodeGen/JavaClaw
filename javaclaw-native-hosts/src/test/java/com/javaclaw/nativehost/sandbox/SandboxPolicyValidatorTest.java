package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
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
    void 平台独立stdin预算不扩大输出且旧入口仍按原限额拒绝() throws Exception {
        var request = command(executable, temporaryDirectory, Map.of(), new byte[128], SandboxMode.BATCH);
        var approved = permission(
                true,
                Duration.ofSeconds(5),
                List.of(temporaryDirectory),
                Set.of(executable.getFileName().toString()),
                Set.of(),
                Set.of(),
                64);
        var runtime = new SandboxRuntimeAccess(List.of(), List.of(), List.of(), 128);
        var validated = SandboxPolicyValidator.validate(
                request,
                approved,
                SandboxMode.BATCH,
                runtime,
                com.javaclaw.nativehost.network.SandboxNetworkAccess.offline());
        assertEquals(128, validated.standardInput().length);
        assertEquals(64, validated.limits().outputBytes());
        assertThrows(
                SecurityException.class, () -> SandboxPolicyValidator.validate(request, approved, SandboxMode.BATCH));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxRuntimeAccess(List.of(), List.of(), List.of(), 1_048_577));
    }

    @Test
    void 精确运行文件不扩张父目录且项目文件权限仍只接受目录() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("runtime-file"), "runtime");
        var request = command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH);
        var runtime = new SandboxRuntimeAccess(List.of(file), List.of(), List.of());
        var validated = SandboxPolicyValidator.validate(
                request,
                permission(true),
                SandboxMode.BATCH,
                runtime,
                com.javaclaw.nativehost.network.SandboxNetworkAccess.offline());
        org.junit.jupiter.api.Assertions.assertTrue(validated.readRoots().contains(file.toRealPath()));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(
                        request, permission(true, Duration.ofSeconds(5), List.of(file), Set.of()), SandboxMode.BATCH));
    }

    @Test
    void 运行时执行文件必须同时有读取范围且真正可执行() throws Exception {
        var request = command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH);
        assertThrows(
                SecurityException.class,
                () -> validateRuntime(request, new SandboxRuntimeAccess(List.of(), List.of(), List.of(executable))));
        Path data = Files.writeString(temporaryDirectory.resolve("data-only"), "not executable");
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rw-------"));
        assertThrows(
                SecurityException.class,
                () -> validateRuntime(request, new SandboxRuntimeAccess(List.of(data), List.of(), List.of(data))));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(
                        command(data, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH),
                        permission(true),
                        SandboxMode.BATCH));
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(
                        command(executable, data, Map.of(), new byte[0], SandboxMode.BATCH),
                        permission(true),
                        SandboxMode.BATCH));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 特殊设备不能作为精确运行文件或执行文件授权() {
        var request = command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH);
        Path device = Path.of("/dev/null");
        assertThrows(
                SecurityException.class,
                () -> validateRuntime(request, new SandboxRuntimeAccess(List.of(device), List.of(), List.of())));
        assertThrows(
                SecurityException.class,
                () -> validateRuntime(
                        request, new SandboxRuntimeAccess(List.of(device.getParent()), List.of(), List.of(device))));
    }

    @Test
    void 重复运行只读文件与项目根规范化后只保留一次() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("runtime-data"), "data");
        var request = command(executable, temporaryDirectory, Map.of(), new byte[0], SandboxMode.BATCH);
        var access = new SandboxRuntimeAccess(List.of(file, file, temporaryDirectory), List.of(), List.of());
        var validated = SandboxPolicyValidator.validate(
                request,
                permission(true, Duration.ofSeconds(5), List.of(temporaryDirectory, temporaryDirectory), Set.of()),
                SandboxMode.BATCH,
                access,
                com.javaclaw.nativehost.network.SandboxNetworkAccess.offline());
        assertEquals(List.of(temporaryDirectory.toRealPath(), file.toRealPath()), validated.readRoots());
    }

    @Test
    void 平台运行时范围拒绝相对路径和负输入限额() {
        Path relative = Path.of("relative");
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxRuntimeAccess(List.of(relative), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxRuntimeAccess(List.of(), List.of(relative), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxRuntimeAccess(List.of(), List.of(), List.of(relative)));
        assertThrows(
                IllegalArgumentException.class, () -> new SandboxRuntimeAccess(List.of(), List.of(), List.of(), -1));
        assertEquals(
                1_048_576, new SandboxRuntimeAccess(List.of(), List.of(), List.of(), 1_048_576).standardInputBytes());
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

    private ValidatedSandboxCommand validateRuntime(SandboxCommand request, SandboxRuntimeAccess access)
            throws Exception {
        return SandboxPolicyValidator.validate(
                request,
                permission(true),
                SandboxMode.BATCH,
                access,
                com.javaclaw.nativehost.network.SandboxNetworkAccess.offline());
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
