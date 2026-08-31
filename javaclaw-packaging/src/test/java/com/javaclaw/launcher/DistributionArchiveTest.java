package com.javaclaw.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionArchiveTest {
    @TempDir
    Path temporary;

    @Test
    void archivesExecutableBytesReproduciblyWithoutOverwritingExistingTargets() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("distribution"));
        Path bin = Files.createDirectory(root.resolve("bin"));
        Path command = Files.writeString(bin.resolve("javaclaw"), "#!/bin/sh\nexit 0\n");
        assertTrue(command.toFile().setExecutable(true));
        Files.writeString(root.resolve("说明.txt"), "安装包中的 UTF-8 文档");
        Path archive = temporary.resolve("first.zip");
        DistributionArchive.write(root, archive);
        Path repeated = temporary.resolve("second.zip");
        DistributionArchive.write(root, repeated);
        assertEquals(BrowserBundleStager.sha256(archive), BrowserBundleStager.sha256(repeated));
        try (var zip = ZipFile.builder().setPath(archive).get()) {
            var entry = zip.getEntry("bin/javaclaw");
            assertEquals(UnixStat.FILE_FLAG | 0755, entry.getUnixMode());
            try (var input = zip.getInputStream(entry)) {
                assertEquals(Files.readString(command), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(zip.getEntry("说明.txt") != null);
        }
        assertThrows(java.io.IOException.class, () -> DistributionArchive.write(root, archive));
        assertEquals(BrowserBundleStager.sha256(repeated), BrowserBundleStager.sha256(archive));
    }

    @Test
    void retainsInternalLinksAndRejectsEscapingLinksWithoutPublishingPartialArchive() throws Exception {
        // Windows 安装包不依赖符号链接；POSIX 的 Chromium.framework 则必须保留相对链接语义。
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path root = Files.createDirectory(temporary.resolve("distribution"));
            assertThrows(java.io.IOException.class, () -> DistributionArchive.write(root, root.resolve("nested.zip")));
            return;
        }
        Path root = Files.createDirectory(temporary.resolve("distribution"));
        Files.writeString(root.resolve("payload"), "bytes");
        Files.createSymbolicLink(root.resolve("current"), Path.of("payload"));
        Path archive = temporary.resolve("valid.zip");
        DistributionArchive.write(root, archive);
        try (var zip = ZipFile.builder().setPath(archive).get()) {
            var entry = zip.getEntry("current");
            assertTrue(entry.isUnixSymlink());
            assertEquals("payload", zip.getUnixSymlink(entry));
        }
        Files.writeString(temporary.resolve("outside"), "not part of distribution");
        Files.createSymbolicLink(root.resolve("escape"), Path.of("../outside"));
        Path rejected = temporary.resolve("invalid.zip");
        assertThrows(java.io.IOException.class, () -> DistributionArchive.write(root, rejected));
        assertTrue(Files.notExists(rejected));
        try (var files = Files.list(temporary)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".javaclaw-archive-")));
        }
    }
}
