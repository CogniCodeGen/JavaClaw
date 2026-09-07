package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnCancelledException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeToolchainArchiveTest {
    @TempDir
    Path temporary;

    @Test
    void 拒绝越界路径保留安装根之外的文件() throws Exception {
        for (String path : List.of("../outside", "/absolute", "C:/drive", "dir\\file", "a/../../b")) {
            Path root = Files.createTempDirectory(temporary, "extract-");
            Path archive = zip(List.of(path));
            assertThrows(
                    IOException.class,
                    () -> new SafeToolchainArchive(root, new CancellationSource()).extract(archive, "zip"),
                    path);
        }
        assertFalse(Files.exists(temporary.resolve("outside")));
    }

    @Test
    void 拒绝跨平台大小写路径冲突及证据伪造() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("case"));
        Path archive = zip(List.of("bin/Node", "bin/node"));
        assertThrows(
                IOException.class,
                () -> new SafeToolchainArchive(root, new CancellationSource()).extract(archive, "zip"));
        Path other = Files.createDirectory(temporary.resolve("manifest"));
        Path forged = zip(List.of(".javaclaw-installation.json"));
        assertThrows(
                IOException.class,
                () -> new SafeToolchainArchive(other, new CancellationSource()).extract(forged, "zip"));
    }

    @Test
    void 展开大小和取消在流式写入前约束() throws Exception {
        Path archive = zip(List.of("bin/node"));
        Path root = Files.createDirectory(temporary.resolve("small"));
        assertThrows(
                IOException.class,
                () -> new SafeToolchainArchive(root, new CancellationSource(), 1).extract(archive, "zip"));
        var cancelled = new CancellationSource();
        cancelled.cancel("测试取消");
        Path other = Files.createDirectory(temporary.resolve("cancelled"));
        assertThrows(
                TurnCancelledException.class, () -> new SafeToolchainArchive(other, cancelled).extract(archive, "zip"));
    }

    @Test
    void 内部文件符号链接物化但越界链接明确拒绝() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("links"));
        Path archive = tarLink("node", "bin/node");
        new SafeToolchainArchive(root, new CancellationSource()).extract(archive, "tar.gz");
        assertEquals("node", Files.readString(root.resolve("bin/alias")));
        assertFalse(Files.isSymbolicLink(root.resolve("bin/alias")));
        Path other = Files.createDirectory(temporary.resolve("escaping"));
        Path unsafe = tarLink("../../outside", "bin/node");
        assertThrows(
                IOException.class,
                () -> new SafeToolchainArchive(other, new CancellationSource()).extract(unsafe, "tar.gz"));
    }

    private Path zip(List<String> paths) throws Exception {
        Path archive = Files.createTempFile(temporary, "fixture-", ".zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String path : paths) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
        }
        return archive;
    }

    private Path tarLink(String target, String regular) throws Exception {
        Path archive = Files.createTempFile(temporary, "fixture-", ".tar.gz");
        try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(archive));
                var tar = new TarArchiveOutputStream(gzip)) {
            var file = new TarArchiveEntry(regular);
            file.setSize(4);
            tar.putArchiveEntry(file);
            tar.write("node".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tar.closeArchiveEntry();
            var link = new TarArchiveEntry("bin/alias", TarConstants.LF_SYMLINK);
            link.setLinkName(target);
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
        return archive;
    }
}
