package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CommandIdentity;

/** 预检并保存内容寻址备份，提交时保留实际旧文件身份；数据库回滚不代表文件回滚。 */
final class CodingPatchExecutor {
    private static final int MAX_PATCH_BYTES = 16 * 1024 * 1024;
    private final CanonicalJson json;
    private final CodingOperationRepository operations;
    private final AttachmentService attachments;
    private final CodingExecutionLocks locks;

    CodingPatchExecutor(
            CanonicalJson json,
            CodingOperationRepository operations,
            AttachmentService attachments,
            CodingExecutionLocks locks) {
        this.json = json;
        this.operations = operations;
        this.attachments = attachments;
        this.locks = locks;
    }

    CodingToolResult apply(WorkspaceFileAccess files, CodingInvocation invocation) throws Exception {
        var request = json.decode(invocation.request().arguments(), CodingContracts.ApplyPatch.class);
        try (var lease = locks.acquire(invocation.turn().executionRoot())) {
            var prepared =
                    files.preparePatch(expand(files, invocation, request), MAX_PATCH_BYTES, invocation.cancellation());
            List<FileEvidence> evidence = backup(invocation, prepared);
            operations.preparation(invocation.id(), json.encode(new PatchEvidence(evidence)));
            invocation.cancellation().throwIfCancelled();
            operations.start(invocation.id());
            WorkspaceFileAccess.PatchResult applied = files.applyPrepared(prepared, invocation.cancellation());
            return result(files, invocation, prepared, applied, evidence);
        }
    }

    private List<WorkspaceFileAccess.Edit> expand(
            WorkspaceFileAccess files, CodingInvocation invocation, CodingContracts.ApplyPatch request)
            throws Exception {
        List<WorkspaceFileAccess.Edit> edits = new ArrayList<>();
        for (CodingContracts.FileEdit edit : request.changes()) {
            Optional<byte[]> content = edit.content().map(text -> text.getBytes(StandardCharsets.UTF_8));
            if (edit.moveTo().isEmpty()) {
                edits.add(new WorkspaceFileAccess.Edit(edit.path(), edit.expectedSha256(), content));
            } else {
                var source = files.read(edit.path(), MAX_PATCH_BYTES, invocation.cancellation());
                if (!source.exists() || !edit.expectedSha256().orElseThrow().equals(source.sha256())) {
                    throw new IllegalStateException("FILE_DIGEST_CONFLICT: 移动源已变化");
                }
                CodingFileTools.utf8(source.content());
                edits.add(new WorkspaceFileAccess.Edit(edit.path(), edit.expectedSha256(), Optional.empty()));
                edits.add(new WorkspaceFileAccess.Edit(
                        edit.moveTo().orElseThrow(),
                        Optional.empty(),
                        Optional.of(content.orElseGet(source::content))));
            }
        }
        return edits;
    }

    private List<FileEvidence> backup(CodingInvocation invocation, WorkspaceFileAccess.PreparedPatch prepared)
            throws Exception {
        List<FileEvidence> evidence = new ArrayList<>();
        for (WorkspaceFileAccess.Change change : prepared.changes()) {
            String before = CodingFileTools.utf8(change.before().content());
            String after = CodingFileTools.utf8(change.after().content());
            Optional<String> beforeBlob = storeSnapshot(invocation, change.before());
            Optional<String> afterBlob = storeSnapshot(invocation, change.after());
            String diff = fullDiff(change.before().path(), before, after);
            String diffDigest = store(invocation, "text/x-diff", diff.getBytes(StandardCharsets.UTF_8));
            evidence.add(new FileEvidence(
                    change.before().path(),
                    beforeBlob,
                    afterBlob,
                    diffDigest,
                    bound(
                            diff,
                            Math.min(64 * 1024, 1024 * 1024 / prepared.changes().size()))));
        }
        return List.copyOf(evidence);
    }

    private Optional<String> storeSnapshot(CodingInvocation invocation, WorkspaceFileAccess.Snapshot snapshot) {
        return snapshot.exists()
                ? Optional.of(store(invocation, "application/octet-stream", snapshot.content()))
                : Optional.empty();
    }

