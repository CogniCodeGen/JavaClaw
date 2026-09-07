package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Edit;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceDirectoryBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void openedDirectoryCannotBeRedirectedByReplacingItsPathWithAnOutsideLink() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project")).toRealPath();
        Path original = Files.createDirectory(root.resolve("parent"));
        Files.writeString(original.resolve("value"), "authorized");
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("value"), "outside secret");
        try (var capability = WorkspaceDirectoryAccess.open(root);
                var parent = capability.directory("parent")) {
            Files.move(original, temporary.resolve("original-directory"));
            Files.createSymbolicLink(original, outside);
            try (var input = parent.openFile("value", Set.of(StandardOpenOption.READ))) {
                ByteBuffer bytes = ByteBuffer.allocate(100);
                input.read(bytes);
                assertEquals("authorized", new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8));
            }
            assertThrows(IOException.class, () -> capability.directory("parent"));
        }
    }

    @Test
    void renameWithoutReplacementPreservesBothExistingFiles() throws Exception {
        Files.writeString(temporary.resolve("proposal"), "proposal");
        Files.writeString(temporary.resolve("concurrent"), "user content");
        try (var directory = WorkspaceDirectoryAccess.open(temporary.toRealPath())) {
            assertThrows(IOException.class, () -> directory.moveNoReplace("proposal", directory, "concurrent"));
        }
        assertEquals("proposal", Files.readString(temporary.resolve("proposal")));
        assertEquals("user content", Files.readString(temporary.resolve("concurrent")));
    }

    @Test
    void successfulPatchRetainsWritesThroughAnAlreadyOpenOriginalInode() throws Exception {
        Path file = Files.writeString(temporary.resolve("value"), "before");
        try (var tree = new WorkspaceFileTree(temporary.toRealPath());
                var oldWriter = FileChannel.open(file, StandardOpenOption.WRITE)) {
            var writer = new WorkspacePatchWriter(tree);
            var prepared = writer.prepare(List.of(edit("value", "before", "after")), 1000);
            var result = writer.apply(prepared);
            assertEquals(Status.APPLIED, result.status());
            assertEquals(1, result.recoveryPaths().size());
            // 外部编辑器的旧 fd 并不遵守本 Worker 的摘要检查；原 inode 必须仍能找回。
            oldWriter.write(ByteBuffer.wrap("late edit".getBytes(StandardCharsets.UTF_8)));
            oldWriter.force(true);
            assertEquals("after", Files.readString(file));
            Path recovery = temporary.resolve(result.recoveryPaths().getFirst());
            assertEquals("late edit", Files.readString(recovery.resolve("original-0")));
            assertEquals(
                    List.of("value"),
                    tree.list("", 100).stream()
                            .map(WorkspaceFileAccess.Entry::path)
                            .toList());
            var access = new WorkspaceFileAccess(temporary.toRealPath());
            assertThrows(
                    SecurityException.class,
                    () -> access.read(
                            result.recoveryPaths().getFirst() + "/original-0", 100, new CancellationSource()));
        }
    }

    @Test
    void rollbackRestoresConcurrentFileWithoutOverwritingItWithThePreparedBeforeImage() throws Exception {
        Files.writeString(temporary.resolve("value"), "before");
        try (var tree = new WorkspaceFileTree(temporary.toRealPath());
                var transaction = new WorkspacePatchTransaction(tree)) {
            var prepared = new WorkspacePatchWriter(tree).prepare(List.of(edit("value", "before", "after")), 1000);
            transaction.apply(prepared.changes().getFirst());
            Files.writeString(temporary.resolve("value"), "concurrent edit");
            assertFalse(transaction.rollback());
            assertEquals("concurrent edit", Files.readString(temporary.resolve("value")));
            Path recovery = temporary.resolve(transaction.recoveryPaths().getFirst());
            assertEquals("before", Files.readString(recovery.resolve("original-0")));
            assertTrue(Files.exists(recovery.resolve("change-0.txt")));
        }
    }

    @Test
    void directoryEnumerationEnforcesItsEntryBudget() throws Exception {
        Files.createFile(temporary.resolve("one"));
        Files.createFile(temporary.resolve("two"));
        try (var directory = WorkspaceDirectoryAccess.open(temporary.toRealPath())) {
            assertThrows(IOException.class, () -> directory.names(1));
            assertEquals(2, directory.names(2).size());
        }
    }

    @Test
    void 删除固定目录内条目不接受跨段名称且不影响根外文件() throws Exception {
        Files.writeString(temporary.resolve("retained"), "outside child");
        try (var root = WorkspaceDirectoryAccess.open(temporary.toRealPath())) {
            root.createDirectory("child");
            try (var child = root.directory("child")) {
                try (var output =
                        child.openFile("value", Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                    output.write(ByteBuffer.wrap(new byte[] {1}));
                    child.force(output);
                }
                assertThrows(IllegalArgumentException.class, () -> child.deleteFile("../retained"));
                assertThrows(IllegalArgumentException.class, () -> child.deleteFile("nested/value"));
                assertThrows(IOException.class, () -> root.deleteDirectory("child"));
                child.deleteFile("value");
                assertTrue(child.names(1).isEmpty());
            }
            root.deleteDirectory("child");
            assertEquals(List.of("retained"), root.names(2));
        }
        assertEquals("outside child", Files.readString(temporary.resolve("retained")));
    }

    private static Edit edit(String path, String before, String after) {
        return new Edit(
                path,
                Optional.of(WorkspaceFileAccess.hash(before.getBytes(StandardCharsets.UTF_8))),
                Optional.of(after.getBytes(StandardCharsets.UTF_8)));
    }
}
