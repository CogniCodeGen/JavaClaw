package com.javaclaw.server.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.javaclaw.builtin.contracts.CodingSystemContracts;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Catalog;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Executable;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Source;

/** 固定系统候选与用户显式登记的有界发现；不执行程序、不搜索宿主 PATH，也不探测依赖目录。 */
public final class SystemCommandCatalog {
    private static final long MAX_EXECUTABLE_BYTES = 256L * 1024 * 1024;
    private final Path protectedDataRoot;

    /**
     * 创建发现边界。
     *
     * @param protectedDataRoot 不允许作为用户系统程序登记的服务私有目录
     */
    public SystemCommandCatalog(Path protectedDataRoot) {
        this.protectedDataRoot = canonicalRoot(protectedDataRoot);
    }

    /**
     * 发现当前配置；单个入口缺失只产生不可用事实，不阻止 Turn 创建。
     *
     * @param registry 已保存的 Workspace 登记
     * @return 当前平台、配置版本及有界入口事实
     */
    public Catalog discover(CodingSystemContracts.Registry registry) {
        List<Executable> entries = new ArrayList<>();
        Optional<String> defaultEncoding = systemEncoding();
        for (Path path : systemCandidates()) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT).replace(".exe", "");
            entries.add(
                    defaultEncoding.isPresent()
                            ? inspect(
                                    "system." + name,
                                    path.toString(),
                                    Source.SYSTEM,
                                    List.of(),
                                    defaultEncoding.orElseThrow())
                            : unavailable(
                                    "system." + name,
                                    path.toString(),
                                    Source.SYSTEM,
                                    List.of(),
                                    "UTF-8",
                                    "SYSTEM_ENCODING_UNAVAILABLE"));
        }
        for (var registration : registry.registrations()) {
            entries.add(inspect(
                    registration.id(),
                    registration.path(),
                    Source.REGISTERED,
                    registration.readRoots(),
                    registration.outputEncoding()));
        }
        return new Catalog(platform(), registry.revision(), entries);
    }

    /**
     * 重新核验冻结入口，替换、消失和不能读取均拒绝执行。
     *
     * @param frozen 本 Turn 的已冻结入口
     * @return 已复核的真实路径
     */
    public Path verify(Executable frozen) {
        if (!frozen.available()) {
            throw new SecurityException("SYSTEM_EXECUTABLE_UNAVAILABLE: " + frozen.id());
        }
        Executable current =
                inspect(frozen.id(), frozen.path(), frozen.source(), frozen.readRoots(), frozen.outputEncoding());
        if (!current.available()
                || !current.realPath().equals(frozen.realPath())
                || !current.sha256().equals(frozen.sha256())
                || !current.fileKey().equals(frozen.fileKey())) {
            throw new SecurityException("SYSTEM_EXECUTABLE_CHANGED: " + frozen.id());
        }
        return Path.of(current.realPath().orElseThrow());
    }

    /** @return 仅取可信服务启动平台的稳定标识 */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
    }

    /** @return 固定系统搜索目录，绝不加入用户登记的父目录 */
    public static List<Path> systemPath() {
        return platform().equals("windows") ? List.of(windowsSystem()) : List.of(Path.of("/usr/bin"), Path.of("/bin"));
    }

    /** @return 平台固定 Shell 位置，未安装时不回退到其他解释器 */
    public static Path shell() {
        return platform().equals("windows") ? windowsSystem().resolve("cmd.exe") : Path.of("/bin/sh");
    }

    private static Path canonicalRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        Path existing = normalized;
        while (!Files.exists(existing) && existing.getParent() != null) {
            existing = existing.getParent();
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(normalized));
        } catch (IOException failure) {
            throw new IllegalArgumentException("系统程序私有数据边界不可解析", failure);
        }
    }

    private static Optional<String> systemEncoding() {
        try {
            return Optional.of(com.javaclaw.nativehost.sandbox.SystemCommandEncoding.current());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private Executable inspect(String id, String path, Source source, List<String> reads, String encoding) {
        try {
            Path requested = Path.of(path);
            if (!requested.isAbsolute()) {
                return unavailable(id, path, source, reads, encoding, "SYSTEM_PATH_PLATFORM_MISMATCH");
            }
            if (!Files.exists(requested)) {
                return unavailable(id, path, source, reads, encoding, "SYSTEM_EXECUTABLE_MISSING");
            }
            Path real = requested.toRealPath();
            if (real.startsWith(protectedDataRoot)) {
                return unavailable(id, path, source, reads, encoding, "SYSTEM_EXECUTABLE_PRIVATE_DATA");
            }
            if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
                return unavailable(id, path, source, reads, encoding, "SYSTEM_EXECUTABLE_NOT_EXECUTABLE");
            }
            FileIdentity identity = digest(real);
            return new Executable(
                    id,
                    path,
                    Optional.of(real.toString()),
                    Optional.of(identity.sha256()),
                    Optional.of(identity.fileKey()),
                    source,
                    reads,
                    encoding,
                    true,
                    Optional.empty());
        } catch (IOException | RuntimeException failure) {
            return unavailable(id, path, source, reads, encoding, "SYSTEM_EXECUTABLE_UNREADABLE");
        }
    }

    private static Executable unavailable(
            String id, String path, Source source, List<String> reads, String encoding, String reason) {
        return new Executable(
                id,
                path,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                source,
                reads,
                encoding,
                false,
                Optional.of(reason));
    }

    private static FileIdentity digest(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
        if (before.size() > MAX_EXECUTABLE_BYTES) {
            throw new IOException("system executable exceeds digest byte bound");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_EXECUTABLE_BYTES) {
                    throw new IOException("system executable grew beyond digest bound");
                }
                digest.update(buffer, 0, count);
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("system executable changed during inspection");
        }
        if (before.fileKey() == null) {
            throw new IOException("system executable has no stable filesystem identity");
        }
        return new FileIdentity(
                HexFormat.of().formatHex(digest.digest()), before.fileKey().toString());
    }

    private static List<Path> systemCandidates() {
        if (platform().equals("windows")) {
            return List.of(
                            "cmd.exe",
                            "where.exe",
                            "whoami.exe",
                            "hostname.exe",
                            "ipconfig.exe",
                            "ping.exe",
                            "tasklist.exe",
                            "findstr.exe",
                            "sort.exe",
                            "tree.com")
                    .stream()
                    .map(windowsSystem()::resolve)
                    .toList();
        }
        return List.of(
                        "/bin/sh",
                        "/bin/bash",
                        "/bin/zsh",
                        "/bin/echo",
                        "/usr/bin/whoami",
                        "/bin/ls",
                        "/bin/cat",
                        "/bin/cp",
                        "/bin/mv",
                        "/bin/rm",
                        "/bin/mkdir",
                        "/bin/rmdir",
                        "/bin/pwd",
                        "/usr/bin/git",
                        "/usr/bin/find",
                        "/usr/bin/grep",
                        "/usr/bin/sed",
                        "/usr/bin/awk",
                        "/usr/bin/head",
                        "/usr/bin/tail",
                        "/usr/bin/sort",
                        "/usr/bin/wc",
                        "/usr/bin/which",
                        "/usr/bin/env",
                        "/usr/bin/uname",
                        "/usr/bin/curl")
                .stream()
                .map(Path::of)
                .toList();
    }

    private record FileIdentity(String sha256, String fileKey) {}

    private static Path windowsSystem() {
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))
                .toAbsolutePath()
                .normalize()
                .resolve("System32");
    }
}
