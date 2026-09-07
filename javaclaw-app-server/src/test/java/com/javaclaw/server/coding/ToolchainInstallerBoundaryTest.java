package com.javaclaw.server.coding;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.nativehost.ManagedRuntimeDirectory;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;
import com.javaclaw.server.toolchain.ToolchainArtifactDownloadPort;

import static com.javaclaw.server.coding.ToolchainArchiveFixtures.file;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainInstallerBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void 首次使用清理崩溃残留但不把残留当作安装证据() throws Exception {
        Path data = dataRoot();
        Path staging = Files.createDirectories(data.resolve("coding/toolchains/staging"));
        Path abandoned = Files.createDirectory(staging.resolve("install-abandoned"));
        Files.writeString(abandoned.resolve("artifact.archive"), "partial");
        ToolchainFileEvidence.permissions(abandoned.resolve("artifact.archive"), false, true);
        var downloads = new AtomicInteger();
        var artifact = artifact(archive(), 100_000);
        var installer = installer(data, (ignored, target, token) -> downloads.incrementAndGet());
        assertThrows(IOException.class, () -> installer.verify(artifact, new CancellationSource()));
        assertEmpty(staging);
        assertEquals(0, downloads.get());
        assertFalse(Files.exists(installation(data, artifact)));
    }

    @Test
    void 应用目录被普通文件占用时失败且不修复或下载() throws Exception {
        Path data = dataRoot();
        Path occupied = Files.writeString(data.resolve("coding"), "retained");
        var calls = new AtomicInteger();
        var installer = installer(data, (artifact, target, token) -> calls.incrementAndGet());
        assertThrows(
                IOException.class, () -> installer.install(artifact(archive(), 100_000), new CancellationSource()));
        assertEquals("retained", Files.readString(occupied));
        assertEquals(0, calls.get());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 应用目录链接拒绝且陈旧安装链接清理不触碰目标() throws Exception {
        Path data = dataRoot();
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("retained"), "safe");
        Files.createSymbolicLink(data.resolve("coding"), outside);
        var installer = installer(data, (artifact, target, token) -> {
            throw new AssertionError("unexpected download");
        });
        assertThrows(
                IOException.class, () -> installer.install(artifact(archive(), 100_000), new CancellationSource()));
        assertEquals("safe", Files.readString(outside.resolve("retained")));
        Files.delete(data.resolve("coding"));
        byte[] archive = archive();
        var artifact = artifact(archive, archive.length);
        Files.createDirectories(installation(data, artifact).getParent());
        Files.createSymbolicLink(installation(data, artifact), outside);
        Path staging = Files.createDirectories(data.resolve("coding/toolchains/staging"));
        Files.createSymbolicLink(staging.resolve("stale-link"), outside);
        Path installed = installer(data, (ignored, target, token) -> target.write(archive))
                .install(artifact, new CancellationSource());
        assertEquals("node", Files.readString(installed.resolve("bin/node")));
        assertFalse(Files.isSymbolicLink(installed));
        assertEquals("safe", Files.readString(outside.resolve("retained")));
        assertEmpty(staging);
        ToolchainFileEvidence.delete(installed);
    }

    @Test
    void 真实稀疏文件大小耗尽缓存预算后不会再请求下载() throws Exception {
        Path data = dataRoot();
        byte[] archive = archive();
        var artifact = artifact(archive, archive.length);
        Path platform = Files.createDirectories(installation(data, artifact).getParent());
        Path retained = platform.resolve("existing-large-file");
        long size = 8L * 1024 * 1024 * 1024;
        // 只创建逻辑大小，实际写入一个字节；验证生产磁盘预算，不下载或分配 8 GiB 数据。
        try (var file = FileChannel.open(
                retained, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            file.position(size - 1);
            file.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertEquals(size, Files.size(retained));
        var calls = new AtomicInteger();
        var installer = installer(data, (ignored, target, token) -> calls.incrementAndGet());
        IOException failure =
                assertThrows(IOException.class, () -> installer.install(artifact, new CancellationSource()));
        assertTrue(failure.getMessage().contains("TOOLCHAIN_CACHE_FULL"));
        assertEquals(0, calls.get());
        assertEquals(size, Files.size(retained));
        Files.delete(retained);
    }

    @Test
    void 下载中取消删除临时归档且后续独立请求能完整重试() throws Exception {
        Path data = dataRoot();
        byte[] archive = archive();
        var artifact = artifact(archive, archive.length);
        var cancellation = new CancellationSource();
        var first = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var installer = installer(data, (ignored, target, token) -> {
            calls.incrementAndGet();
            if (first.getAndSet(false)) {
                target.write(archive, 0, archive.length / 2);
                cancellation.cancel("download interrupted");
            } else {
                target.write(archive);
            }
        });
        assertThrows(TurnCancelledException.class, () -> installer.install(artifact, cancellation));
        assertFalse(Files.exists(installation(data, artifact)));
        assertEmpty(data.resolve("coding/toolchains/staging"));
        Path installed = installer.install(artifact, new CancellationSource());
        assertEquals("node", Files.readString(installed.resolve("bin/node")));
        assertEquals(installed, installer.verify(artifact, new CancellationSource()));
        assertEquals(installed, installer.install(artifact, new CancellationSource()));
        assertEquals(2, calls.get());
        ToolchainFileEvidence.delete(installed);
    }

    @Test
    void 单字节超限和下载异常均不发布且释放staging() throws Exception {
        byte[] archive = archive();
        var artifact = artifact(archive, 4);
        Path data = dataRoot();
        var bounded = installer(data, (ignored, target, token) -> {
            for (byte value : archive) {
                target.write(value);
            }
        });
        IOException exceeded =
                assertThrows(IOException.class, () -> bounded.install(artifact, new CancellationSource()));
        assertTrue(exceeded.getMessage().contains("超过字节上限"));
        assertEmpty(data.resolve("coding/toolchains/staging"));
        var failing = installer(data, (ignored, target, token) -> {
            target.write(1);
            throw new IOException("fixture transport failed");
        });
        IOException failure =
                assertThrows(IOException.class, () -> failing.install(artifact, new CancellationSource()));
        assertEquals("fixture transport failed", failure.getMessage());
        assertEmpty(data.resolve("coding/toolchains/staging"));
        assertFalse(Files.exists(installation(data, artifact)));
    }

    @Test
    void 摘要正确的损坏归档或缺失发行入口均不能发布() throws Exception {
        Path data = dataRoot();
        for (byte[] bytes : List.of(
                new byte[] {1, 2, 3},
                Files.readAllBytes(ToolchainArchiveFixtures.zip(temporary, List.of(file("readme", "hello")))))) {
            var artifact = artifact(bytes, bytes.length);
            var installer = installer(data, (ignored, target, token) -> target.write(bytes));
            assertThrows(IOException.class, () -> installer.install(artifact, new CancellationSource()));
            assertFalse(Files.exists(installation(data, artifact)));
            assertEmpty(data.resolve("coding/toolchains/staging"));
        }
    }

    @Test
    void 已发布证据缺失损坏或超限必须重装且逐次重新核验() throws Exception {
        Path data = dataRoot();
        byte[] archive = archive();
        var artifact = artifact(archive, archive.length);
        var calls = new AtomicInteger();
        var installer = installer(data, (ignored, target, token) -> {
            calls.incrementAndGet();
            target.write(archive);
        });
        Path installed = installer.install(artifact, new CancellationSource());
        for (String damage : List.of("missing", "json", "hash", "oversized")) {
            Path manifest = installed.resolve(".javaclaw-installation.json");
            ToolchainFileEvidence.permissions(installed, true, false);
            ToolchainFileEvidence.permissions(manifest, false, false);
            switch (damage) {
                case "missing" -> Files.delete(manifest);
                case "json" -> Files.writeString(manifest, "{invalid");
                case "hash" ->
                    Files.writeString(manifest, "{\"artifactSha256\":\"" + "0".repeat(64) + "\",\"files\":{}}");
                default -> {
                    try (var file = FileChannel.open(manifest, StandardOpenOption.WRITE)) {
                        file.position(16L * 1024 * 1024);
                        file.write(ByteBuffer.wrap(new byte[] {1}));
                    }
                }
            }
            assertThrows(IOException.class, () -> installer.verify(artifact, new CancellationSource()), damage);
            assertEquals(installed, installer.install(artifact, new CancellationSource()));
            assertEquals(installed, installer.verify(artifact, new CancellationSource()));
        }
        assertEquals(5, calls.get());
        assertEmpty(data.resolve("coding/toolchains/staging"));
        ToolchainFileEvidence.delete(installed);
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void 已安装目录被植入链接后重新下载且不读取修改链接目标() throws Exception {
        Path data = dataRoot();
        byte[] archive = archive();
        var artifact = artifact(archive, archive.length);
        var calls = new AtomicInteger();
        var installer = installer(data, (ignored, target, token) -> {
            calls.incrementAndGet();
            target.write(archive);
        });
        Path installed = installer.install(artifact, new CancellationSource());
        Path outside = Files.writeString(temporary.resolve("outside-file"), "safe");
        ToolchainFileEvidence.permissions(installed, true, false);
        Files.createSymbolicLink(installed.resolve("injected"), outside);
        IOException invalid =
                assertThrows(IOException.class, () -> installer.verify(artifact, new CancellationSource()));
        assertTrue(invalid.getMessage().contains("不能包含链接"));
        assertEquals(installed, installer.install(artifact, new CancellationSource()));
        assertFalse(Files.exists(installed.resolve("injected")));
        assertEquals("safe", Files.readString(outside));
        assertEquals(2, calls.get());
        ToolchainFileEvidence.delete(installed);
    }

    private Path dataRoot() throws Exception {
        Path root = temporary.resolve("data-v6");
        ManagedRuntimeDirectory.prepare(root);
        return root;
    }

    private byte[] archive() throws Exception {
        return Files.readAllBytes(ToolchainArchiveFixtures.zip(temporary, List.of(file("bin/node", "node"))));
    }

    private static ToolchainArtifact artifact(byte[] archive, long downloadBytes) throws Exception {
        String sha = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(archive));
        return new ToolchainArtifact(
                new ToolchainRef(ToolchainKind.NODE, "1.0", sha),
                CodingToolchainCatalog.platform(),
                CodingToolchainCatalog.architecture(),
                URI.create("https://example.test/node.zip"),
                "zip",
                Map.of("node", "bin/node"),
                downloadBytes,
                "MIT");
    }

    private static Path installation(Path dataRoot, ToolchainArtifact artifact) {
        return dataRoot.resolve("coding/toolchains")
                .resolve(CodingToolchainCatalog.platform() + "-" + CodingToolchainCatalog.architecture())
                .resolve(artifact.reference().artifactSha256());
    }

    private static ToolchainInstaller installer(Path dataRoot, ToolchainArtifactDownloadPort downloads)
            throws Exception {
        return new ToolchainInstaller(dataRoot, downloads, new CanonicalJson());
    }

    private static void assertEmpty(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }
}
