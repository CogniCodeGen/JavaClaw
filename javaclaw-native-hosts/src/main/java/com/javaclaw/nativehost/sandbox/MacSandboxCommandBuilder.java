package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 使用 macOS Seatbelt profile 构造默认拒绝的无 shell 启动命令。 */
final class MacSandboxCommandBuilder implements SandboxCommandBuilder {
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");
    private static final List<Path> SYSTEM_READ_ROOTS = List.of(
            Path.of("/System"),
            Path.of("/usr"),
            Path.of("/bin"),
            Path.of("/sbin"),
            Path.of("/Library/Apple"),
            Path.of("/private/etc"),
            Path.of("/private/var/db"),
            Path.of("/dev"));

    @Override
    public SandboxLaunchPlan build(ValidatedSandboxCommand command) {
        if (!Files.isExecutable(SANDBOX_EXEC)) {
            throw new UnsupportedOperationException("macOS sandbox-exec is unavailable");
        }
        ArrayList<String> isolated = new ArrayList<>();
        isolated.add(SANDBOX_EXEC.toString());
        isolated.add("-p");
        isolated.add(profile(command));
        isolated.addAll(command.argv());
        return new SandboxLaunchPlan(
                name(), SandboxHelperCommand.wrap(command, isolated), command.environment(), 0, false);
    }

    @Override
    public String name() {
        return "macos-seatbelt";
    }

    private static String profile(ValidatedSandboxCommand command) {
        Set<Path> readable = new LinkedHashSet<>(SYSTEM_READ_ROOTS);
        readable.addAll(command.readRoots());
        readable.addAll(command.writeRoots());
        readable.add(command.executable());
        command.terminal().ifPresent(readable::add);
        StringBuilder profile = new StringBuilder(2048);
        profile.append("(version 3)\n(deny default)\n(import \"dyld-support.sb\")\n");
        profile.append("(allow syscall*)\n");
        profile.append("(allow process-fork)\n");
        profile.append("(allow process-exec (literal \"")
                .append(escape(SandboxHelperCommand.javaExecutable()))
                .append("\"))\n");
        appendPaths(profile, "process-exec", command.executableRoots());
        profile.append("(allow signal (target self))\n");
        profile.append("(allow sysctl-read)\n(allow mach-lookup)\n");
        appendPaths(profile, "file-read* file-read-metadata file-test-existence", readable);
        appendLiteralPaths(profile, "file-read-metadata file-test-existence", traversalDirectories(readable));
        appendPaths(profile, "file-map-executable", executableRoots(command));
        appendPaths(profile, "file-write*", command.writeRoots());
        appendPaths(profile, "file-write-data", Set.of(Path.of("/dev/null")));
        command.terminal().ifPresent(terminal -> appendPaths(profile, "file-write-data", Set.of(terminal)));
        if (!command.allowDelete()) {
            profile.append("(deny file-write-unlink)\n");
        }
        profile.append("(deny network*)\n");
        return profile.toString();
    }

    private static Set<Path> executableRoots(ValidatedSandboxCommand command) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(Path.of("/System"));
        roots.add(Path.of("/usr/lib"));
        roots.addAll(command.executableRoots());
        Path javaHome =
                Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        if (command.executable().startsWith(javaHome)) {
            roots.add(javaHome);
        }
        return Set.copyOf(roots);
    }

    private static void appendPaths(StringBuilder profile, String operation, Iterable<Path> paths) {
        LinkedHashSet<Path> values = new LinkedHashSet<>();
        paths.forEach(path -> {
            if (Files.exists(path)) {
                Path normalized = path.toAbsolutePath().normalize();
                values.add(normalized);
                macOsPathAlias(normalized).ifPresent(values::add);
            }
        });
        if (values.isEmpty()) {
            return;
        }
        profile.append("(allow ").append(operation);
        for (Path path : values) {
            String filter = Files.isDirectory(path) ? "subpath" : "literal";
            profile.append(" (")
                    .append(filter)
                    .append(" \"")
                    .append(escape(path))
                    .append("\")");
        }
        profile.append(")\n");
    }

    private static Set<Path> traversalDirectories(Iterable<Path> roots) {
        LinkedHashSet<Path> directories = new LinkedHashSet<>();
        roots.forEach(root -> {
            Path normalized = root.toAbsolutePath().normalize();
            addParents(directories, normalized);
            macOsPathAlias(normalized).ifPresent(alias -> addParents(directories, alias));
        });
        return Set.copyOf(directories);
    }

    private static void addParents(Set<Path> directories, Path path) {
        Path parent = path.getParent();
        while (parent != null) {
            directories.add(parent);
            parent = parent.getParent();
        }
    }

    private static void appendLiteralPaths(StringBuilder profile, String operation, Iterable<Path> paths) {
        ArrayList<Path> values = new ArrayList<>();
        paths.forEach(path -> {
            if (Files.exists(path)) {
                values.add(path);
            }
        });
        if (values.isEmpty()) {
            return;
        }
        profile.append("(allow ").append(operation);
        for (Path path : values) {
            profile.append(" (literal \"").append(escape(path)).append("\")");
        }
        profile.append(")\n");
    }

    /**
     * 返回 macOS 为同一目录公开的稳定别名。
     *
     * <p>Seatbelt 按传入路径匹配 filter，不会替命令参数解析 {@code /var -> /private/var} 与 {@code /tmp ->
     * /private/tmp}。规则必须同时列出这两个等价名字，否则合法的临时目录会被误拒绝。
     */
    private static java.util.Optional<Path> macOsPathAlias(Path path) {
        Path privateVar = Path.of("/private/var");
        if (path.startsWith(privateVar)) {
            return java.util.Optional.of(Path.of("/var").resolve(privateVar.relativize(path)));
        }
        Path privateTmp = Path.of("/private/tmp");
        if (path.startsWith(privateTmp)) {
            return java.util.Optional.of(Path.of("/tmp").resolve(privateTmp.relativize(path)));
        }
        return java.util.Optional.empty();
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
