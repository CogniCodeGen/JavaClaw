package com.javaclaw.server.extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Safe ZIP staging, validation, atomic install and recoverable Trash uninstall. */
public final class PluginBundleInstaller {
    public static final long MAX_ZIP_BYTES = 256L * 1024L * 1024L;
    public static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    public static final int MAX_FILES = 10_000;
    public static final long MAX_COMPRESSION_RATIO = 100;

    private final Path pluginRoot;
    private final Path stagingRoot;
    private final Path trashRoot;
    private final PluginCatalog catalog;

    /**
     * 建立 owner-only 的安装、staging 和 Trash 根；staging 与安装根必须同文件系统以支持原子移动。
     *
     * @throws java.io.IOException 目录不安全或无法满足原子安装要求
     */
    public PluginBundleInstaller(Path pluginRoot, Path stagingRoot, Path trashRoot, PluginCatalog catalog)
            throws IOException {
        this.pluginRoot = secureDirectory(pluginRoot);
        this.stagingRoot = secureDirectory(stagingRoot);
        this.trashRoot = secureDirectory(trashRoot);
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        if (!Files.getFileStore(this.pluginRoot).equals(Files.getFileStore(this.stagingRoot))) {
            throw new IOException("plugin staging and install roots must share a filesystem");
        }
    }

    /**
     * 有界读取 ZIP、校验摘要/条目/路径/签名并原子安装；unsignedConfirmed 只确认来源，不能提升权限。
     *
     * @return 已验证包及 ZIP 摘要
     * @throws java.io.IOException 包损坏、越界、签名失败或不支持原子移动
     */
    public InstalledPlugin install(InputStream zip, String expectedSha256, boolean unsignedConfirmed)
            throws IOException {
        Objects.requireNonNull(zip, "zip");
        String expected = expectedSha256 == null || expectedSha256.isBlank() ? null : normalizedHash(expectedSha256);
        Path archive = Files.createTempFile(stagingRoot, ".plugin-upload-", ".zip");
        Path staging = null;
        try {
            String bundleHash = copyArchive(zip, archive);
            if (expected != null && !expected.equals(bundleHash)) {
                throw new IOException("plugin ZIP SHA-256 does not match attachment metadata");
            }
            ZipSafetyIndex safety = ZipSafetyIndex.read(archive);
            staging = Files.createTempDirectory(stagingRoot, ".plugin-staging-");
            extract(archive, staging, safety);
            LoadedPlugin inspected = catalog.loader().load(staging, unsignedConfirmed);
            Path target = pluginRoot
                    .resolve(inspected.manifest().id())
                    .resolve(inspected.manifest().version())
                    .normalize();
            if (!target.startsWith(pluginRoot)) {
                throw new IOException("plugin target escapes root");
            }
            Files.createDirectories(target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("plugin version is already installed: "
                        + inspected.manifest().id() + " " + inspected.manifest().version());
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                throw new IOException("plugin filesystem does not support atomic installation", unsupported);
            }
            staging = null;
            LoadedPlugin installed;
            try {
                installed = catalog.register(target, unsignedConfirmed);
            } catch (IOException | RuntimeException failure) {
                Path recovery = trashPath(
                        inspected.manifest().id(), inspected.manifest().version());
                Files.move(target, recovery, StandardCopyOption.ATOMIC_MOVE);
                throw failure;
            }
            return new InstalledPlugin(installed, bundleHash, Instant.now());
        } finally {
            Files.deleteIfExists(archive);
            if (staging != null) {
                deleteOwnedTree(staging, stagingRoot);
            }
        }
    }

    /**
     * 在 owner-only staging 检查待审包，结束即清理临时副本；不注册 Catalog、不移动到安装根、不启动进程。
     *
     * @param zip 已上传附件的输入流，由调用方关闭
     * @param expectedSha256 附件固定摘要
     * @return 已验证来源和声明权限的只读快照，未签名不等于来源已确认
     * @throws IOException 摘要、ZIP、入口或签名无效
     */
    public PluginBundlePreview preview(InputStream zip, String expectedSha256) throws IOException {
        String expected = normalizedHash(expectedSha256);
        Path archive = Files.createTempFile(stagingRoot, ".plugin-preview-", ".zip");
        Path staging = null;
        try {
            if (!expected.equals(copyArchive(zip, archive))) {
                throw new IOException("plugin preview SHA-256 mismatch");
            }
            ZipSafetyIndex safety = ZipSafetyIndex.read(archive);
            staging = Files.createTempDirectory(stagingRoot, ".plugin-preview-");
            extract(archive, staging, safety);
            var plugin = catalog.loader().load(staging, true);
            var permissions = plugin.processes().stream()
                    .map(value -> {
                        var process = value.declaration();
                        return process.id() + " · " + process.kind() + "\n工作区读取：" + process.workspaceRead()
                                + " · 写入：" + process.workspaceWrite() + "\n网络 Broker："
                                + String.join("、", process.networkAllowlist())
                                + "\n超时：" + process.timeoutMillis() + "ms · 输出：" + process.outputLimitBytes() + "B";
                    })
                    .toList();
            boolean required = plugin.processes().stream()
                    .anyMatch(value -> value.declaration().workspaceRead()
                            || value.declaration().workspaceWrite()
                            || !value.declaration().networkAllowlist().isEmpty());
            return new PluginBundlePreview(
                    expected,
                    plugin.manifest().id(),
                    plugin.manifest().name(),
                    plugin.manifest().version(),
                    plugin.signatureVerified(),
                    plugin.manifest().signature() == null
                            ? ""
                            : plugin.manifest().signature().keyId(),
                    required,
                    permissions);
        } finally {
            Files.deleteIfExists(archive);
            if (staging != null) {
                deleteOwnedTree(staging, stagingRoot);
            }
        }
    }

