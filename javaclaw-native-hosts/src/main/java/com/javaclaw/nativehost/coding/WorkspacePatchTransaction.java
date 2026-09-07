package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Change;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Snapshot;

/** 保存实际被替换的 inode；POSIX 修改使用原子交换，无法交换的平台使用有空窗的不可覆盖移动。 */
final class WorkspacePatchTransaction implements AutoCloseable {
    private final WorkspaceFileTree tree;
    private final Runnable beforeExchange;
    private final Map<String, WorkspaceDirectoryAccess> parents = new LinkedHashMap<>();
    private final List<Mutation> mutations = new ArrayList<>();
    private final List<String> created = new ArrayList<>();
    private WorkspaceDirectoryAccess recovery;
    private String recoveryPath;
    private int event;
    private boolean uncertainExchange;

    WorkspacePatchTransaction(WorkspaceFileTree tree) {
        this(tree, () -> {});
    }

    /** 包内故障注入在最终预检后、原子交换前运行；生产固定 Worker 使用无操作回调。 */
    WorkspacePatchTransaction(WorkspaceFileTree tree, Runnable beforeExchange) {
        this.tree = tree;
        this.beforeExchange = beforeExchange;
    }

    void apply(Change change) throws IOException {
        String path = change.before().path();
        String parentPath = parent(path);
        WorkspaceDirectoryAccess directory = parentDirectory(parentPath);
        openRecovery(directory, parentPath);
        Mutation mutation = new Mutation(
                change,
                directory,
                leaf(path),
                mutations.size(),
                change.before().exists() && change.after().exists() && directory.supportsExchange());
        mutations.add(mutation);
        writeManifest(mutation);
        if (change.after().exists()) {
            writeBytes(recovery, mutation.proposal(), change.after().content());
        }
        requireCurrent(directory, mutation.leaf, change.before());
        if (mutation.atomic) {
            exchange(mutation);
            return;
        }
        if (change.before().exists()) {
            directory.moveNoReplace(mutation.leaf, recovery, mutation.original());
            mutation.removed = true;
            record(mutation, "original-captured");
            requireCurrent(recovery, mutation.original(), change.before());
            if (change.after().exists()) {
                recovery.copyAccess(mutation.original(), recovery, mutation.proposal());
            }
        }
        if (change.after().exists()) {
            recovery.moveNoReplace(mutation.proposal(), directory, mutation.leaf);
        }
        mutation.installed = true;
        record(mutation, "applied-original-inode-retained");
    }

    private void exchange(Mutation mutation) throws IOException {
        mutation.directory.copyAccess(mutation.leaf, recovery, mutation.original());
        beforeExchange.run();
        mutation.directory.exchange(mutation.leaf, recovery, mutation.original());
        mutation.removed = true;
        mutation.installed = true;
        // 一旦交换成功，任何未完成的核验都必须停止，不再自动交换并发的当前路径。
        uncertainExchange = true;
        record(mutation, "atomic-exchange-original-captured");
        requireCurrent(recovery, mutation.original(), mutation.change.before());
        record(mutation, "applied-original-inode-retained");
        uncertainExchange = false;
    }

    boolean rollback() {
        if (uncertainExchange) {
            return false;
        }
        boolean restored = created.isEmpty();
        for (Mutation mutation : mutations.reversed()) {
            try {
                if (mutation.installed) {
                    rollbackInstalled(mutation);
                } else if (mutation.removed) {
                    recovery.moveNoReplace(mutation.original(), mutation.directory, mutation.leaf);
                }
                record(mutation, "rolled-back");
            } catch (IOException | RuntimeException conflict) {
                restored = false;
                try {
                    record(mutation, "recovery-required");
                } catch (IOException recordFailure) {
                    conflict.addSuppressed(recordFailure);
                }
                break;
            }
        }
        return restored;
    }

    private void rollbackInstalled(Mutation mutation) throws IOException {
        Snapshot after = mutation.change.after();
        requireCurrent(mutation.directory, mutation.leaf, after);
        if (mutation.atomic) {
            beforeExchange.run();
            mutation.directory.exchange(mutation.leaf, recovery, mutation.original());
            record(mutation, "rollback-exchanged-current-captured");
            requireCurrent(recovery, mutation.original(), after);
            return;
        }
        if (after.exists()) {
            mutation.directory.moveNoReplace(mutation.leaf, recovery, mutation.applied());
            try {
                requireCurrent(recovery, mutation.applied(), after);
            } catch (IOException conflict) {
                // 被移走的是实际当前 inode。摘要冲突时无覆盖地还给项目；失败则仍保留于恢复目录。
                recovery.moveNoReplace(mutation.applied(), mutation.directory, mutation.leaf);
                throw conflict;
            }
        }
        if (mutation.removed) {
            recovery.moveNoReplace(mutation.original(), mutation.directory, mutation.leaf);
        }
    }

