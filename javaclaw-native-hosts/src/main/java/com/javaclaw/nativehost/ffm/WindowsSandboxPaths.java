package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

/** 将 Windows 请求解析成无 reparse point 的真实路径，避免 ACL 授权被路径别名绕过。 */
final class WindowsSandboxPaths {
    private static final int INVALID_FILE_ATTRIBUTES = -1;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;

    private WindowsSandboxPaths() {}

    static Prepared prepare(WindowsSandboxRequest request) throws IOException {
        WindowsSandboxNative.requireBackend();
        List<Path> reads = roots(request.readRoots(), "read root");
        List<Path> writes = roots(request.writeRoots(), "write root");
        Path workingDirectory = canonical(request.context().workingDirectory(), "working directory");
        if (!Files.isDirectory(workingDirectory) || !inside(workingDirectory, reads, writes)) {
            throw new SecurityException("Windows sandbox working directory is outside permitted roots");
        }
        Path requestedExecutable;
        try {
            requestedExecutable = Path.of(request.arguments().getFirst());
        } catch (RuntimeException failure) {
            throw new IOException("Windows sandbox executable path is invalid", failure);
        }
        if (!requestedExecutable.isAbsolute()) {
            throw new SecurityException("Windows sandbox executable must be absolute");
        }
        Path executable = canonical(requestedExecutable, "executable");
        if (!Files.isRegularFile(executable) || !inside(executable, reads, writes) && !trusted(executable)) {
            throw new SecurityException("Windows sandbox executable is outside permitted roots");
        }
        ArrayList<String> arguments = new ArrayList<>(request.arguments());
        arguments.set(0, executable.toString());
        return new Prepared(
                List.copyOf(arguments),
                executable,
                workingDirectory,
                request.context().environment(),
                reads,
                writes,
                request.allowDelete(),
                request.timeout(),
                request.limits(),
                request.networkAccess());
    }

    static void rejectReparsePoint(Path path) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            var result = backend.invoke(backend.getFileAttributes, WindowsSandboxNative.wide(arena, path.toString()));
            int attributes = result.number();
            if (attributes == INVALID_FILE_ATTRIBUTES) {
                throw WindowsSandboxNative.error("GetFileAttributesW", result.error());
            }
            if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new SecurityException("Windows sandbox root must not be a reparse point: " + path);
            }
        }
    }

    private static List<Path> roots(List<Path> source, String name) throws IOException {
        ArrayList<Path> result = new ArrayList<>();
        for (Path root : source) {
            Path real = canonical(root, name);
            if (!Files.isDirectory(real)) {
                throw new SecurityException(name + " is not a directory: " + root);
            }
            if (!result.contains(real)) {
                result.add(real);
            }
        }
        return List.copyOf(result);
    }

    private static Path canonical(Path value, String name) throws IOException {
        Path normalized = value.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(name + " does not exist: " + normalized);
        }
        rejectReparsePoint(normalized);
        Path real = normalized.toRealPath();
        rejectReparsePoint(real);
        return real;
    }

    private static boolean inside(Path path, List<Path> first, List<Path> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).anyMatch(path::startsWith);
    }

    static boolean trusted(Path executable) {
        ArrayList<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("java.home", "C:\\JavaClawRuntime"))
                .toAbsolutePath()
                .normalize());
        String systemRoot = System.getenv("SystemRoot");
        roots.add(Path.of(systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot)
                .toAbsolutePath()
                .normalize());
        return roots.stream().anyMatch(executable::startsWith);
    }

    record Prepared(
            List<String> arguments,
            Path executable,
            Path workingDirectory,
            java.util.Map<String, String> environment,
            List<Path> readRoots,
            List<Path> writeRoots,
            boolean allowDelete,
            java.time.Duration timeout,
            com.javaclaw.api.ResourceLimits limits,
            SandboxNetworkAccess networkAccess) {}
}
