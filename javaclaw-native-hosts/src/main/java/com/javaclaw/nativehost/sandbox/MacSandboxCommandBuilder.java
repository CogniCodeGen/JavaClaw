package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.javaclaw.nativehost.network.SandboxNetworkAccess;

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
            Path.of("/private/var/select/sh"),
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
        // dyld 必须查询 AMFI 的 @rpath 策略；此规则允许查询，不改变代码签名校验结果。
        profile.append("(allow system-mac-syscall (mac-policy-name \"AMFI\"))\n");
        profile.append("(allow process-info* (target same-sandbox))\n");
        profile.append("(allow sysctl-write (sysctl-name \"kern.grade_cputype\"))\n");
        // JDK 与 Node 的 JIT 需要匿名可执行映射；生成代码仍继承相同文件、进程和网络边界。
        profile.append("(allow dynamic-code-generation)\n");
        // FileChannel.force 使用 F_FULLFSYNC；只允许刷盘，不开放任意 fcntl 命令。
        profile.append("(allow system-fcntl (fcntl-command F_FULLFSYNC F_BARRIERFSYNC))\n");
        // Java/Python 目录遍历复制已有 FD 并设置 close-on-exec，不授权打开新的文件。
        profile.append("(allow system-fcntl (fcntl-command F_GETFD F_SETFD F_DUPFD_CLOEXEC))\n");
        // Node 初始化查询/设置已有 FD 模式；Gradle 使用现有文件上的 POSIX advisory lock。
        profile.append("(allow system-fcntl (fcntl-command F_GETFL F_SETFL F_GETLK F_SETLK F_SETLKW))\n");
        // 运行库设置 TCP 行为并查询连接结果；这些选项不授予连接、监听或任何新目标。
        // Seatbelt 没有 TCP_NODELAY 别名；Darwin tcp.h 将该选项固定为 1。
        profile.append(
                "(allow socket-option-set (socket-option-name SO_OOBINLINE SO_NOSIGPIPE SO_KEEPALIVE SO_REUSEADDR 1))\n");
        profile.append("(allow socket-option-get (socket-option-name SO_ERROR SO_TYPE))\n");
        profile.append("(allow process-fork)\n");
        profile.append("(allow process-exec (literal \"")
                .append(escape(SandboxHelperCommand.javaExecutable()))
                .append("\"))\n");
        appendPaths(profile, "process-exec", command.executableRoots());
        profile.append("(allow signal (target self))\n");
        profile.append("(allow sysctl-read)\n");
        // 运行库仅能查询基础目录及电源服务；不开放可代发网络请求的任意宿主 Mach 服务。
        profile.append("(allow mach-lookup (global-name \"com.apple.system.opendirectoryd.libinfo\")")
                .append(" (global-name \"com.apple.PowerManagement.control\"))\n");
        appendPaths(profile, "file-read* file-read-metadata file-test-existence", readable);
        appendLiteralPaths(profile, "file-read-metadata file-test-existence", traversalDirectories(readable));
        appendPaths(profile, "file-map-executable", executableRoots(command));
        appendPaths(profile, "file-write*", command.writeRoots());
        protectWriteRoots(profile, command.writeRoots());
        appendPaths(profile, "file-write-data", Set.of(Path.of("/dev/null")));
        command.terminal().ifPresent(terminal -> appendPaths(profile, "file-write-data", Set.of(terminal)));
        if (!command.allowDelete()) {
            profile.append("(deny file-write-unlink)\n");
        }
        appendNetwork(profile, command.networkAccess());
        return profile.toString();
    }

    private static void protectWriteRoots(StringBuilder profile, Iterable<Path> roots) {
        LinkedHashSet<Path> protectedRoots = new LinkedHashSet<>();
        roots.forEach(path -> {
            if (Files.isDirectory(path)) {
                protectedRoots.add(path);
                macOsPathAlias(path).ifPresent(protectedRoots::add);
            }
        });
        for (Path path : protectedRoots) {
            // 项目可删除根内的文件，但不能将权限根本身替换成指向宿主其他位置的链接。
            profile.append("(deny file-write-unlink (literal \"")
                    .append(escape(path))
                    .append("\"))\n");
        }
    }

    private static void appendNetwork(StringBuilder profile, SandboxNetworkAccess network) {
        if (network.mode() == SandboxNetworkAccess.Mode.OFFLINE) {
            profile.append("(deny network*)\n");
            return;
        }
        int port = network.proxyEndpoint().orElseThrow().getPort();
        // deny default 保持 DNS、UDP、IPv6 和其他 loopback 端口关闭；不可追加覆盖此例外的 deny network*。
        profile.append("(allow network-outbound (remote tcp4 \"localhost:")
                .append(port)
                .append("\"))\n");
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
