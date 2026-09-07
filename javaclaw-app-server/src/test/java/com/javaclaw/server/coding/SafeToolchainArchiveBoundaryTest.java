package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnCancelledException;

import static com.javaclaw.server.coding.ToolchainArchiveFixtures.directory;
import static com.javaclaw.server.coding.ToolchainArchiveFixtures.file;
import static com.javaclaw.server.coding.ToolchainArchiveFixtures.link;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeToolchainArchiveBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void ZIP拒绝加密未知压缩与特殊Unix文件而不生成可执行入口() throws Exception {
        Path encrypted = ToolchainArchiveFixtures.zip(temporary, List.of(file("bin/node", "node")));
        ToolchainArchiveFixtures.centralField(encrypted, 8, 1, true);
        reject(encrypted, "zip", "不支持加密或未知 ZIP 特性");
        Path unknown = ToolchainArchiveFixtures.zip(temporary, List.of(file("bin/node", "node")));
        ToolchainArchiveFixtures.centralField(unknown, 10, 99, true);
        reject(unknown, "zip", "不支持加密或未知 ZIP 特性");
        Path fifo = ToolchainArchiveFixtures.zip(
                temporary, List.of(new ToolchainArchiveFixtures.Entry("bin/pipe", TarConstants.LF_FIFO, "", "", 0644)));
        reject(fifo, "zip", "特殊 ZIP 文件");
    }

    @Test
    void ZIP链接物化为独立文件并拒绝超长或危险目标() throws Exception {
        var entries = List.of(directory("bin/"), file("bin/node", "node"), link("bin/alias", "node", false));
        Path root = extract(ToolchainArchiveFixtures.zip(temporary, entries), "zip");
        assertEquals("node", Files.readString(root.resolve("bin/alias")));
        assertFalse(Files.isSymbolicLink(root.resolve("bin/alias")));
        Files.writeString(root.resolve("bin/node"), "changed");
        assertEquals("node", Files.readString(root.resolve("bin/alias")));
        for (String target : List.of("x".repeat(4097), "", "/absolute", "a\\b", "C:drive", "../..")) {
            Path archive = ToolchainArchiveFixtures.zip(temporary, List.of(link("bin/alias", target, false)));
            reject(archive, "zip", target.length() > 4096 ? "过长" : target.equals("../..") ? "越界" : "内部相对路径");
        }
    }

    @Test
    void TAR硬链接和多步前向链接保持内部含义且支持XZ() throws Exception {
        var entries = List.of(
                directory("././bin/"),
                directory("bin/"),
                link("bin/first", "second", false),
                link("bin/second", "node", false),
                link("bin/hard", "bin/node", true),
                new ToolchainArchiveFixtures.Entry("bin/node", TarConstants.LF_NORMAL, "node", "", 0755),
                new ToolchainArchiveFixtures.Entry("bin/legacy", TarConstants.LF_OLDNORM, "node", "", 0644));
        Path root = extract(ToolchainArchiveFixtures.tar(temporary, entries, true), "tar.xz");
        for (String name : List.of("first", "second", "hard", "node", "legacy")) {
            assertEquals("node", Files.readString(root.resolve("bin/" + name)));
            assertFalse(Files.isSymbolicLink(root.resolve("bin/" + name)));
        }
        Files.writeString(root.resolve("bin/node"), "changed");
        assertEquals("node", Files.readString(root.resolve("bin/hard")));
    }

    @Test
    void TAR悬空目录循环及过深依赖链均不能成为完整安装() throws Exception {
        for (var entries : List.of(
                List.of(link("alias", "missing", false)),
                List.of(directory("folder/"), link("alias", "folder", false)),
                List.of(link("first", "second", false), link("second", "first", false)),
                List.of(link("alias", ".", true)))) {
            reject(ToolchainArchiveFixtures.tar(temporary, entries, false), "tar.gz", "链接");
        }
        var chain = new ArrayList<ToolchainArchiveFixtures.Entry>();
        for (int index = 0; index < 33; index++) {
            chain.add(link("alias-" + index, index == 32 ? "node" : "alias-" + (index + 1), false));
        }
        chain.add(file("node", "node"));
        reject(ToolchainArchiveFixtures.tar(temporary, chain, false), "tar.gz", "循环链接");
    }

    @Test
    void TAR设备管道和GNU稀疏声明在提取正文前拒绝() throws Exception {
        for (byte kind : new byte[] {
            TarConstants.LF_FIFO,
            TarConstants.LF_CHR,
            TarConstants.LF_BLK,
            TarConstants.LF_GNUTYPE_SPARSE,
            TarConstants.LF_CONTIG,
            (byte) 'Z'
        }) {
            Path archive = ToolchainArchiveFixtures.tar(
                    temporary, List.of(new ToolchainArchiveFixtures.Entry("bin/special", kind, "", "", 0644)), false);
            reject(archive, "tar.gz", kind == TarConstants.LF_GNUTYPE_SPARSE ? "稀疏或未知" : "特殊文件");
            Path disguisedDirectory = ToolchainArchiveFixtures.tar(
                    temporary, List.of(new ToolchainArchiveFixtures.Entry("bin/special/", kind, "", "", 0644)), false);
            reject(disguisedDirectory, "tar.gz", kind == TarConstants.LF_GNUTYPE_SPARSE ? "稀疏或未知" : "特殊文件");
        }
    }

    @Test
    void 名称歧义重复文件和目录文件冲突不能覆盖前一条目() throws Exception {
        for (String name : List.of(" ", "./", "x".repeat(4097), "bin//node", "bin/./node", "bin/ /node", "bin\0node")) {
            reject(
                    ToolchainArchiveFixtures.zip(temporary, List.of(file(name, "node"))),
                    "zip",
                    name.indexOf(0) >= 0 ? "非法" : "制品");
        }
        for (var entries : List.of(
                List.of(file("node", "first"), file("node", "second")),
                List.of(file("node", "first"), directory("node/")),
                List.of(directory("Bin/"), directory("bin/")),
                List.of(file("caf\u00e9", "first"), file("cafe\u0301", "second")))) {
            reject(ToolchainArchiveFixtures.tar(temporary, entries, false), "tar.gz", "冲突路径");
        }
    }

    @Test
    void 声明过大或短于声明的ZIP与截断TAR均失败() throws Exception {
        Path oversized = ToolchainArchiveFixtures.zip(temporary, List.of(file("node", "node")));
        ToolchainArchiveFixtures.centralField(oversized, 24, 512 * 1024 * 1024 + 1, false);
        reject(oversized, "zip", "展开大小超过限制");
        Path shortZip = ToolchainArchiveFixtures.zip(temporary, List.of(file("node", "node")));
        ToolchainArchiveFixtures.centralField(shortZip, 24, 10, false);
        reject(shortZip, "zip", "提前结束");
        reject(ToolchainArchiveFixtures.truncatedTar(temporary), "tar.gz", "");
    }

    @Test
    void 无Unix权限的ZIP普通文件兼容且重复目录仍受条目总量限制() throws Exception {
        Path archive = ToolchainArchiveFixtures.zip(temporary, List.of(file("node", "node")));
        ToolchainArchiveFixtures.centralField(archive, 38, 0, false);
        assertEquals("node", Files.readString(extract(archive, "zip").resolve("node")));
        // 重复合法目录避免制造大量宿主文件，但归档总条目仍必须消耗解析预算。
        var repeated = java.util.Collections.nCopies(100_001, directory("bin/"));
        reject(ToolchainArchiveFixtures.tar(temporary, repeated, false), "tar.gz", "超过文件数量限制");
    }

    @Test
    void 复制链接同样计入展开预算并在文件流中响应取消() throws Exception {
        Path archive =
                ToolchainArchiveFixtures.zip(temporary, List.of(file("node", "node"), link("alias", "node", false)));
        Path root = Files.createTempDirectory(temporary, "budget-");
        IOException failure = assertThrows(
                IOException.class,
                () -> new SafeToolchainArchive(root, new CancellationSource(), 7).extract(archive, "zip"));
        assertTrue(failure.getMessage().contains("展开大小超过限制"));
        Path large = ToolchainArchiveFixtures.zip(temporary, List.of(file("node", "x".repeat(131_072))));
        var checks = new AtomicInteger();
        CancellationToken token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return checks.incrementAndGet() > 2;
            }

            @Override
            public Optional<String> reason() {
                return Optional.of("归档流读取中取消");
            }
        };
        Path cancelled = Files.createTempDirectory(temporary, "cancel-");
        assertThrows(
                TurnCancelledException.class, () -> new SafeToolchainArchive(cancelled, token).extract(large, "zip"));
        assertEquals(65_536, Files.size(cancelled.resolve("node")));
    }

    private Path extract(Path archive, String format) throws Exception {
        Path root = Files.createTempDirectory(temporary, "extract-");
        new SafeToolchainArchive(root, new CancellationSource()).extract(archive, format);
        return root;
    }

    private void reject(Path archive, String format, String expected) throws Exception {
        Path root = Files.createTempDirectory(temporary, "reject-");
        IOException failure = assertThrows(
                IOException.class,
                () -> new SafeToolchainArchive(root, new CancellationSource()).extract(archive, format));
        assertTrue(failure.getMessage().contains(expected), failure::toString);
        assertFalse(Files.exists(root.resolve(".javaclaw-installation.json")));
    }
}
