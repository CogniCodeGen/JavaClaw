package com.javaclaw.server.coding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.tukaani.xz.XZInputStream;

import com.javaclaw.api.CancellationToken;

/** 在私有 staging 内解包；拒绝路径穿越、特殊文件和冲突名称，内部文件链接物化为独立普通文件。 */
final class SafeToolchainArchive {
    private static final long MAXIMUM_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAXIMUM_FILE_BYTES = 512L * 1024 * 1024;
    private static final int MAXIMUM_ENTRIES = 100_000;
    private final Path root;
    private final CancellationToken cancellation;
    private final java.util.Map<String, String> names = new java.util.HashMap<>();
    private int entries;
    private final List<Link> links = new ArrayList<>();
    private long expanded;
    private final long maximumExpanded;

    SafeToolchainArchive(Path root, CancellationToken cancellation) {
        this(root, cancellation, MAXIMUM_EXPANDED_BYTES);
    }

    SafeToolchainArchive(Path root, CancellationToken cancellation, long availableBytes) {
        maximumExpanded = Math.min(MAXIMUM_EXPANDED_BYTES, availableBytes);
        this.root = root;
        this.cancellation = cancellation;
    }

    void extract(Path archive, String format) throws Exception {
        if (format.equals("zip")) {
            zip(archive);
        } else {
            try (InputStream raw = Files.newInputStream(archive);
                    InputStream decoded =
                            format.equals("tar.gz") ? new GZIPInputStream(raw) : new XZInputStream(raw, 65_536);
                    TarArchiveInputStream input = new TarArchiveInputStream(decoded)) {
                TarArchiveEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    tarEntry(entry, input);
                }
            }
        }
        materializeLinks();
    }

    private void zip(Path archive) throws Exception {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                // Commons Compress 会规范化部分 DOS 分隔符，原始名称也要拒绝歧义。
                String rawName = new String(entry.getRawName(), java.nio.charset.StandardCharsets.UTF_8);
                if (rawName.contains("\\") || rawName.indexOf('\0') >= 0) {
                    throw new IOException("ZIP 原始路径包含非法分隔符");
                }
                Path destination = destination(entry.getName(), entry.isDirectory());
                if (!zip.canReadEntryData(entry)) {
                    throw new IOException("不支持加密或未知 ZIP 特性");
                }
                int type = entry.getUnixMode() & 0170000;
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else if (entry.isUnixSymlink()) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        byte[] target = input.readNBytes(4097);
                        if (target.length > 4096) {
                            throw new IOException("制品链接目标过长");
                        }
                        links.add(new Link(
                                destination,
                                linkTarget(
                                        destination,
                                        new String(target, java.nio.charset.StandardCharsets.UTF_8),
                                        false)));
                    }
                } else if (type == 0 || type == 0100000) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        file(destination, input, entry.getSize(), entry.getUnixMode());
                    }
                } else {
                    throw new IOException("制品包含特殊 ZIP 文件");
                }
            }
        }
    }

    private void tarEntry(TarArchiveEntry entry, TarArchiveInputStream input) throws Exception {
        boolean directory = entry.getLinkFlag() == TarConstants.LF_DIR;
        Path destination = destination(entry.getName(), directory);
        if (entry.isSparse() || !input.canReadEntryData(entry)) {
            throw new IOException("制品不允许稀疏或未知 TAR 文件");
        }
        if (directory) {
            Files.createDirectories(destination);
        } else if (entry.isSymbolicLink() || entry.isLink()) {
            links.add(new Link(destination, linkTarget(destination, entry.getLinkName(), entry.isLink())));
        } else if (entry.getLinkFlag() == TarConstants.LF_NORMAL || entry.getLinkFlag() == TarConstants.LF_OLDNORM) {
            // isFile() 也可能接受未知或设备 typeflag；安装只允许明确的普通文件声明。
            file(destination, input, entry.getSize(), entry.getMode());
        } else {
            throw new IOException("制品包含设备、管道或其他特殊文件");
        }
    }

    private Path destination(String name, boolean directory) throws IOException {
        cancellation.throwIfCancelled();
        String normalized = name;
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        requireRelative(normalized);
        if (normalized.equals(".javaclaw-installation.json")) {
            throw new IOException("制品不能覆盖安装证据");
        }
        String folded = java.text.Normalizer.normalize(normalized, java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        String existing = names.putIfAbsent(folded, normalized);
        Path target = root.resolve(normalized).normalize();
        boolean repeatedDirectory =
                directory && normalized.equals(existing) && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        if (++entries > MAXIMUM_ENTRIES || (existing != null && !repeatedDirectory)) {
            throw new IOException("制品包含冲突路径或超过文件数量限制");
        }
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("制品路径越界");
        }
        return target;
    }

    private Path linkTarget(Path destination, String name, boolean hardLink) throws IOException {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw new IOException("制品链接目标必须是内部相对路径");
        }
        Path target = (hardLink ? root : destination.getParent()).resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("制品链接越界");
        }
        return target;
    }

    private static void requireRelative(String name) throws IOException {
        if (name.isBlank()
                || name.length() > 4096
                || name.startsWith("/")
                || name.contains("\\")
                || name.contains(":")
                || name.indexOf('\0') >= 0) {
            throw new IOException("制品文件名不是安全相对路径");
        }
        for (String component : name.split("/", -1)) {
            if (component.isBlank() || component.equals("..") || component.equals(".")) {
                throw new IOException("制品路径包含非法分量");
            }
        }
    }

    private void file(Path destination, InputStream input, long size, int mode) throws Exception {
        if (size < 0 || size > MAXIMUM_FILE_BYTES || expanded > maximumExpanded - size) {
            throw new IOException("制品展开大小超过限制");
        }
        expanded += size;
        Files.createDirectories(destination.getParent());
        try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0) {
                cancellation.throwIfCancelled();
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new IOException("制品文件提前结束");
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
        ToolchainFileEvidence.permissions(destination, (mode & 0111) != 0, false);
    }

    private void materializeLinks() throws Exception {
        for (int pass = 0; !links.isEmpty() && pass < 32; pass++) {
            var iterator = links.iterator();
            boolean progress = false;
            while (iterator.hasNext()) {
                Link link = iterator.next();
                if (Files.isRegularFile(link.target(), LinkOption.NOFOLLOW_LINKS)) {
                    try (InputStream input = Files.newInputStream(link.target())) {
                        file(
                                link.destination(),
                                input,
                                Files.size(link.target()),
                                Files.isExecutable(link.target()) ? 0100 : 0);
                    }
                    iterator.remove();
                    progress = true;
                }
            }
            if (!progress) {
                break;
            }
        }
        if (!links.isEmpty()) {
            throw new IOException("制品包含悬空、目录或循环链接");
        }
    }

    private record Link(Path destination, Path target) {}
}