    private String store(CodingInvocation invocation, String mediaType, byte[] bytes) {
        CanonicalPayload identity = json.encode(new BlobIdentity(
                invocation.id(), mediaType, java.util.HexFormat.of().formatHex(sha256(bytes))));
        return attachments
                .store(
                        AttachmentScope.workspace(invocation.workspaceId()),
                        new CommandIdentity("coding/blob", "coding-blob-" + identity.sha256(), 0, identity.sha256()),
                        mediaType,
                        bytes)
                .digest();
    }

    private CodingToolResult result(
            WorkspaceFileAccess files,
            CodingInvocation invocation,
            WorkspaceFileAccess.PreparedPatch prepared,
            WorkspaceFileAccess.PatchResult applied,
            List<FileEvidence> evidence)
            throws Exception {
        List<CodingResults.PatchChange> changed = new ArrayList<>();
        List<ToolExecutionFact> facts = new ArrayList<>();
        boolean complete = applied.status() == WorkspaceFileAccess.Status.APPLIED;
        for (int index = 0; index < prepared.changes().size(); index++) {
            WorkspaceFileAccess.Change change = prepared.changes().get(index);
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
            changed.add(new CodingResults.PatchChange(
                    change.after().path(),
                    operation,
                    before,
                    after,
                    Optional.empty(),
                    evidence.get(index).preview()));
            facts.add(new ToolExecutionFact(
                    new CorePayloads.FileChange(Path.of(change.after().path()), operation, before, after)));
        }
        Optional<String> failure =
                complete ? Optional.empty() : Optional.of(applied.status().name());
        return new CodingToolResult(
                new CodingResults.PatchResult(
                        combineMoves(invocation, changed), complete, failure, applied.recoveryPaths()),
                facts,
                complete);
    }

    private List<CodingResults.PatchChange> combineMoves(
            CodingInvocation invocation, List<CodingResults.PatchChange> changes) {
        var remaining = new java.util.LinkedHashMap<String, CodingResults.PatchChange>();
        changes.forEach(change -> remaining.put(change.path(), change));
        var result = new ArrayList<CodingResults.PatchChange>();
        var request = json.decode(invocation.request().arguments(), CodingContracts.ApplyPatch.class);
        for (var edit : request.changes()) {
            var source = remaining.remove(edit.path());
            var destination = edit.moveTo().map(remaining::remove).orElse(null);
            if (source != null && destination != null) {
                result.add(new CodingResults.PatchChange(
                        source.path(),
                        "move",
                        source.beforeSha256(),
                        destination.afterSha256(),
                        edit.moveTo(),
                        source.diff() + destination.diff()));
            } else if (source != null) {
                result.add(source);
            } else if (destination != null) {
                result.add(destination);
            }
        }
        return List.copyOf(result);
    }

    private static boolean matches(WorkspaceFileAccess.Snapshot actual, WorkspaceFileAccess.Snapshot expected) {
        return actual.exists() == expected.exists() && actual.sha256().equals(expected.sha256());
    }

    private static Optional<String> digest(WorkspaceFileAccess.Snapshot snapshot) {
        return snapshot.exists() ? Optional.of(snapshot.sha256()) : Optional.empty();
    }

    private static String fullDiff(String path, String before, String after) {
        StringBuilder diff = new StringBuilder("--- a/")
                .append(path)
                .append("\n+++ b/")
                .append(path)
                .append('\n');
        List<String> oldLines = before.lines().toList();
        List<String> newLines = after.lines().toList();
        diff.append("@@ -")
                .append(oldLines.isEmpty() ? 0 : 1)
                .append(',')
                .append(oldLines.size())
                .append(" +")
                .append(newLines.isEmpty() ? 0 : 1)
                .append(',')
                .append(newLines.size())
                .append(" @@\n");
        appendLines(diff, oldLines, '-', before.endsWith("\n"));
        appendLines(diff, newLines, '+', after.endsWith("\n"));
        return diff.toString();
    }

    private static void appendLines(StringBuilder target, List<String> lines, char prefix, boolean finalNewline) {
        for (String line : lines) {
            target.append(prefix).append(line).append('\n');
        }
        if (!lines.isEmpty() && !finalNewline) {
            target.append("\\ No newline at end of file\n");
        }
    }

    private static String bound(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 32) + "\n…Diff 预览已截断，完整内容见附件。";
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record BlobIdentity(String operationId, String mediaType, String digest) {}

    private record PatchEvidence(List<FileEvidence> files) {}

    private record FileEvidence(
            String path, Optional<String> beforeBlob, Optional<String> afterBlob, String diffBlob, String preview) {}
}
