package com.javaclaw.nativehost.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceDirectoryOperationsTest {
    @TempDir
    Path temporary;

    @Test
    void 检查后替换的用户目录被原样归还而非删除() throws Exception {
        Files.createDirectory(temporary.resolve("selected"));
        try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
            var result = WorkspaceDirectoryOperations.rmdir(tree, "selected", () -> {
                try {
                    Files.move(temporary.resolve("selected"), temporary.resolve("user-moved"));
                    Files.createDirectory(temporary.resolve("selected"));
                    Files.writeString(temporary.resolve("selected/keep.txt"), "并发用户内容");
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            assertEquals(Optional.of("DIRECTORY_CONFLICT"), result.failureCode());
            assertTrue(result.changes().isEmpty());
            assertEquals("并发用户内容", Files.readString(temporary.resolve("selected/keep.txt")));
            assertTrue(Files.isDirectory(temporary.resolve("user-moved")));
        }
    }

    @Test
    void 显式父目录开关和既存目录返回真实创建列表() throws Exception {
        try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
            var denied = WorkspaceDirectoryOperations.mkdir(tree, "a/b", false);
            assertTrue(denied.failureCode().isPresent());
            assertTrue(denied.changes().isEmpty());
            assertFalse(Files.exists(temporary.resolve("a")));
            var created = WorkspaceDirectoryOperations.mkdir(tree, "a/b", true);
            assertTrue(created.failureCode().isEmpty());
            assertEquals(
                    List.of(
                            new WorkspaceDirectoryResult.Change("a", "create"),
                            new WorkspaceDirectoryResult.Change("a/b", "create")),
                    created.changes());
            var unchanged = WorkspaceDirectoryOperations.mkdir(tree, "a/b", false);
            assertTrue(unchanged.changes().isEmpty());
            assertTrue(unchanged.failureCode().isEmpty());
        }
    }

    @Test
    void 空目录删除保留原对象且非空目录不会被递归删除() throws Exception {
        Files.createDirectory(temporary.resolve("empty"));
        Files.createDirectory(temporary.resolve("full"));
        Files.write(temporary.resolve("full/data.bin"), new byte[] {0, (byte) 255});
        try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
            var rejected = WorkspaceDirectoryOperations.rmdir(tree, "full");
            assertTrue(rejected.failureCode().isPresent());
            assertTrue(rejected.changes().isEmpty());
            assertTrue(Files.exists(temporary.resolve("full/data.bin")));
            var removed = WorkspaceDirectoryOperations.rmdir(tree, "empty");
            assertTrue(removed.failureCode().isEmpty());
            assertFalse(Files.exists(temporary.resolve("empty")));
            assertEquals(List.of(new WorkspaceDirectoryResult.Change("empty", "delete")), removed.changes());
            assertTrue(Files.isDirectory(
                    temporary.resolve(removed.recoveryPaths().getFirst()).resolve("original-directory")));
        }
    }

    @Test
    void 文件提交时父目录消失不能暗中重新建立目录() throws Exception {
        try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
            var writer = new WorkspacePatchWriter(tree);
            var prepared = writer.prepare(
                    List.of(new WorkspaceFileAccess.Edit(
                            "missing/file.bin", Optional.empty(), Optional.of(new byte[] {0, (byte) 255}))),
                    100);
            var result = writer.apply(prepared, false);
            assertEquals(WorkspaceFileAccess.Status.ROLLED_BACK, result.status());
            assertTrue(result.createdDirectories().isEmpty());
            assertFalse(Files.exists(temporary.resolve("missing")));
        }
    }

    @Test
    void 超出目录深度预算在创建第一个目录之前失败() throws Exception {
        String path = String.join("/", java.util.Collections.nCopies(201, "a"));
        try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
            var rejected = WorkspaceDirectoryOperations.mkdir(tree, path, true);
            assertEquals(Optional.of("DIRECTORY_CREATE_FAILED"), rejected.failureCode());
            assertTrue(rejected.changes().isEmpty());
            assertTrue(rejected.recoveryPaths().isEmpty());
            assertFalse(Files.exists(temporary.resolve("a")));
        }
    }

    @Test
    void 目录操作拒绝符号链接和保留恢复目录() throws Exception {
        Path outside = Files.createTempDirectory("javaclaw-directory-outside");
        try {
            Files.createSymbolicLink(temporary.resolve("link"), outside);
            try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
                assertTrue(WorkspaceDirectoryOperations.mkdir(tree, "link/child", true)
                        .failureCode()
                        .isPresent());
                assertTrue(WorkspaceDirectoryOperations.rmdir(tree, "link")
                        .failureCode()
                        .isPresent());
                assertThrows(
                        SecurityException.class,
                        () -> WorkspaceDirectoryOperations.rmdir(tree, ".javaclaw-recovery-owned"));
            }
            assertFalse(Files.exists(outside.resolve("child")));
        } finally {
            Files.delete(outside);
        }
    }
}