    List<String> recoveryPaths() {
        return recoveryPath == null ? List.of() : List.of(recoveryPath);
    }

    private WorkspaceDirectoryAccess parentDirectory(String relative) throws IOException {
        WorkspaceDirectoryAccess existing = parents.get(relative);
        if (existing != null) {
            return existing;
        }
        WorkspaceDirectoryAccess current = tree.directory("");
        String traversed = "";
        try {
            if (!relative.isEmpty()) {
                for (Path segment : Path.of(relative)) {
                    String name = segment.toString();
                    traversed = traversed.isEmpty() ? name : traversed + "/" + name;
                    WorkspaceDirectoryAccess next = openOrCreate(current, name, traversed);
                    current.close();
                    current = next;
                }
            }
            parents.put(relative, current);
            return current;
        } catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }

    private WorkspaceDirectoryAccess openOrCreate(WorkspaceDirectoryAccess parent, String leaf, String relative)
            throws IOException {
        try {
            return parent.directory(leaf);
        } catch (NoSuchFileException missing) {
            parent.createDirectory(leaf);
            created.add(relative);
            return parent.directory(leaf);
        }
    }

    private void openRecovery(WorkspaceDirectoryAccess parent, String parentPath) throws IOException {
        if (recovery != null) {
            return;
        }
        String name = ".javaclaw-recovery-" + UUID.randomUUID();
        parent.createDirectory(name);
        recoveryPath = parentPath.isEmpty() ? name : parentPath + "/" + name;
        recovery = parent.directory(name);
        writeBytes(
                recovery,
                "README.txt",
                ("JavaClaw 恢复材料。原 inode 保留用于处理并发写入；不得自动删除或重放。\n"
                                + "POSIX 修改使用原子交换；Windows 修改为两阶段移动。均非摘要原子 CAS 或跨系统事务。\n"
                                + "change-N.txt 中 pathBase64 为 UTF-8 相对路径；event-N.txt 按调用顺序记录状态。\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void writeManifest(Mutation mutation) throws IOException {
        Change change = mutation.change;
        String encoded =
                Base64.getUrlEncoder().encodeToString(change.before().path().getBytes(StandardCharsets.UTF_8));
        String content = "pathBase64=" + encoded + "\natomicExchange=" + mutation.atomic
                + "\nbeforeExists=" + change.before().exists()
                + "\nbeforeSha256=" + change.before().sha256() + "\nafterExists="
                + change.after().exists()
                + "\nafterSha256=" + change.after().sha256() + "\noriginal=" + mutation.original()
                + "\nproposal=" + mutation.proposal() + "\napplied=" + mutation.applied() + "\n";
        writeBytes(recovery, "change-" + mutation.index + ".txt", content.getBytes(StandardCharsets.UTF_8));
        record(mutation, "prepared");
    }

    private void record(Mutation mutation, String state) throws IOException {
        writeBytes(
                recovery,
                "event-" + event++ + ".txt",
                ("change=" + mutation.index + "\nstate=" + state + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(WorkspaceDirectoryAccess directory, String leaf, byte[] content) throws IOException {
        try (var output = directory.openFile(leaf, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
            directory.force(output);
        }
    }

    static void requireCurrent(WorkspaceDirectoryAccess directory, String leaf, Snapshot expected) throws IOException {
        Snapshot actual = WorkspaceFileTree.snapshot(directory, leaf, expected.path(), WorkspaceFileAccess.MAX_BYTES);
        if (actual.exists() != expected.exists() || !actual.sha256().equals(expected.sha256())) {
            throw new IOException("FILE_DIGEST_CONFLICT: Workspace file conflict: " + expected.path());
        }
    }

    private static String parent(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    private static String leaf(String relative) {
        return relative.substring(relative.lastIndexOf('/') + 1);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        List<WorkspaceDirectoryAccess> owned = new ArrayList<>(parents.values());
        if (recovery != null) {
            owned.add(recovery);
        }
        for (WorkspaceDirectoryAccess directory : owned.reversed()) {
            try {
                directory.close();
            } catch (IOException problem) {
                if (failure == null) {
                    failure = problem;
                } else {
                    failure.addSuppressed(problem);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class Mutation {
        private final Change change;
        private final WorkspaceDirectoryAccess directory;
        private final String leaf;
        private final int index;
        private final boolean atomic;
        private boolean removed;
        private boolean installed;

        private Mutation(Change change, WorkspaceDirectoryAccess directory, String leaf, int index, boolean atomic) {
            this.change = change;
            this.directory = directory;
            this.leaf = leaf;
            this.index = index;
            this.atomic = atomic;
        }

        private String original() {
            return "original-" + index;
        }

        private String proposal() {
            return atomic ? original() : "proposal-" + index;
        }

        private String applied() {
            return "applied-" + index;
        }
    }
}
