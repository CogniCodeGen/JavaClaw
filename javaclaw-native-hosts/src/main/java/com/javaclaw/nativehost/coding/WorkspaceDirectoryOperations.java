package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 固定 Worker 的非递归目录操作；删除保存原 inode，任何并发冲突不覆盖后来的用户对象。 */
final class WorkspaceDirectoryOperations {
    private WorkspaceDirectoryOperations() {}

    static WorkspaceDirectoryResult mkdir(WorkspaceFileTree tree, String path, boolean parents) {
        var changes = new ArrayList<WorkspaceDirectoryResult.Change>();
        try {
            WorkspaceFileProtocol.requireRelative(path, false);
            WorkspaceFileMetadata.requireVisible(path);
            if (path.split("/").length > 200) {
                throw new IOException("directory depth exceeds limit");
            }
            create(tree, path, parents, changes);
            return new WorkspaceDirectoryResult(changes, Optional.empty(), List.of());
        } catch (IOException | RuntimeException failure) {
            return new WorkspaceDirectoryResult(changes, Optional.of("DIRECTORY_CREATE_FAILED"), List.of());
        }
    }

    private static void create(
            WorkspaceFileTree tree, String path, boolean parents, List<WorkspaceDirectoryResult.Change> changes)
            throws IOException {
        String traversed = "";
        WorkspaceDirectoryAccess current = tree.directory("");
        try {
            for (Path segment : Path.of(path)) {
                String name = segment.toString();
                traversed = WorkspaceFileTree.child(traversed, name);
                WorkspaceDirectoryAccess next;
                try {
                    next = current.directory(name);
                } catch (NoSuchFileException absent) {
                    if (!parents && !traversed.equals(path)) {
                        throw absent;
                    }
                    current.createDirectory(name);
                    changes.add(new WorkspaceDirectoryResult.Change(traversed, "create"));
                    next = current.directory(name);
                }
                try {
                    current.close();
                } catch (IOException closeFailure) {
                    try {
                        next.close();
                    } catch (IOException nextFailure) {
                        closeFailure.addSuppressed(nextFailure);
                    }
                    throw closeFailure;
                }
                current = next;
            }
        } finally {
            current.close();
        }
    }

    static WorkspaceDirectoryResult rmdir(WorkspaceFileTree tree, String path) throws IOException {
        return rmdir(tree, path, () -> {});
    }

    /** 测试可在最终检查后替换目标对象；生产固定 Worker 不传入项目回调。 */
    static WorkspaceDirectoryResult rmdir(WorkspaceFileTree tree, String path, Runnable beforeCapture)
            throws IOException {
        WorkspaceFileProtocol.requireRelative(path, false);
        WorkspaceFileMetadata.requireVisible(path);
        String parentPath = WorkspaceFileTree.parent(path);
        String leaf = Path.of(path).getFileName().toString();
        try (var parent = tree.directory(parentPath)) {
            BasicFileAttributes before;
            try {
                before = parent.attributes(leaf);
                if (before.fileKey() == null) {
                    throw new IOException("directory identity is unavailable");
                }
                requireEmpty(parent, leaf);
            } catch (IOException | RuntimeException denied) {
                return new WorkspaceDirectoryResult(
                        List.of(), Optional.of("DIRECTORY_NOT_EMPTY_OR_MISSING"), List.of());
            }
            String recoveryName = ".javaclaw-recovery-" + UUID.randomUUID();
            parent.createDirectory(recoveryName);
            String recoveryPath = WorkspaceFileTree.child(parentPath, recoveryName);
            try (var recovery = parent.directory(recoveryName)) {
                manifest(recovery, path);
                beforeCapture.run();
                return capture(parent, leaf, before, recovery, path, recoveryPath);
            }
        }
    }

    private static WorkspaceDirectoryResult capture(
            WorkspaceDirectoryAccess parent,
            String leaf,
            BasicFileAttributes before,
            WorkspaceDirectoryAccess recovery,
            String path,
            String recoveryPath)
            throws IOException {
        parent.moveNoReplace(leaf, recovery, "original-directory");
        try {
            BasicFileAttributes captured = recovery.attributes("original-directory");
            if (!captured.isDirectory() || !Objects.equals(before.fileKey(), captured.fileKey())) {
                throw new IOException("directory identity changed before capture");
            }
            requireEmpty(recovery, "original-directory");
        } catch (IOException | RuntimeException conflict) {
            try {
                recovery.moveNoReplace("original-directory", parent, leaf);
                return new WorkspaceDirectoryResult(
                        List.of(), Optional.of("DIRECTORY_CONFLICT"), List.of(recoveryPath));
            } catch (IOException restoreFailed) {
                return new WorkspaceDirectoryResult(
                        List.of(new WorkspaceDirectoryResult.Change(path, "delete")),
                        Optional.of("RECOVERY_REQUIRED"),
                        List.of(recoveryPath));
            }
        }
        return new WorkspaceDirectoryResult(
                List.of(new WorkspaceDirectoryResult.Change(path, "delete")), Optional.empty(), List.of(recoveryPath));
    }

    private static void requireEmpty(WorkspaceDirectoryAccess parent, String name) throws IOException {
        try (var directory = parent.directory(name)) {
            if (!directory.names(1).isEmpty()) {
                throw new IOException("DIRECTORY_NOT_EMPTY");
            }
        }
    }

    private static void manifest(WorkspaceDirectoryAccess recovery, String path) throws IOException {
        byte[] content = ("JavaClaw 空目录删除恢复材料。原目录对象保留，不自动清理或重放。\npath=" + path + "\n").getBytes(StandardCharsets.UTF_8);
        try (var output =
                recovery.openFile("README.txt", Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
            recovery.force(output);
        }
    }
}
