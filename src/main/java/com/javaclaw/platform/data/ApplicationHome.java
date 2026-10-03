package com.javaclaw.platform.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The one portable, writable outer directory for JavaClaw-managed files.
 *
 * <p>Production code must live below {@code runtime/}; development code is located through the
 * source tree containing {@code pom.xml} and {@code src/}. The process working directory is never
 * used to choose the active home. Earlier data formats are never imported.
 */
public final class ApplicationHome {

    /** Former migration setting; retained only so old launch flags are inert. */
    public static final String MIGRATION_SOURCE_PROPERTY = "javaclaw.migration.source";
    public static final String DEVELOPMENT_HOME_PROPERTY = "javaclaw.development.home";
    private final Path root;
    private final boolean development;

    private ApplicationHome(Path root, boolean development) {
        this.root = root;
        this.development = development;
    }

    public static ApplicationHome resolve() {
        try {
            var source = ApplicationHome.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null
                    || !"file".equalsIgnoreCase(source.getLocation().getProtocol())) {
                throw new IOException("无法确定可信的应用代码位置");
            }
            ApplicationHome detected = fromCodeSource(Path.of(source.getLocation().toURI()));
            String override = System.getProperty(DEVELOPMENT_HOME_PROPERTY);
            if (override == null || override.isBlank()) return detected;
            if (!detected.development) {
                throw new IOException("发行版不接受开发数据目录参数");
            }
            Path requested = Path.of(override).toAbsolutePath().normalize();
            Path buildRoot = detected.root.resolve("target");
            if (!requested.equals(detected.root) && !requested.startsWith(buildRoot)) {
                throw new IOException("开发数据目录只能位于源码树的 target/ 下: " + requested);
            }
            Path cursor = detected.root;
            for (Path segment : detected.root.relativize(requested)) {
                cursor = cursor.resolve(segment);
                if (Files.isSymbolicLink(cursor)) {
                    throw new IOException("开发数据目录不能经过符号链接: " + cursor);
                }
            }
            return new ApplicationHome(requested, true);
        } catch (Exception failure) {
            throw new IllegalStateException("无法确定 JavaClaw 应用目录", failure);
        }
    }

    /** Strict derivation from a real launcher/JAR/classes path; useful to packaging checks. */
    public static ApplicationHome fromCodeSource(Path codeSource) throws IOException {
        Path source = Objects.requireNonNull(codeSource, "codeSource").toRealPath();
        for (Path cursor = Files.isDirectory(source) ? source : source.getParent();
                cursor != null; cursor = cursor.getParent()) {
            if (cursor.getFileName() != null
                    && "runtime".equals(cursor.getFileName().toString())) {
                return new ApplicationHome(cursor.getParent().toRealPath(), false);
            }
        }
        for (Path cursor = Files.isDirectory(source) ? source : source.getParent();
                cursor != null; cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(cursor.resolve("src"), LinkOption.NOFOLLOW_LINKS)) {
                return new ApplicationHome(cursor.toRealPath(), true);
            }
        }
        throw new IOException("代码必须位于应用 runtime/ 或 Maven 源码树内: " + source);
    }

    /** Explicit home for tests and launchers that have already authenticated their own location. */
    public static ApplicationHome at(Path root) throws IOException {
        return new ApplicationHome(Objects.requireNonNull(root, "root").toRealPath(), false);
    }

    public Path root() { return root; }
    public Path runtimeDirectory() { return root.resolve("runtime"); }
    public Path pluginsDirectory() { return root.resolve("plugins"); }
    public Path dataDirectory() { return root.resolve("data"); }
    public Path cacheDirectory() { return dataDirectory().resolve("cache"); }
    public Path temporaryDirectory() { return dataDirectory().resolve("tmp"); }
    public Path logsDirectory() { return dataDirectory().resolve("logs"); }
    public Path playwrightBrowsersDirectory() { return cacheDirectory().resolve("playwright-browsers"); }

    /**
     * Prepare managed directories before logging, database or native services start.
     */
    public ApplicationHome prepare() throws IOException {
        return prepare(null);
    }

    /** Initialize format 4; an upgrade from format 3 requires an acquired instance guard. */
    public ApplicationHome prepare(ApplicationUpgradeGuard guard) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("应用目录不能是符号链接: " + root);
        requireDirectory(root);
        Path probe = Files.createTempFile(root, ".javaclaw-write-", ".tmp");
        Files.delete(probe);
        requireDirectory(dataDirectory());
        if (guard != null) guard.requireHome(this);
        upgradeFormatIfNeeded(guard);
        // The version marker must be written before cache/tmp/log directories make data nonempty.
        new DataRoot(dataDirectory()).prepare();
        requireDirectory(pluginsDirectory());
        requireDirectory(cacheDirectory());
        requireDirectory(temporaryDirectory());
        requireDirectory(logsDirectory());
        requireDirectory(playwrightBrowsersDirectory());
        return this;
    }

    private void upgradeFormatIfNeeded(ApplicationUpgradeGuard guard) throws IOException {
        Path marker = requireManaged(dataDirectory().resolve(DataRoot.FORMAT_FILE));
        Path reset = requireManaged(root.resolve(".javaclaw-reset-in-progress"));
        boolean interrupted = Files.exists(reset, LinkOption.NOFOLLOW_LINKS);
        if (interrupted && !Files.isRegularFile(reset, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("升级标记不是普通文件: " + reset);
        }
        String current = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                ? Files.readString(marker).strip() : "";
        if (!interrupted && !"3".equals(current)) return;
        if (guard == null) throw new IOException("格式 3 数据升级需要独占启动锁");
        if (!"3".equals(current) && !DataRoot.FORMAT_VERSION.equals(current)) {
            throw new IOException("升级中数据格式无效: " + current);
        }
        if (interrupted && !"3-to-4".equals(Files.readString(reset).strip())) {
            throw new IOException("无法识别的升级标记: " + reset);
        }
        assertPlainTree(dataDirectory());
        assertPlainTree(pluginsDirectory());
        if (!interrupted) {
            com.javaclaw.util.AtomicFileWriter.writeString(reset, "3-to-4");
        }
        // Old launchers reject format 4 before trying to acquire their data lock.
        com.javaclaw.util.AtomicFileWriter.writeString(marker, DataRoot.FORMAT_VERSION);
        clearManagedChildren(dataDirectory(), java.util.Set.of(
                DataRoot.FORMAT_FILE, ApplicationUpgradeGuard.DATA_LOCK));
        clearManagedChildren(pluginsDirectory(), java.util.Set.of());
        Files.delete(reset);
    }

    private void assertPlainTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("升级路径不是普通目录: " + directory);
        }
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(
                    Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                requireManaged(path);
                if (Files.isSymbolicLink(path)) throw new IOException("升级目录内存在符号链接: " + path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(
                    Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                requireManaged(path);
                if (Files.isSymbolicLink(path)) throw new IOException("升级目录内存在符号链接: " + path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private void clearManagedChildren(Path directory, java.util.Set<String> preserve)
            throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(
                    Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                requireManaged(path);
                if (!path.getParent().equals(directory)
                        || !preserve.contains(path.getFileName().toString())) {
                    Files.delete(path);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(
                    Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                if (!path.equals(directory)) Files.delete(path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /** Reject lexical escape and any existing symbolic link below the canonical home. */
    public Path requireManaged(Path candidate) throws IOException {
        Path normalized = Objects.requireNonNull(candidate, "candidate").toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("应用目录外路径被拒绝: " + normalized);
        Path current = root;
        Path relative = root.relativize(normalized);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("应用目录内存在符号链接: " + current);
            }
        }
        return normalized;
    }

    public void requireDirectory(Path path) throws IOException {
        Path checked = requireManaged(path);
        Files.createDirectories(checked);
        if (!Files.isDirectory(checked, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("应用目录不是文件夹: " + checked);
        }
    }

}
