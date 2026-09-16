package com.javaclaw.server.coding;

import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.ContentKind;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;

/** 字节变更证据：所有正文先保存为 CAS，只有能严格解码的有界文本才产生 Diff。 */
final class CodingFileSystemEvidence {
    private final CanonicalJson json;
    private final AttachmentService attachments;

    CodingFileSystemEvidence(CanonicalJson json, AttachmentService attachments) {
        this.json = json;
        this.attachments = attachments;
    }

    List<Backup> save(CodingInvocation invocation, WorkspaceFileAccess.PreparedPatch prepared) {
        List<Backup> backups = new ArrayList<>();
        for (var change : prepared.changes()) {
            backups.add(new Backup(
                    change.before().path(),
                    store(invocation, change.before()),
                    store(invocation, change.after()),
                    diff(change),
                    contentKind(change)));
        }
        return List.copyOf(backups);
    }

    private Optional<String> store(CodingInvocation invocation, WorkspaceFileAccess.Snapshot snapshot) {
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        var identity = json.encode(new BlobIdentity(invocation.id(), snapshot.sha256()));
        return Optional.of(attachments
                .store(
                        AttachmentScope.workspace(invocation.workspaceId()),
                        new CommandIdentity(
                                "coding/filesystem-blob",
                                "coding-filesystem-blob-" + identity.sha256(),
                                0,
                                identity.sha256()),
                        "application/octet-stream",
                        snapshot.content())
                .digest());
    }

    private static ContentKind contentKind(WorkspaceFileAccess.Change change) {
        try {
            CodingFileTools.utf8(change.before().content());
            CodingFileTools.utf8(change.after().content());
            return ContentKind.TEXT;
        } catch (CharacterCodingException binary) {
            return ContentKind.BINARY;
        }
    }

    private static Optional<String> diff(WorkspaceFileAccess.Change change) {
        byte[] before = change.before().content();
        byte[] after = change.after().content();
        if ((long) before.length + after.length > 64 * 1024) {
            return Optional.empty();
        }
        try {
            String oldText = CodingFileTools.utf8(before);
            String newText = CodingFileTools.utf8(after);
            StringBuilder result = new StringBuilder("--- a/")
                    .append(change.before().path())
                    .append("\n+++ b/")
                    .append(change.after().path())
                    .append('\n');
            result.append("@@ -")
                    .append(oldText.isEmpty() ? 0 : 1)
                    .append(',')
                    .append(oldText.lines().count())
                    .append(" +")
                    .append(newText.isEmpty() ? 0 : 1)
                    .append(',')
                    .append(newText.lines().count())
                    .append(" @@\n");
            append(result, oldText, '-');
            append(result, newText, '+');
            boolean truncated = result.length() > 65_536;
            int end = Math.min(result.length(), truncated ? 65_480 : 65_536);
            if (end > 0 && Character.isHighSurrogate(result.charAt(end - 1))) {
                end--;
            }
            return Optional.of(result.substring(0, end) + (truncated ? "\n[Diff 已截断；完整原始字节保存在备份中。]" : ""));
        } catch (CharacterCodingException binary) {
            return Optional.empty();
        }
    }

    private static void append(StringBuilder result, String text, char prefix) {
        text.lines().forEach(line -> result.append(prefix).append(line).append('\n'));
        if (!text.isEmpty() && !text.endsWith("\n")) {
            result.append("\\ No newline at end of file\n");
        }
    }

    record Backup(
            String path,
            Optional<String> beforeBlob,
            Optional<String> afterBlob,
            Optional<String> textDiff,
            ContentKind kind) {}

    private record BlobIdentity(String operationId, String digest) {}
}
