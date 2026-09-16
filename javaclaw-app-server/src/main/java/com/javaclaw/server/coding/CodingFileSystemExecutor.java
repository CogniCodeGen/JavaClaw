package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.DirectoryChange;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.ContentKind;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.FileSystemChange;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.FileSystemResult;
import com.javaclaw.nativehost.coding.WorkspaceDirectoryFiles;
import com.javaclaw.nativehost.coding.WorkspaceDirectoryResult;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingOperationRepository;

/** 非递归文件系统工具的可信副作用边界；原调用身份不因字节计划展开而改变。 先保存原始字节和计划再进入 STARTED；原生回执丢失由 CodingPlatform 记录 UNKNOWN_OUTCOME，不重放。 */
final class CodingFileSystemExecutor {
    private static final int MAX_PATCH_BYTES = 16 * 1024 * 1024;
    private final CanonicalJson json;
    private final CodingOperationRepository operations;
    private final CodingExecutionLocks locks;
    private final CodingFileSystemEvidence evidence;

    CodingFileSystemExecutor(
            CanonicalJson json,
            CodingOperationRepository operations,
            AttachmentService attachments,
            CodingExecutionLocks locks) {
        this.json = json;
        this.operations = operations;
        this.locks = locks;
        evidence = new CodingFileSystemEvidence(json, attachments);
    }

    CodingToolResult execute(WorkspaceFileAccess files, CodingInvocation invocation) throws Exception {
        try (var lease = locks.acquire(invocation.turn().executionRoot())) {
            var directories = new WorkspaceDirectoryFiles(invocation.turn().executionRoot(), invocation.permission());
            String operation = invocation.request().tool().name();
            if (operation.equals("file_mkdir") || operation.equals("file_rmdir")) {
                return directory(files, directories, invocation, operation);
            }
            List<WorkspaceFileAccess.Edit> edits = edits(files, invocation, operation);
            for (var edit : edits) {
                requireParent(files, edit.path(), invocation);
            }
            var prepared = files.preparePatch(edits, MAX_PATCH_BYTES, invocation.cancellation());
            var backups = evidence.save(invocation, prepared);
            operations.preparation(invocation.id(), json.encode(new Preparation(backups)));
            invocation.cancellation().throwIfCancelled();
            operations.start(invocation.id());
            var applied = directories.applyFiles(prepared, invocation.cancellation());
            return result(files, invocation, prepared, applied, backups);
        }
    }

    private List<WorkspaceFileAccess.Edit> edits(
            WorkspaceFileAccess files, CodingInvocation invocation, String operation) throws Exception {
        var arguments = invocation.request().arguments();
        return switch (operation) {
            case "file_write" -> {
                var input = json.decode(arguments, CodingFileSystemContracts.FileWrite.class);
                yield List.of(
                        new WorkspaceFileAccess.Edit(input.path(), input.expectedSha256(), Optional.of(input.bytes())));
            }
            case "file_delete" -> {
                var input = json.decode(arguments, CodingFileSystemContracts.FileDelete.class);
                yield List.of(new WorkspaceFileAccess.Edit(
                        input.path(), Optional.of(input.expectedSha256()), Optional.empty()));
            }
            case "file_copy", "file_move" -> transfer(files, invocation, operation.equals("file_move"));
            default -> throw new IllegalArgumentException("未知文件系统操作");
        };
    }

    private List<WorkspaceFileAccess.Edit> transfer(
            WorkspaceFileAccess files, CodingInvocation invocation, boolean move) throws Exception {
        var input = json.decode(invocation.request().arguments(), CodingFileSystemContracts.FileTransfer.class);
        var source = files.read(input.path(), MAX_PATCH_BYTES, invocation.cancellation());
        if (!source.exists() || !source.sha256().equals(input.expectedSha256())) {
            throw new IllegalStateException("FILE_DIGEST_CONFLICT: 源文件已变化");
        }
        var target = new WorkspaceFileAccess.Edit(input.destination(), Optional.empty(), Optional.of(source.content()));
        return move
                ? List.of(
                        new WorkspaceFileAccess.Edit(
                                input.path(), Optional.of(input.expectedSha256()), Optional.empty()),
                        target)
                : List.of(target);
    }

