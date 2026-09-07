package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Change;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Edit;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.PatchResult;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.PreparedPatch;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Snapshot;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Status;

/** Worker 内的补丁协调；保留实际 inode，拒绝覆盖并发新目标，不声称摘要检查与 rename 是原子 CAS。 */
final class WorkspacePatchWriter {
    private final WorkspaceFileTree tree;

    WorkspacePatchWriter(WorkspaceFileTree tree) {
        this.tree = tree;
    }

    PreparedPatch prepare(List<Edit> edits, int maximum) throws IOException {
        ArrayList<Change> changes = new ArrayList<>();
        long bytes = 0;
        for (Edit edit : edits) {
            Snapshot before = tree.snapshot(edit.path(), Math.toIntExact(maximum - bytes));
            if (before.exists() != edit.expectedSha256().isPresent()
                    || before.exists()
                            && !before.sha256().equals(edit.expectedSha256().orElseThrow())) {
                throw new IOException(
                        "FILE_DIGEST_CONFLICT: Workspace file changed before preparation: " + edit.path());
            }
            byte[] afterBytes = edit.content().orElseGet(() -> new byte[0]);
            Snapshot after = new Snapshot(
                    edit.path(),
                    edit.content().isPresent(),
                    edit.content().isPresent() ? WorkspaceFileAccess.hash(afterBytes) : "",
                    afterBytes);
            bytes += before.content().length + (long) afterBytes.length;
            if (bytes > maximum) {
                throw new IOException("Workspace patch exceeds cumulative snapshot limit");
            }
            changes.add(new Change(before, after));
        }
        return new PreparedPatch(changes);
    }

    PatchResult apply(PreparedPatch patch) {
        WorkspacePatchTransaction transaction = new WorkspacePatchTransaction(tree);
        PatchResult result;
        try {
            for (Change change : patch.changes()) {
                Snapshot actual = tree.snapshot(change.before().path(), WorkspaceFileAccess.MAX_BYTES);
                if (actual.exists() != change.before().exists()
                        || !actual.sha256().equals(change.before().sha256())) {
                    throw new IOException("FILE_DIGEST_CONFLICT: Workspace file conflict: "
                            + change.before().path());
                }
            }
            for (Change change : patch.changes()) {
                transaction.apply(change);
            }
            result = new PatchResult(
                    Status.APPLIED,
                    tree.access().supportsExchange()
                            ? "全部文件已按摘要前提应用；修改使用原子交换，原 inode 保留于恢复目录"
                            : "全部文件已按摘要前提应用；Windows 两阶段移动保留原 inode，目标可能短暂不存在",
                    transaction.recoveryPaths());
        } catch (IOException | RuntimeException failure) {
            boolean restored = transaction.rollback();
            result = new PatchResult(
                    restored ? Status.ROLLED_BACK : Status.RECOVERY_REQUIRED,
                    detail(failure),
                    transaction.recoveryPaths());
        }
        try {
            transaction.close();
        } catch (IOException failure) {
            return new PatchResult(Status.RECOVERY_REQUIRED, detail(failure), transaction.recoveryPaths());
        }
        return result;
    }

    private static String detail(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= 4096 ? message : message.substring(0, 4096);
    }
}