    /**
     * 将已登记安装目录原子移入应用 Trash 并取消目录登记；调用方必须先停止该插件进程。
     *
     * @return 可供恢复的 Trash 路径
     * @throws java.io.IOException 路径不在受控根内、移动失败或目录发生竞态
     */
    public Path uninstall(String pluginId) throws IOException {
        LoadedPlugin plugin = catalog.require(pluginId);
        Path source = plugin.bundleRoot().toRealPath();
        if (!source.startsWith(pluginRoot)) {
            throw new IOException("refusing to uninstall a bundle outside the plugin root");
        }
        Path destination = trashPath(plugin.manifest().id(), plugin.manifest().version());
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        if (!catalog.unregister(pluginId)) {
            Files.move(destination, source, StandardCopyOption.ATOMIC_MOVE);
            throw new IOException("plugin catalog changed during uninstall");
        }
        return destination;
    }

    private Path trashPath(String id, String version) throws IOException {
        Path destination = trashRoot
                .resolve(id + "-" + version + "-"
                        + System.currentTimeMillis() + "-"
                        + UUID.randomUUID().toString().replace("-", ""))
                .normalize();
        if (!destination.startsWith(trashRoot)) {
            throw new IOException("invalid Trash target");
        }
        return destination;
    }

    private static String copyArchive(InputStream input, Path target) throws IOException {
        MessageDigest digest = sha256();
        long count = 0;
        try (OutputStream output =
                Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > MAX_ZIP_BYTES) {
                    throw new IOException("plugin ZIP exceeds 256 MiB");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void extract(Path archive, Path root, ZipSafetyIndex safety) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), Charset.forName("IBM437"))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                ZipSafetyIndex.Entry safe = safety.require(entry.getName());
                Path target = safePath(root, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    ownerOnly(target, true, false);
                    continue;
                }
                Files.createDirectories(target.getParent());
                ensureNoLinks(root, target.getParent());
                long written = 0;
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream output = Files.newOutputStream(
                                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        written += count;
                        if (written > safe.uncompressedSize()) {
                            throw new IOException("plugin ZIP entry exceeded declared size");
                        }
                        output.write(buffer, 0, count);
                    }
                }
                if (written != safe.uncompressedSize()) {
                    throw new IOException("plugin ZIP entry size mismatch: " + entry.getName());
                }
                ownerOnly(target, false, safe.executable());
            }
        }
        ensureNoLinks(root, root);
    }

    private static Path safePath(Path root, String name) throws IOException {
        if (name == null
                || name.isBlank()
                || name.indexOf('\0') >= 0
                || name.indexOf('\\') >= 0
                || name.startsWith("/")
                || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("plugin ZIP entry path is invalid");
        }
        Path relative = Path.of(name).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IOException("plugin ZIP entry escapes staging");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("plugin ZIP entry escapes staging");
        }
        return target;
    }

    private static void ensureNoLinks(Path root, Path path) throws IOException {
        Path current = root;
        Path relative = root.relativize(path);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("plugin staging contains a symbolic link");
            }
        }
    }

    private static Path secureDirectory(Path value) throws IOException {
        Path path = Objects.requireNonNull(value, "directory").toAbsolutePath().normalize();
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path);
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("plugin path is not a real directory: " + path);
        }
        ownerOnly(path, true, false);
        return path.toRealPath();
    }

    private static void ownerOnly(Path path, boolean directory, boolean executable) throws IOException {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
            return;
        }
        EnumSet<PosixFilePermission> permissions =
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (directory || executable) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        Files.setPosixFilePermissions(path, permissions);
    }

    private static void deleteOwnedTree(Path target, Path root) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root) || Files.isSymbolicLink(normalized)) {
            throw new IOException("refusing to clean an unsafe staging path");
        }
        if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String normalizedHash(String value) {
        String hash = value.toLowerCase(java.util.Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 must contain 64 lowercase hex characters");
        }
        return hash;
    }

    /**
     * 已完成原子安装的插件包与来源摘要。
     *
     * @param plugin 已验证且已登记的插件快照
     * @param bundleSha256 安装 ZIP 的 SHA-256，用于内容验证和去重
     * @param installedAt 安装完成时间
     */
    public record InstalledPlugin(LoadedPlugin plugin, String bundleSha256, Instant installedAt) {}

    /** Central-directory metadata unavailable through java.util.zip.ZipEntry. */
    private static final class ZipSafetyIndex {
        private static final int CENTRAL_SIGNATURE = 0x02014b50;
        private static final int EOCD_SIGNATURE = 0x06054b50;
        private final Map<String, Entry> entries;

        private ZipSafetyIndex(Map<String, Entry> entries) {
            this.entries = Map.copyOf(entries);
        }

        static ZipSafetyIndex read(Path archive) throws IOException {
            byte[] bytes = Files.readAllBytes(archive);
            int eocd = findEocd(bytes);
            ByteBuffer end = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int count = Short.toUnsignedInt(end.getShort(eocd + 10));
            long centralSize = Integer.toUnsignedLong(end.getInt(eocd + 12));
            long centralOffset = Integer.toUnsignedLong(end.getInt(eocd + 16));
            if (count == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL || count > MAX_FILES) {
                throw new IOException("ZIP64 or excessive plugin ZIP entry count is unsupported");
            }
            if (centralOffset + centralSize > eocd || centralOffset > Integer.MAX_VALUE) {
                throw new IOException("plugin ZIP central directory is invalid");
            }
            int offset = Math.toIntExact(centralOffset);
            long total = 0;
            HashMap<String, Entry> result = new HashMap<>();
            for (int index = 0; index < count; index++) {
                if (offset + 46 > bytes.length || end.getInt(offset) != CENTRAL_SIGNATURE) {
                    throw new IOException("plugin ZIP central directory is truncated");
                }
                int madeBy = Short.toUnsignedInt(end.getShort(offset + 4));
                int flags = Short.toUnsignedInt(end.getShort(offset + 8));
                long compressed = Integer.toUnsignedLong(end.getInt(offset + 20));
                long uncompressed = Integer.toUnsignedLong(end.getInt(offset + 24));
                int nameLength = Short.toUnsignedInt(end.getShort(offset + 28));
                int extraLength = Short.toUnsignedInt(end.getShort(offset + 30));
                int commentLength = Short.toUnsignedInt(end.getShort(offset + 32));
                long external = Integer.toUnsignedLong(end.getInt(offset + 38));
                int endOffset = offset + 46 + nameLength + extraLength + commentLength;
                if (endOffset > bytes.length || compressed == 0xffffffffL || uncompressed == 0xffffffffL) {
                    throw new IOException("ZIP64 plugin entries are unsupported");
                }
                Charset names = (flags & (1 << 11)) != 0 ? StandardCharsets.UTF_8 : Charset.forName("IBM437");
                String name = new String(bytes, offset + 46, nameLength, names);
                safePath(Path.of("/").toAbsolutePath().normalize(), name);
                int platform = madeBy >>> 8;
                int unixMode = platform == 3 ? (int) (external >>> 16) : 0;
                if ((unixMode & 0170000) == 0120000) {
                    throw new IOException("plugin ZIP symbolic links are forbidden: " + name);
                }
                if ((unixMode & 0170000) != 0 && (unixMode & 0170000) != 0100000 && (unixMode & 0170000) != 0040000) {
                    throw new IOException("plugin ZIP special files are forbidden: " + name);
                }
                if (uncompressed > 0 && (compressed == 0 || uncompressed > compressed * MAX_COMPRESSION_RATIO)) {
                    throw new IOException("plugin ZIP compression ratio exceeds 100:1");
                }
                total = Math.addExact(total, uncompressed);
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new IOException("plugin ZIP expands beyond 512 MiB");
                }
                if (result.put(name, new Entry(uncompressed, (unixMode & 0111) != 0)) != null) {
                    throw new IOException("plugin ZIP contains duplicate path: " + name);
                }
                offset = endOffset;
            }
            if (result.size() != count) {
                throw new IOException("plugin ZIP entry count mismatch");
            }
            return new ZipSafetyIndex(result);
        }

        Entry require(String name) throws IOException {
            Entry value = entries.get(name);
            if (value == null) {
                throw new IOException("ZIP entry is absent from central directory");
            }
            return value;
        }

        private static int findEocd(byte[] bytes) throws IOException {
            int minimum = Math.max(0, bytes.length - 65_557);
            ByteBuffer value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = bytes.length - 22; offset >= minimum; offset--) {
                if (value.getInt(offset) == EOCD_SIGNATURE) {
                    return offset;
                }
            }
            throw new IOException("plugin ZIP end record is missing");
        }

        private record Entry(long uncompressedSize, boolean executable) {}
    }
}
