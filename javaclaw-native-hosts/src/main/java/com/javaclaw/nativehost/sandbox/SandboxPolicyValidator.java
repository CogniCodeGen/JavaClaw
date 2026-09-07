package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 将 PermissionProfile 收窄为平台 backend 可精确执行的路径、环境和进程边界。 */
final class SandboxPolicyValidator {
    private static final List<String> FORBIDDEN_ENVIRONMENT = List.of(
            "CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "LD_PRELOAD", "LD_LIBRARY_PATH");

    private SandboxPolicyValidator() {}

    static ValidatedSandboxCommand validate(
            SandboxCommand command, PermissionProfile permission, SandboxMode requiredMode) throws IOException {
        return validate(
                command, permission, requiredMode, SandboxRuntimeAccess.empty(), SandboxNetworkAccess.offline());
    }

    static ValidatedSandboxCommand validate(
            SandboxCommand command,
            PermissionProfile permission,
            SandboxMode requiredMode,
            SandboxRuntimeAccess runtimeAccess,
            SandboxNetworkAccess networkAccess)
            throws IOException {
        SandboxCommand checkedCommand = Objects.requireNonNull(command, "command");
        PermissionProfile checkedPermission = Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(runtimeAccess, "runtimeAccess");
        Objects.requireNonNull(networkAccess, "networkAccess");
        if (checkedCommand.mode() != requiredMode) {
            throw new IllegalArgumentException("sandbox command mode does not match the requested operation");
        }
        if (requiredMode == SandboxMode.PTY && !checkedPermission.processes().allowPty()) {
            throw new SecurityException("PermissionProfile does not allow PTY sessions");
        }
        requireNetworkBroker(checkedPermission);
        if (checkedCommand.standardInput().length
                > checkedPermission.resources().outputBytes()) {
            throw new SecurityException("sandbox standard input exceeds the configured byte limit");
        }
        Path executable = executable(checkedCommand, checkedPermission);
        List<Path> projectReadRoots = realRoots(checkedPermission.files().readRoots(), "read root");
        List<Path> projectWriteRoots = realRoots(checkedPermission.files().writeRoots(), "write root");
        Path workingDirectory = checkedCommand.workingDirectory().toRealPath();
        if (!Files.isDirectory(workingDirectory) || !insideAny(workingDirectory, projectReadRoots, projectWriteRoots)) {
            throw new SecurityException("sandbox working directory is outside permitted roots");
        }
        List<Path> readRoots = merge(projectReadRoots, realRoots(runtimeAccess.readRoots(), "runtime read root"));
        List<Path> writeRoots = merge(projectWriteRoots, realRoots(runtimeAccess.writeRoots(), "runtime write root"));
        Duration timeout =
                minimum(checkedCommand.timeout(), checkedPermission.processes().maxRunTime());
        return new ValidatedSandboxCommand(
                checkedCommand.id(),
                replaceExecutable(checkedCommand.argv(), executable),
                executable,
                workingDirectory,
                environment(checkedCommand.environment()),
                checkedCommand.standardInput(),
                checkedCommand.mode(),
                timeout,
                checkedPermission.resources(),
                readRoots,
                writeRoots,
                executableRoots(executable, runtimeAccess, readRoots, writeRoots),
                checkedPermission.files().allowDelete(),
                java.util.Optional.empty(),
                networkAccess);
    }

    private static List<Path> merge(List<Path> first, List<Path> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream())
                .distinct()
                .toList();
    }

    private static List<Path> executableRoots(
            Path executable, SandboxRuntimeAccess access, List<Path> reads, List<Path> writes) throws IOException {
        ArrayList<Path> result = new ArrayList<>(List.of(executable));
        for (Path root : access.executableRoots()) {
            Path real = root.toRealPath();
            if (!insideAny(real, reads, writes)) {
                throw new SecurityException("runtime executable root must be inside an approved runtime root");
            }
            if (!Files.isDirectory(real) && (!Files.isRegularFile(real) || !Files.isExecutable(real))) {
                throw new SecurityException("runtime executable root must be an executable file or directory");
            }
            if (!result.contains(real)) {
                result.add(real);
            }
        }
        return List.copyOf(result);
    }

    private static Path executable(SandboxCommand command, PermissionProfile permission) throws IOException {
        Path requested = Path.of(command.argv().getFirst());
        if (!requested.isAbsolute()) {
            throw new SecurityException("sandbox executable must be an absolute path");
        }
        Path real = requested.toRealPath();
        if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
            throw new SecurityException("sandbox executable is not an executable file");
        }
        String name = real.getFileName().toString();
        if (!permission.processes().executables().contains(name)) {
            throw new SecurityException("executable is not allowed by PermissionProfile: " + name);
        }
        return real;
    }

    private static List<String> replaceExecutable(List<String> argv, Path executable) {
        ArrayList<String> result = new ArrayList<>(argv);
        result.set(0, executable.toString());
        return List.copyOf(result);
    }

    private static List<Path> realRoots(List<Path> roots, String name) throws IOException {
        ArrayList<Path> result = new ArrayList<>();
        for (Path root : roots) {
            Path real = root.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new SecurityException(name + " is not a directory: " + root);
            }
            if (!result.contains(real)) {
                result.add(real);
            }
        }
        return List.copyOf(result);
    }

    private static boolean insideAny(Path path, List<Path> first, List<Path> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).anyMatch(path::startsWith);
    }

    private static Map<String, String> environment(Map<String, String> source) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || FORBIDDEN_ENVIRONMENT.contains(name)
                    || name.startsWith("DYLD_")) {
                throw new SecurityException("sandbox environment variable is forbidden: " + name);
            }
            if (value.indexOf('\0') >= 0) {
                throw new SecurityException("sandbox environment contains NUL: " + name);
            }
            result.put(name, value);
        });
        return Map.copyOf(result);
    }

    private static void requireNetworkBroker(PermissionProfile permission) {
        if (!permission.network().hosts().isEmpty()
                || !permission.network().ports().isEmpty()) {
            throw new UnsupportedOperationException(
                    "raw process networking is unavailable; use the host NetworkBroker allowlist");
        }
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
