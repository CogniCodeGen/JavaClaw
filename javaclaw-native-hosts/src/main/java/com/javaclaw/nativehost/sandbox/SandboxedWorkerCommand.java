package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * @param privateScratch 非 null；默认传 Optional.empty() 禁止删除，非空时只允许删除可信调用者显式声明的独占临时根子项
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
        ResourceLimits limits,
        Optional<PrivateScratch> privateScratch) {
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
        privateScratch = Objects.requireNonNull(privateScratch, "privateScratch");
        if (privateScratch.isPresent()) {
            privateScratch.orElseThrow().requireBoundary(workingDirectory, readRoots, writeRoots, executableRoots);
        }
    }

    /**
     * 显式允许清理私有临时目录的子项，不允许删除或替换根本身。
     *
     * <p>只能由管理独占临时目录及其父目录的可信宿主调用，不能用于 Workspace 或用户配置的任意写入根。 临时根必须是唯一写入根，且与只读和可执行根互不包含；目录身份在此捕获并在启动前复核。
     *
     * @param scratchRoot 已存在、归当前用户所有的绝对规范普通目录
     * @return 保留其他命令限制的新描述
     * @throws IOException 目录元数据不可读取或文件系统无法提供稳定身份
     */
    public SandboxedWorkerCommand withPrivateScratch(Path scratchRoot) throws IOException {
        PrivateScratch scratch = PrivateScratch.capture(scratchRoot);
        return new SandboxedWorkerCommand(
                id,
                argv,
                workingDirectory,
                environment,
                readRoots,
                writeRoots,
                executableRoots,
                lifetime,
                limits,
                Optional.of(scratch));
    }

    /** 由可信调用者声明且冻结身份的私有临时根；不能用任意 Path 直接构造或伪造目录身份。 */
    public static final class PrivateScratch {
        private final Path root;
        private final Object fileKey;

        private PrivateScratch(Path root, Object fileKey) {
            this.root = root;
            this.fileKey = fileKey;
        }

        /** 返回只允许清理子项的规范根路径。 */
        public Path root() {
            return root;
        }

        private static PrivateScratch capture(Path requested) throws IOException {
            Path root = Objects.requireNonNull(requested, "scratchRoot");
            return new PrivateScratch(root, identity(root));
        }

        void verify(Path working, List<Path> reads, List<Path> writes, List<Path> executables) throws IOException {
            if (!fileKey.equals(identity(root))) {
                throw new SecurityException("Worker private scratch directory identity changed");
            }
            requireBoundary(working, reads, writes, executables);
        }

        private void requireBoundary(Path working, List<Path> reads, List<Path> writes, List<Path> executables) {
            if (writes.size() != 1 || !writes.getFirst().equals(root) || !working.startsWith(root)) {
                throw new SecurityException("Worker private scratch must be the sole write root containing its cwd");
            }
            for (Path path : java.util.stream.Stream.concat(reads.stream(), executables.stream())
                    .toList()) {
                if (path.startsWith(root) || root.startsWith(path)) {
                    throw new SecurityException("Worker private scratch must not overlap read or executable roots");
                }
            }
        }

        private static Object identity(Path root) throws IOException {
            if (!root.isAbsolute()
                    || !root.normalize().equals(root)
                    || root.getParent() == null
                    || !root.toRealPath().equals(root)) {
                throw new SecurityException("Worker private scratch must be a canonical directory without symlinks");
            }
            BasicFileAttributes attributes =
                    Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new SecurityException("Worker private scratch must be an ordinary directory");
            }
            var owner = root.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            if (!owner.equals(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS))) {
                throw new SecurityException("Worker private scratch must belong to the current user");
            }
            if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new SecurityException("Worker private scratch must not be writable by other users");
                }
            }
            if (attributes.fileKey() == null) {
                throw new IOException("Worker private scratch requires a stable directory identity");
            }
            return attributes.fileKey();
        }
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
