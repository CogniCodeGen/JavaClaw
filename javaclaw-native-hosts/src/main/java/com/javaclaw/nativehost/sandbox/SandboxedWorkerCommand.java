package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ResourceLimits;

/**
 * 长生命周期 Worker 的 fail-closed 原生 Sandbox 启动描述。
 *
 * <p>该描述不接受标准输入内容，调用方在启动后独占进程管道。环境必须是最小显式集合，尤其禁止把宿主 HOME、代理、Java 注入参数或动态库搜索路径传入 Worker。
 *
 * @param id 启动实例标识
 * @param argv 不经过 shell 的目标命令
 * @param workingDirectory 仅属于 Worker 的工作目录
 * @param environment 最小显式环境
 * @param readRoots Worker 只读运行时根
 * @param writeRoots Worker 临时写入根
 * @param executableRoots 子进程可执行文件根，例如打包的 Chromium runtime
 * @param lifetime 进程最长生命周期
 * @param limits 进程树资源上限
 */
public record SandboxedWorkerCommand(
        String id,
        List<String> argv,
        Path workingDirectory,
        Map<String, String> environment,
        List<Path> readRoots,
        List<Path> writeRoots,
        List<Path> executableRoots,
        Duration lifetime,
        ResourceLimits limits) {
    private static final Set<String> FORBIDDEN_ENVIRONMENT = Set.of(
            "CLASSPATH",
            "HOME",
            "HOMEDRIVE",
            "HOMEPATH",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "LD_LIBRARY_PATH",
            "LD_PRELOAD",
            "NO_PROXY",
            "_JAVA_OPTIONS");

    /** 复制集合并拒绝宿主环境与无界进程。 */
    public SandboxedWorkerCommand {
        id = text(id, "id");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("argv must contain non-blank values");
        }
        workingDirectory = normalized(workingDirectory, "workingDirectory");
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        environment.forEach(SandboxedWorkerCommand::requireEnvironment);
        readRoots = paths(readRoots, "readRoots");
        writeRoots = paths(writeRoots, "writeRoots");
        executableRoots = paths(executableRoots, "executableRoots");
        if (readRoots.isEmpty() || writeRoots.isEmpty() || executableRoots.isEmpty()) {
            throw new IllegalArgumentException("Worker read, write and executable roots must not be empty");
        }
        lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.compareTo(Duration.ofSeconds(1)) < 0 || lifetime.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Worker lifetime must be between 1 second and 10 minutes");
        }
        Objects.requireNonNull(limits, "limits");
    }

    private static List<Path> paths(List<Path> source, String name) {
        return Objects.requireNonNull(source, name).stream()
                .map(path -> normalized(path, name + " element"))
                .distinct()
                .toList();
    }

    private static Path normalized(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }

    private static void requireEnvironment(String name, String value) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")
                || FORBIDDEN_ENVIRONMENT.contains(name.toUpperCase(java.util.Locale.ROOT))
                || name.toUpperCase(java.util.Locale.ROOT).startsWith("DYLD_")) {
            throw new SecurityException("Worker environment variable is forbidden: " + name);
        }
        if (Objects.requireNonNull(value, "environment value").indexOf('\0') >= 0) {
            throw new SecurityException("Worker environment contains NUL: " + name);
        }
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
