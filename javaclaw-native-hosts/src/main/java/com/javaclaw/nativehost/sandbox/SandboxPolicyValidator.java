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

/** 将 PermissionProfile 收窄为平台 backend 可精确执行的路径、环境和进程边界。 */
final class SandboxPolicyValidator {
    private static final List<String> FORBIDDEN_ENVIRONMENT = List.of(
            "CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "LD_PRELOAD", "LD_LIBRARY_PATH");

    private SandboxPolicyValidator() {}

    static ValidatedSandboxCommand validate(
            SandboxCommand command, PermissionProfile permission, SandboxMode requiredMode) throws IOException {
        SandboxCommand checkedCommand = Objects.requireNonNull(command, "command");
        PermissionProfile checkedPermission = Objects.requireNonNull(permission, "permission");
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
        List<Path> readRoots = realRoots(checkedPermission.files().readRoots(), "read root");
        List<Path> writeRoots = realRoots(checkedPermission.files().writeRoots(), "write root");
        Path workingDirectory = checkedCommand.workingDirectory().toRealPath();
        if (!Files.isDirectory(workingDirectory) || !insideAny(workingDirectory, readRoots, writeRoots)) {
            throw new SecurityException("sandbox working directory is outside permitted roots");
        }
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
                List.of(executable),
                checkedPermission.files().allowDelete(),
                java.util.Optional.empty());
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
