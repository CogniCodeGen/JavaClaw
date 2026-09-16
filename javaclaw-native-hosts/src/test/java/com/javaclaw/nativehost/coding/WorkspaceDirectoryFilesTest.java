package com.javaclaw.nativehost.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceDirectoryFilesTest {
    @TempDir
    Path temporary;

    @Test
    void 实际Worker返回目录创建失败无变化及空目录恢复材料() throws Exception {
        Path root = temporary.toRealPath();
        var access = new WorkspaceDirectoryFiles(root, permission(List.of(root), true));
        var token = new CancellationSource();
        var missingParent = access.mkdir("a/b", false, token);
        assertEquals(Optional.of("DIRECTORY_CREATE_FAILED"), missingParent.failureCode());
        assertTrue(missingParent.changes().isEmpty());
        assertFalse(Files.exists(root.resolve("a")));
        var created = access.mkdir("a/b", true, token);
        assertEquals(
                List.of(
                        new WorkspaceDirectoryResult.Change("a", "create"),
                        new WorkspaceDirectoryResult.Change("a/b", "create")),
                created.changes());
        assertTrue(created.failureCode().isEmpty());
        assertTrue(access.mkdir("a/b", false, token).changes().isEmpty());
        var nonempty = access.rmdir("a", token);
        assertEquals(Optional.of("DIRECTORY_NOT_EMPTY_OR_MISSING"), nonempty.failureCode());
        assertTrue(nonempty.changes().isEmpty());
        var removed = access.rmdir("a/b", token);
        assertEquals(List.of(new WorkspaceDirectoryResult.Change("a/b", "delete")), removed.changes());
        assertTrue(removed.failureCode().isEmpty());
        assertFalse(Files.exists(root.resolve("a/b")));
        Path recovery = root.resolve(removed.recoveryPaths().getFirst());
        assertTrue(Files.isDirectory(recovery.resolve("original-directory")));
        assertTrue(Files.readString(recovery.resolve("README.txt")).contains("path=a/b"));
    }

    @Test
    void 实际Worker字节提交与删除保留原始二进制且禁止补建父目录() throws Exception {
        Path root = temporary.toRealPath();
        var profile = permission(List.of(root), true);
        var files = new WorkspaceFileAccess(root, profile);
        var access = new WorkspaceDirectoryFiles(root, profile);
        var token = new CancellationSource();
        byte[] content = new byte[] {0, (byte) 255, (byte) 128, 10};
        var creation = files.preparePatch(
                List.of(new WorkspaceFileAccess.Edit("data.bin", Optional.empty(), Optional.of(content))), 100, token);
        var written = access.applyFiles(creation, token);
        assertEquals(WorkspaceFileAccess.Status.APPLIED, written.status());
        assertTrue(written.createdDirectories().isEmpty());
        assertArrayEquals(content, Files.readAllBytes(root.resolve("data.bin")));
        var deletion = files.preparePatch(
                List.of(new WorkspaceFileAccess.Edit(
                        "data.bin", Optional.of(WorkspaceFileAccess.hash(content)), Optional.empty())),
                100,
                token);
        assertEquals(
                WorkspaceFileAccess.Status.APPLIED,
                access.applyFiles(deletion, token).status());
        assertFalse(Files.exists(root.resolve("data.bin")));
        var missingParent = files.preparePatch(
                List.of(new WorkspaceFileAccess.Edit("missing/data.bin", Optional.empty(), Optional.of(content))),
                100,
                token);
        var rejected = access.applyFiles(missingParent, token);
        assertEquals(WorkspaceFileAccess.Status.ROLLED_BACK, rejected.status());
        assertTrue(rejected.createdDirectories().isEmpty());
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test
    void 目录和已准备字节补丁提交仍校验本次写入删除及恢复父目录权限() throws Exception {
        Path root = temporary.toRealPath();
        Path child = Files.createDirectory(root.resolve("child"));
        byte[] original = new byte[] {0, (byte) 255};
        Files.write(child.resolve("data.bin"), original);
        var token = new CancellationSource();
        var files = new WorkspaceFileAccess(root, permission(List.of(root), true));
        var deletion = files.preparePatch(
                List.of(new WorkspaceFileAccess.Edit(
                        "child/data.bin", Optional.of(WorkspaceFileAccess.hash(original)), Optional.empty())),
                100,
                token);
        var readOnly = new WorkspaceDirectoryFiles(root, permission(List.of(), false));
        assertThrows(SecurityException.class, () -> readOnly.mkdir("new", false, token));
        assertThrows(SecurityException.class, () -> readOnly.applyFiles(deletion, token));
        var noDelete = new WorkspaceDirectoryFiles(root, permission(List.of(root), false));
        assertThrows(SecurityException.class, () -> noDelete.rmdir("child", token));
        assertThrows(SecurityException.class, () -> noDelete.applyFiles(deletion, token));
        var childOnly = new WorkspaceDirectoryFiles(root, permission(List.of(child), true));
        // 删除目录的恢复材料位于父目录；不能借对子目录的授权写入未授权父目录。
        assertThrows(SecurityException.class, () -> childOnly.rmdir("child", token));
        assertArrayEquals(original, Files.readAllBytes(child.resolve("data.bin")));
        assertFalse(Files.exists(root.resolve("new")));
        try (var paths = Files.list(root)) {
            assertEquals(List.of(child), paths.toList());
        }
    }

    private PermissionProfile permission(List<Path> writeRoots, boolean allowDelete) throws Exception {
        return new PermissionProfile(
                "directory-facade-test",
                1,
                new FilePermission(List.of(temporary.toRealPath()), writeRoots, allowDelete, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(5)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, 1024 * 1024, 4, 128));
    }
}
