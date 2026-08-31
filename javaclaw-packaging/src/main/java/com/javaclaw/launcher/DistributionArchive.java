package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/** 发行 ZIP 写入器；保留 jlink/Chromium 执行位与包内相对符号链接，不跟随链接复制包外文件。 */
public final class DistributionArchive {
    private DistributionArchive() {}

    /** Maven 内部入口，参数依次是本次 distribution 根与尚不存在的 ZIP；失败只清理自己创建的临时 ZIP。 */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DistributionArchive <distribution> <archive.zip>");
        }
        write(Path.of(args[0]), Path.of(args[1]));
    }

    static void write(Path source, Path destination) throws IOException {
        Path root = source.toRealPath();
        Path target = destination.toAbsolutePath().normalize();
        if (target.startsWith(root) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("archive destination must be new and outside the distribution");
        }
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(path -> !path.equals(root))
                    .sorted()
                    .limit(100_001)
                    .toList();
        }
        if (files.size() > 100_000) {
            throw new IOException("distribution has too many entries");
        }
        Path staging = Files.createTempFile(target.getParent(), ".javaclaw-archive-", ".zip");
        try {
            try (var zip = new ZipArchiveOutputStream(staging)) {
                zip.setEncoding("UTF-8");
                zip.setUseZip64(Zip64Mode.AsNeeded);
                zip.setLevel(3);
                long bytes = 0;
                for (Path file : files) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    if (Files.isSymbolicLink(file)) {
                        Path link = Files.readSymbolicLink(file);
                        if (link.isAbsolute()
                                || !file.getParent().resolve(link).normalize().startsWith(root)
                                || !file.toRealPath().startsWith(root)) {
                            throw new IOException("distribution link escapes root: " + name);
                        }
                        byte[] contents = link.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                        var entry = new ZipArchiveEntry(name);
                        entry.setUnixMode(UnixStat.LINK_FLAG | 0777);
                        entry.setSize(contents.length);
                        entry.setTime(0);
                        zip.putArchiveEntry(entry);
                        zip.write(contents);
                        zip.closeArchiveEntry();
                    } else if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                        if (!file.toRealPath().startsWith(root)) {
                            throw new IOException("distribution directory escapes root");
                        }
                        var entry = new ZipArchiveEntry(name + "/");
                        entry.setUnixMode(UnixStat.DIR_FLAG | 0755);
                        entry.setTime(0);
                        zip.putArchiveEntry(entry);
                        zip.closeArchiveEntry();
                    } else {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                || (bytes += Files.size(file)) > 8L * 1024 * 1024 * 1024) {
                            throw new IOException("invalid distribution file or archive size exceeded");
                        }
                        var entry = new ZipArchiveEntry(name);
                        entry.setUnixMode(UnixStat.FILE_FLAG | (Files.isExecutable(file) ? 0755 : 0644));
                        entry.setSize(Files.size(file));
                        entry.setTime(0);
                        zip.putArchiveEntry(entry);
                        Files.copy(file, zip);
                        zip.closeArchiveEntry();
                    }
                }
            }
            // 以完整临时文件硬链接原子认领新名称；ATOMIC_MOVE 在部分平台会覆盖并发创建的用户文件。
            Files.createLink(target, staging);
        } finally {
            Files.deleteIfExists(staging);
        }
    }
}