    private CodingToolResult directory(
            WorkspaceFileAccess files,
            WorkspaceDirectoryFiles directories,
            CodingInvocation invocation,
            String operation)
            throws Exception {
        String path;
        boolean parents = false;
        if (operation.equals("file_mkdir")) {
            var input = json.decode(invocation.request().arguments(), CodingFileSystemContracts.FileMkdir.class);
            path = input.path();
            parents = input.parents();
            if (!parents) {
                requireParent(files, path, invocation);
            }
        } else {
            path = json.decode(invocation.request().arguments(), CodingFileSystemContracts.FileRmdir.class)
                    .path();
            var present = files.stat(path, invocation.cancellation());
            if (present.isEmpty() || !present.orElseThrow().directory()) {
                throw new IllegalArgumentException("DIRECTORY_MISSING_OR_NOT_DIRECTORY");
            }
            if (!invocation.permission().files().allowDelete()) {
                throw new SecurityException("目录删除需要 allowDelete");
            }
        }
        directories.requirePermission(path, operation.equals("file_rmdir"));
        operations.preparation(invocation.id(), json.encode(new DirectoryPreparation(path, operation, parents)));
        invocation.cancellation().throwIfCancelled();
        operations.start(invocation.id());
        WorkspaceDirectoryResult applied = operation.equals("file_mkdir")
                ? directories.mkdir(path, parents, invocation.cancellation())
                : directories.rmdir(path, invocation.cancellation());
        var changes = applied.changes().stream()
                .map(change -> directoryChange(change.path(), change.operation()))
                .toList();
        List<ToolExecutionFact> facts = applied.changes().stream()
                .map(change -> new ToolExecutionFact(new DirectoryChange(Path.of(change.path()), change.operation())))
                .toList();
        boolean complete = applied.failureCode().isEmpty();
        return new CodingToolResult(
                new FileSystemResult(
                        invocation.id(), changes, complete, applied.failureCode(), applied.recoveryPaths()),
                facts,
                complete);
    }

    private CodingToolResult result(
            WorkspaceFileAccess files,
            CodingInvocation invocation,
            WorkspaceFileAccess.PreparedPatch prepared,
            WorkspaceFileAccess.PatchResult applied,
            List<CodingFileSystemEvidence.Backup> backups)
            throws Exception {
        var changes = new ArrayList<FileSystemChange>();
        var facts = new ArrayList<ToolExecutionFact>();
        boolean complete = applied.status() == WorkspaceFileAccess.Status.APPLIED;
        for (int index = 0; index < prepared.changes().size(); index++) {
            var change = prepared.changes().get(index);
            if (!complete
                    && (applied.status() == WorkspaceFileAccess.Status.ROLLED_BACK
                            || !matches(
                                    files.read(change.after().path(), MAX_PATCH_BYTES, invocation.cancellation()),
                                    change.after()))) {
                continue;
            }
            Optional<String> before = digest(change.before());
            Optional<String> after = digest(change.after());
            String operation = before.isEmpty() ? "create" : after.isEmpty() ? "delete" : "update";
            changes.add(new FileSystemChange(
                    change.before().path(),
                    backups.get(index).kind(),
                    operation,
                    before,
                    after,
                    size(change.before()),
                    size(change.after()),
                    backups.get(index).textDiff()));
            facts.add(new ToolExecutionFact(
                    new CorePayloads.FileChange(Path.of(change.before().path()), operation, before, after)));
        }
        for (String created : applied.createdDirectories()) {
            changes.add(directoryChange(created, "create"));
            facts.add(new ToolExecutionFact(new DirectoryChange(Path.of(created), "create")));
        }
        return new CodingToolResult(
                new FileSystemResult(
                        invocation.id(),
                        changes,
                        complete,
                        complete
                                ? Optional.empty()
                                : Optional.of(applied.status().name()),
                        applied.recoveryPaths()),
                facts,
                complete);
    }

    private static void requireParent(WorkspaceFileAccess files, String path, CodingInvocation invocation)
            throws Exception {
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "." : path.substring(0, slash);
        var entry = files.stat(parent, invocation.cancellation());
        if (entry.isEmpty() || !entry.orElseThrow().directory()) {
            throw new IllegalArgumentException("FILE_PARENT_MISSING");
        }
    }

    private static FileSystemChange directoryChange(String path, String operation) {
        return new FileSystemChange(
                path,
                ContentKind.DIRECTORY,
                operation,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static boolean matches(WorkspaceFileAccess.Snapshot actual, WorkspaceFileAccess.Snapshot expected) {
        return actual.exists() == expected.exists() && actual.sha256().equals(expected.sha256());
    }

    private static Optional<String> digest(WorkspaceFileAccess.Snapshot snapshot) {
        return snapshot.exists() ? Optional.of(snapshot.sha256()) : Optional.empty();
    }

    private static Optional<Long> size(WorkspaceFileAccess.Snapshot snapshot) {
        return snapshot.exists() ? Optional.of((long) snapshot.content().length) : Optional.empty();
    }

    private record Preparation(List<CodingFileSystemEvidence.Backup> files) {}

    private record DirectoryPreparation(String path, String operation, boolean parents) {}
}
