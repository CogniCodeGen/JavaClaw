package com.javaclaw.browser;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 将单个 Chrome 归档解压到新建的空目录，保留 macOS 应用包中的权限和内部相对链接。
 *
 * <p>全部路径先验证，文件写入结束后再创建符号链接，避免后续条目穿透链接写到目录外。
 * 失败时保留现场，由安装器统一清理临时目录。</p>
 */
final class ChromeArchiveExtractor {

    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 30_000;
    private static final int MAX_LINK_BYTES = 16_384;

    private ChromeArchiveExtractor() {
    }

    static void extract(Path archive, Path destination) throws IOException {
        requireNotInterrupted();
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Chrome 解压目标不是普通目录: " + destination);
        }
        Path root = destination.toRealPath();
        try (var children = Files.list(root)) {
            if (children.findAny().isPresent()) {
                throw new IOException("Chrome 解压目标必须为空目录: " + destination);
            }
        }
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            List<ArchiveEntry> entries = inspectEntries(zip, root);
            Map<Path, Path> links = readLinks(zip, entries);
            validateLinks(root, entries, links);
            long[] extracted = {0};
            for (ArchiveEntry entry : entries) {
                requireNotInterrupted();
                if (entry.kind() == Kind.LINK) continue;
                if (entry.kind() == Kind.DIRECTORY) {
                    ensureDirectories(root, entry.path());
                } else {
                    ensureDirectories(root, entry.path().getParent());
                    try (OutputStream output = Files.newOutputStream(entry.path(),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                        copyEntry(zip, entry, output, extracted);
                    }
                }
            }
            for (ArchiveEntry entry : entries) {
                requireNotInterrupted();
                if (entry.kind() == Kind.LINK) {
                    ensureDirectories(root, entry.path().getParent());
                    Files.createSymbolicLink(entry.path(), links.get(entry.path()));
                }
            }
            // 在实际文件系统上再次解析，覆盖大小写或 Unicode 等价名称引用同一链接的情况。
            // 此后不再写入归档内容，校验失败时也不会穿透新建的链接。
            validateLinkTargets(root, links, true);
            restorePermissions(root, entries);
        } catch (InvalidPathException invalid) {
            throw new IOException("Chrome 归档包含无效路径", invalid);
        }
    }

    private static List<ArchiveEntry> inspectEntries(ZipFile zip, Path root) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        Set<Path> paths = new HashSet<>();
        long declaredBytes = 0;
        var enumeration = zip.getEntries();
        while (enumeration.hasMoreElements()) {
            requireNotInterrupted();
            ZipArchiveEntry entry = enumeration.nextElement();
            if (entries.size() >= MAX_ENTRIES) {
                throw new IOException("Chrome 归档条目超过 " + MAX_ENTRIES);
            }
            String name = entry.getName();
            Path relative = requireRelativePath(name, false);
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root) || path.equals(root) || !paths.add(path)) {
                throw new IOException("Chrome 归档条目越界或重复: " + name);
            }
            Kind kind = kindOf(entry);
            if (!zip.canReadEntryData(entry)) {
                throw new IOException("Chrome 归档条目编码不受支持: " + name);
            }
            if (entry.getSize() < 0 || entry.getSize() > MAX_EXTRACTED_BYTES - declaredBytes) {
                throw new IOException("Chrome 归档解压大小无效或超过 2 GiB: " + name);
            }
            if (kind == Kind.DIRECTORY && entry.getSize() != 0) {
                throw new IOException("Chrome 归档目录包含数据: " + name);
            }
            declaredBytes += entry.getSize();
            entries.add(new ArchiveEntry(entry, path, kind));
        }
        if (entries.isEmpty()) throw new IOException("Chrome 归档为空");
        return entries;
    }

    private static Kind kindOf(ZipArchiveEntry entry) throws IOException {
        int type = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        if (type != 0 && type != UnixStat.FILE_FLAG
                && type != UnixStat.DIR_FLAG && type != UnixStat.LINK_FLAG) {
            throw new IOException("Chrome 归档包含不受支持的特殊条目: " + entry.getName());
        }
        if (entry.isUnixSymlink()) return Kind.LINK;
        if (type == UnixStat.DIR_FLAG || entry.isDirectory()) return Kind.DIRECTORY;
        return Kind.FILE;
    }

    private static Map<Path, Path> readLinks(ZipFile zip, List<ArchiveEntry> entries)
            throws IOException {
        Map<Path, Path> links = new HashMap<>();
        for (ArchiveEntry entry : entries) {
            if (entry.kind() != Kind.LINK) continue;
            if (entry.zipEntry().getSize() > MAX_LINK_BYTES) {
                throw new IOException("Chrome 归档符号链接目标过长: " + entry.zipEntry().getName());
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            copyEntry(zip, entry, content, new long[]{0});
            Path target = requireRelativePath(content.toString(StandardCharsets.UTF_8), true);
            links.put(entry.path(), target);
        }
        return links;
    }

    private static Path requireRelativePath(String value, boolean link) throws IOException {
        if (value.isEmpty() || value.startsWith("/") || value.contains("\\")
                || value.matches("^[A-Za-z]:.*")) {
            throw new IOException("Chrome 归档包含绝对路径或反斜杠路径: " + value);
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) throw new IOException("Chrome 归档路径必须相对: " + value);
        if (!link) {
            for (Path segment : path) {
                if (segment.toString().equals("..") || segment.toString().equals(".")) {
                    throw new IOException("Chrome 归档条目包含路径逃逸: " + value);
                }
            }
        }
        return path;
    }

    private static void validateLinks(
            Path root, List<ArchiveEntry> entries, Map<Path, Path> links) throws IOException {
        for (ArchiveEntry entry : entries) {
            for (Path parent = entry.path().getParent(); !parent.equals(root);
                    parent = parent.getParent()) {
                if (links.containsKey(parent)) {
                    throw new IOException("Chrome 归档条目穿透符号链接: " + entry.zipEntry().getName());
                }
            }
        }
        validateLinkTargets(root, links, false);
    }

    private static void validateLinkTargets(
            Path root, Map<Path, Path> links, boolean filesystem) throws IOException {
        for (var link : links.entrySet()) {
            requireNotInterrupted();
            Path cursor = link.getKey().getParent();
            ArrayDeque<String> remaining = new ArrayDeque<>();
            appendSegments(remaining, link.getValue());
            int followed = 0;
            while (!remaining.isEmpty()) {
                requireNotInterrupted();
                String segment = remaining.removeFirst();
                if (segment.equals(".")) continue;
                if (segment.equals("..")) {
                    if (cursor.equals(root)) {
                        throw new IOException("Chrome 归档符号链接越界: " + link.getKey());
                    }
                    cursor = cursor.getParent();
                    continue;
                }
                cursor = cursor.resolve(segment);
                Path target = filesystem
                        ? (Files.isSymbolicLink(cursor)
                            ? requireRelativePath(Files.readSymbolicLink(cursor).toString(), true) : null)
                        : links.get(cursor);
                if (target != null) {
                    if (++followed > 40) {
                        throw new IOException("Chrome 归档符号链接循环或层级过深: " + link.getKey());
                    }
                    ArrayDeque<String> expanded = new ArrayDeque<>();
                    appendSegments(expanded, target);
                    expanded.addAll(remaining);
                    remaining = expanded;
                    cursor = cursor.getParent();
                }
            }
        }
    }

    private static void appendSegments(ArrayDeque<String> segments, Path path) {
        for (Path segment : path) segments.addLast(segment.toString());
    }

    private static void ensureDirectories(Path root, Path directory) throws IOException {
        Path cursor = root;
        for (Path segment : root.relativize(directory)) {
            cursor = cursor.resolve(segment);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cursor);
            if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Chrome 解压路径不是普通目录: " + cursor);
            }
        }
    }

    private static void copyEntry(
            ZipFile zip, ArchiveEntry entry, OutputStream output, long[] extracted)
            throws IOException {
        CRC32 crc = new CRC32();
        long actual = 0;
        try (InputStream input = zip.getInputStream(entry.zipEntry())) {
            byte[] buffer = new byte[32_768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                requireNotInterrupted();
                if (count > entry.zipEntry().getSize() - actual
                        || count > MAX_EXTRACTED_BYTES - extracted[0]) {
                    throw new IOException("Chrome 归档条目超过声明大小: " + entry.zipEntry().getName());
                }
                actual += count;
                extracted[0] += count;
                crc.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (actual != entry.zipEntry().getSize() || crc.getValue() != entry.zipEntry().getCrc()) {
            throw new IOException("Chrome 归档条目长度或校验和不符: " + entry.zipEntry().getName());
        }
    }

    private static void restorePermissions(Path root, List<ArchiveEntry> entries) throws IOException {
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) == null) return;
        List<ArchiveEntry> ordered = entries.stream()
                .sorted(Comparator.comparingInt((ArchiveEntry entry) -> entry.path().getNameCount())
                        .reversed()).toList();
        PosixFilePermission[] bits = {
                PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_READ
        };
        for (ArchiveEntry entry : ordered) {
            requireNotInterrupted();
            if (entry.kind() == Kind.LINK || entry.zipEntry().getUnixMode() == 0) continue;
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            int mode = entry.zipEntry().getUnixMode();
            for (int bit = 0; bit < bits.length; bit++) {
                if ((mode & (1 << bit)) != 0) permissions.add(bits[bit]);
            }
            Files.setPosixFilePermissions(entry.path(), permissions);
        }
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Chrome 归档解压已中断");
        }
    }

    private enum Kind { FILE, DIRECTORY, LINK }

    private record ArchiveEntry(ZipArchiveEntry zipEntry, Path path, Kind kind) {
    }
}
