package com.javaclaw.platform.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JavaClaw 受管文件唯一且可写的便携根目录。
 *
 * <p>发行代码位于 {@code runtime/} 中；开发代码优先定位包含 Desktop 源码模块的
 * Maven 聚合根，也支持独立的 {@code pom.xml + src/} 源码树。进程当前工作目录
 * 不参与应用根目录选择，历史数据格式不会被导入。
 */
public final class ApplicationHome {

    /** 保留旧迁移参数名，使历史启动参数保持无效。 */
    public static final String MIGRATION_SOURCE_PROPERTY = "javaclaw.migration.source";
    public static final String DEVELOPMENT_HOME_PROPERTY = "javaclaw.development.home";
    private static final String DESKTOP_MODULE = "javaclaw-desktop";
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
            return detected.withDevelopmentHome(Path.of(override));
        } catch (Exception failure) {
            throw new IllegalStateException("无法确定 JavaClaw 应用目录", failure);
        }
    }

    /** 从实际启动器、JAR 或 classes 路径严格推导，供发行检查复用。 */
    public static ApplicationHome fromCodeSource(Path codeSource) throws IOException {
        Path source = Objects.requireNonNull(codeSource, "codeSource").toRealPath();
        for (Path cursor = Files.isDirectory(source) ? source : source.getParent();
                cursor != null; cursor = cursor.getParent()) {
            if (cursor.getFileName() != null
                    && "runtime".equals(cursor.getFileName().toString())) {
                return new ApplicationHome(cursor.getParent().toRealPath(), false);
            }
        }
        Path standaloneRoot = null;
        for (Path cursor = Files.isDirectory(source) ? source : source.getParent();
                cursor != null; cursor = cursor.getParent()) {
            if (isAggregateSourceRoot(cursor)) {
                return new ApplicationHome(cursor.toRealPath(), true);
            }
            if (standaloneRoot == null && isMavenSourceRoot(cursor)) standaloneRoot = cursor;
        }
        if (standaloneRoot != null) return new ApplicationHome(standaloneRoot.toRealPath(), true);
        throw new IOException("代码必须位于应用 runtime/ 或 Maven 源码树内: " + source);
    }

    /** 测试数据仅允许放入已识别源码树的构建目录，不能借模块迁移扩大边界。 */
    ApplicationHome withDevelopmentHome(Path requestedHome) throws IOException {
        if (!development) throw new IOException("发行版不接受开发数据目录参数");
        Path requested = Objects.requireNonNull(requestedHome, "requestedHome").toAbsolutePath().normalize();
        boolean desktopBuild = isAggregateSourceRoot(root)
                && requested.startsWith(root.resolve(DESKTOP_MODULE).resolve("target"));
        if (!requested.equals(root) && !requested.startsWith(root.resolve("target")) && !desktopBuild) {
            throw new IOException("开发数据目录只能位于源码树或 Desktop 模块的 target/ 下: " + requested);
        }
        Path cursor = root;
        for (Path segment : root.relativize(requested)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("开发数据目录不能经过符号链接: " + cursor);
            }
        }
        return new ApplicationHome(requested, true);
    }

    private static boolean isAggregateSourceRoot(Path root) {
        Path desktop = root.resolve(DESKTOP_MODULE);
        return Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(desktop, LinkOption.NOFOLLOW_LINKS) && isMavenSourceRoot(desktop);
    }

    private static boolean isMavenSourceRoot(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(root.resolve("src"), LinkOption.NOFOLLOW_LINKS);
    }

    /** 为已验证启动位置的启动器和测试指定应用根目录。 */
    public static ApplicationHome at(Path root) throws IOException {
        return new ApplicationHome(Objects.requireNonNull(root, "root").toRealPath(), false);
    }

    public boolean development() { return development; }
    public Path root() { return root; }
    public Path runtimeDirectory() { return root.resolve("runtime"); }
    public Path pluginsDirectory() { return root.resolve("plugins-v2"); }
    public Path dataDirectory() { return root.resolve("data-v5"); }
    public Path cacheDirectory() { return dataDirectory().resolve("cache"); }
    public Path temporaryDirectory() { return dataDirectory().resolve("tmp"); }
    public Path logsDirectory() { return dataDirectory().resolve("logs"); }
    public Path playwrightBrowsersDirectory() { return cacheDirectory().resolve("playwright-browsers"); }

    /**
     * 在日志、数据库及原生服务启动前准备受管目录。
     */
    public ApplicationHome prepare() throws IOException {
        return prepare(null);
    }

    /** 初始化独立的格式 5 目录；旧目录不读取、不迁移、不删除。 */
    public ApplicationHome prepare(ApplicationUpgradeGuard guard) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("应用目录不能是符号链接: " + root);
        requireDirectory(root);
        Path probe = Files.createTempFile(root, ".javaclaw-write-", ".tmp");
        Files.delete(probe);
        requireDirectory(dataDirectory());
        if (guard != null) guard.requireHome(this);
        // 在缓存和日志创建前写入新格式标记，始终保留历史目录。
        new DataRoot(dataDirectory()).prepare();
        requireDirectory(pluginsDirectory());
        requireDirectory(cacheDirectory());
        requireDirectory(temporaryDirectory());
        requireDirectory(logsDirectory());
        requireDirectory(playwrightBrowsersDirectory());
        return this;
    }

    /** 拒绝路径越界，以及规范根目录下已有的符号链接。 */
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
