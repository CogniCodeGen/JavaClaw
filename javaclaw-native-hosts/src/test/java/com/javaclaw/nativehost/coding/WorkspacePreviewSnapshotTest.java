package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspacePreviewSnapshotTest {
    @TempDir
    Path temporary;

    @Test
    void 原生隔离Worker只获得缓存写权限并拒绝读取兄弟目录() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
        Path allowed = Files.createDirectory(root.resolve("allowed"));
        Files.writeString(allowed.resolve("a.txt"), "allowed content");
        Files.writeString(root.resolve("outside.txt"), "private");
        Path cache = Files.createDirectory(temporary.resolve("cache")).toRealPath();
        var permission = new com.javaclaw.api.PermissionProfile(
                "preview",
                1,
                new com.javaclaw.api.FilePermission(java.util.List.of(allowed), java.util.List.of(), false, false),
                new com.javaclaw.api.NetworkPermission(java.util.Set.of(), java.util.Set.of(), true),
                new com.javaclaw.api.ProcessPermission(java.util.Set.of(), false, java.time.Duration.ofSeconds(30)),
                new com.javaclaw.api.ToolPermission(
                        java.util.Set.of(),
                        com.javaclaw.api.ToolRisk.READ_ONLY,
                        com.javaclaw.api.ApprovalRequirement.NONE),
                new com.javaclaw.api.ResourceLimits(512L * 1024 * 1024, 1024 * 1024, 4, 128));
        var result = WorkspacePreviewSnapshot.capture(
                root,
                permission,
                "allowed/a.txt",
                cache.resolve("snapshot"),
                1024,
                new com.javaclaw.api.CancellationSource());
        assertEquals(15, result.sizeBytes());
        assertEquals("allowed content", Files.readString(cache.resolve("snapshot")));
        assertThrows(
                SecurityException.class,
                () -> WorkspacePreviewSnapshot.capture(
                        root,
                        permission,
                        "outside.txt",
                        cache.resolve("outside"),
                        1024,
                        new com.javaclaw.api.CancellationSource()));
    }

    @Test
    void 固定目录句柄跨缓冲区流式复制并校验完整摘要() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
        byte[] bytes = new byte[256 * 1024 + 19];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index % 251);
        }
        Files.write(root.resolve("文件.txt"), bytes);
        Path destination = temporary.toRealPath().resolve("snapshot");
        try (WorkspaceFileTree tree = new WorkspaceFileTree(root)) {
            var result = WorkspacePreviewSnapshot.copy(tree, "文件.txt", destination, bytes.length);
            assertEquals(bytes.length, result.sizeBytes());
            assertEquals(
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    result.digest());
            assertArrayEquals(bytes, Files.readAllBytes(destination));
            assertThrows(
                    IOException.class, () -> WorkspacePreviewSnapshot.copy(tree, "文件.txt", destination, bytes.length));
        }
    }

    @Test
    void 超预算与跨根路径在创建目标前拒绝() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
        Files.writeString(root.resolve("large.txt"), "large");
        Path destination = temporary.toRealPath().resolve("snapshot");
        try (WorkspaceFileTree tree = new WorkspaceFileTree(root)) {
            assertThrows(IOException.class, () -> WorkspacePreviewSnapshot.copy(tree, "large.txt", destination, 1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorkspacePreviewSnapshot.copy(tree, "../secret", destination, 10));
            assertFalse(Files.exists(destination));
        }
    }
}
