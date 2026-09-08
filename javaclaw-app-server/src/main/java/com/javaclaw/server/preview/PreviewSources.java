package com.javaclaw.server.preview;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.nativehost.coding.WorkspacePreviewSnapshot;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreItemReader;
import com.javaclaw.server.turn.PreviewReadAuthority;

/** 解析可验证来源并通过原生 Worker 或受控 Blob 创建不可变内容，绝不直接打开项目文件。 */
final class PreviewSources {
    private final CoreItemReader core;
    private final AttachmentService attachments;
    private final PreviewReadAuthority authority;
    private final CanonicalJson json;

    PreviewSources(
            CoreItemReader core, AttachmentService attachments, PreviewReadAuthority authority, CanonicalJson json) {
        this.core = core;
        this.attachments = attachments;
        this.authority = authority;
        this.json = json;
    }

    Prepared prepare(DocumentReference reference, CancellationToken cancellation) throws Exception {
        authority.workspace(reference.workspaceId());
        return switch (reference.kind()) {
            case ATTACHMENT -> attachment(reference);
            case MESSAGE_CONTENT -> message(reference);
            case WORKSPACE_FILE -> file(reference, cancellation);
        };
    }

    private Prepared attachment(DocumentReference reference) {
        var value = reference.attachment().orElseThrow();
        requireAttachmentMessage(reference);
        var metadata = attachments.readMetadata(AttachmentScope.workspace(reference.workspaceId()), value.digest());
        if (metadata.sizeBytes() != value.sizeBytes() || !metadata.mediaType().equals(value.mediaType())) {
            throw new SecurityException("预览附件与已提交元信息不一致");
        }
        return new Prepared(
                reference,
                new Details(
                        value.fileName(),
                        metadata.mediaType(),
                        metadata.sizeBytes(),
                        DocumentPreview.Origin.REFERENCED_VERSION,
                        Optional.of(value.digest()),
                        Optional.empty()),
                Optional.empty(),
                "",
                Optional.empty());
    }

    private Prepared message(DocumentReference reference) {
        ItemEnvelope item = authority.source(
                reference.workspaceId(), reference.sourceItemId().orElseThrow());
        String text = messageText(item);
        boolean body = reference.selector().equals("body");
        String selected = body ? text : PreviewMarkdown.fence(text, index(reference.selector()));
        byte[] bytes = selected.getBytes(StandardCharsets.UTF_8);
        return new Prepared(
                reference,
                new Details(
                        body ? "消息.md" : "代码.txt",
                        body ? "text/markdown" : "text/plain",
                        bytes.length,
                        DocumentPreview.Origin.REFERENCED_VERSION,
                        Optional.empty(),
                        Optional.empty()),
                Optional.empty(),
                "",
                Optional.of(bytes));
    }

    private Prepared file(DocumentReference reference, CancellationToken cancellation) throws Exception {
        var access = authority.current(
                reference.workspaceId(), reference.sourceItemId().orElseThrow(), Optional.empty());
        Selection selection = reference.selector().startsWith("link:")
                ? messageLink(access.item(), index(reference.selector()))
                : typedFile(access.item(), index(reference.selector()));
        return workspaceFile(reference, access, selection, cancellation);
    }

    private Prepared workspaceFile(
            DocumentReference reference,
            PreviewReadAuthority.Access access,
            Selection selection,
            CancellationToken cancellation)
            throws Exception {
        var entry = new WorkspaceFileAccess(access.root(), access.permission())
                .stat(selection.path(), cancellation)
                .orElseThrow(() -> new IOException("PREVIEW_SOURCE_MISSING: 文件不存在"));
        if (entry.directory()) {
            throw new IllegalArgumentException("PREVIEW_UNSUPPORTED: 目录不能作为文档预览");
        }
        String name = Path.of(selection.path()).getFileName().toString();
        return new Prepared(
                reference,
                new Details(
                        name,
                        mediaType(name),
                        entry.size(),
                        DocumentPreview.Origin.CURRENT_FILE,
                        selection.digest(),
                        selection.line()),
                Optional.of(access),
                selection.path(),
                Optional.empty());
    }

    private Selection messageLink(ItemEnvelope item, int index) {
        String href = PreviewMarkdown.select(PreviewMarkdown.messageLinks(messageText(item)), index);
        var target = PreviewMarkdown.relative(href, "");
        return new Selection(target.path(), Optional.empty(), target.line());
    }

    private Selection typedFile(ItemEnvelope item, int index) {
        if (CoreSchemas.FILE_CHANGE.equals(item.schemaId()) && item.producerId().equals("core") && index == 0) {
            var change = json.decode(item.payload(), CorePayloads.FileChange.class);
            // 只转换宿主分隔符；POSIX 文件名里的反斜杠仍由 Worker 拒绝，不能将它重新解释成另一条路径。
            return new Selection(
                    change.relativePath().toString().replace(File.separatorChar, '/'),
                    change.afterDigest(),
                    Optional.empty());
        }
        if (!CoreSchemas.TOOL_RESULT.equals(item.schemaId())
                || !item.producerId().equals("core")) {
            throw new SecurityException("不支持该文件来源 Schema");
        }
        var result = json.decode(item.payload(), CorePayloads.ToolResult.class);
        var call = core.findToolCall(item.turnId(), result.callId())
                .orElseThrow(() -> new SecurityException("文件结果缺少工具调用证据"));
        if (!result.success() || !call.producerId().equals(CodingContracts.EXTENSION_ID)) {
            throw new SecurityException("文件结果不是已确认 Coding 来源");
        }
        return switch (call.toolName()) {
            case "file_read" -> readSelection(result, index);
            case "file_list" -> listSelection(result, index);
            case "file_search" -> searchSelection(result, index);
            default -> throw new SecurityException("工具结果不提供文件预览引用");
        };
    }

    private Selection readSelection(CorePayloads.ToolResult result, int index) {
        if (index != 0) {
            throw new IllegalArgumentException("文件读取结果只有一个来源");
        }
        var value = json.decode(result.output(), CodingResults.FileReadResult.class);
        return new Selection(value.path(), Optional.of(value.sha256()), Optional.empty());
    }

    private Selection listSelection(CorePayloads.ToolResult result, int index) {
        var value = json.decode(result.output(), CodingResults.FileListResult.class)
                .entries()
                .get(index);
        if (value.kind() != CodingResults.EntryKind.FILE) {
            throw new IllegalArgumentException("所选条目不是文件");
        }
        return new Selection(value.path(), Optional.empty(), Optional.empty());
    }

    private Selection searchSelection(CorePayloads.ToolResult result, int index) {
        var value = json.decode(result.output(), CodingResults.FileSearchResult.class)
                .matches()
                .get(index);
        return new Selection(value.path(), Optional.empty(), Optional.of(Math.toIntExact(value.lineNumber())));
    }

    Prepared resource(Prepared parent, String markdown, String href, CancellationToken cancellation) throws Exception {
        revalidate(parent);
        if (!PreviewMarkdown.links(markdown).contains(href)) {
            throw new SecurityException("资源链接不在父文档中");
        }
        if (parent.access().isPresent()) {
            var directory = Path.of(parent.relative()).getParent();
            var target = PreviewMarkdown.relative(href, directory == null ? "" : directory.toString());
            var current = authority.current(
                    parent.reference().workspaceId(),
                    parent.reference().sourceItemId().orElseThrow(),
                    parent.access());
            return workspaceFile(
                    parent.reference(),
                    current,
                    new Selection(target.path(), Optional.empty(), target.line()),
                    cancellation);
        }
        if (parent.reference().kind() == DocumentReference.Kind.MESSAGE_CONTENT) {
            var reference = parent.reference();
            var current = authority.current(
                    reference.workspaceId(), reference.sourceItemId().orElseThrow(), Optional.empty());
            var target = PreviewMarkdown.relative(href, "");
            return workspaceFile(
                    reference, current, new Selection(target.path(), Optional.empty(), target.line()), cancellation);
        }
        return attachmentResource(parent, href);
    }

    private Prepared attachmentResource(Prepared parent, String href) {
        DocumentReference reference = parent.reference();
        if (reference.sourceItemId().isEmpty()) {
            throw new IllegalArgumentException("PREVIEW_RESOURCE_MISSING: 独立附件没有关联的相对资源");
        }
        var item = authority.source(
                reference.workspaceId(), reference.sourceItemId().orElseThrow());
        messageText(item);
        var message = json.decode(item.payload(), CorePayloads.Message.class);
        String name = PreviewMarkdown.relative(href, "").path();
        var matches = message.attachments().stream()
                .filter(value -> value.fileName().equals(name))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("PREVIEW_RESOURCE_MISSING: 来源消息没有唯一匹配的附件");
        }
        return attachment(DocumentReference.attachment(reference.workspaceId(), item.id(), matches.getFirst()));
    }

    private void requireAttachmentMessage(DocumentReference reference) {
        if (reference.sourceItemId().isEmpty()) {
            return;
        }
        var item = authority.source(
                reference.workspaceId(), reference.sourceItemId().orElseThrow());
        messageText(item);
        if (!json.decode(item.payload(), CorePayloads.Message.class)
                .attachments()
                .contains(reference.attachment().orElseThrow())) {
            throw new SecurityException("预览附件不属于来源消息");
        }
    }

    void revalidate(Prepared source) {
        DocumentReference reference = source.reference();
        authority.workspace(reference.workspaceId());
        if (source.access().isPresent()) {
            var current = authority.current(
                    reference.workspaceId(), reference.sourceItemId().orElseThrow(), source.access());
            Path path = current.root().resolve(source.relative()).normalize();
            if (current.permission().files().readRoots().stream().noneMatch(path::startsWith)) {
                throw new SecurityException("预览文件读取权限已撤销");
            }
        } else if (reference.kind() == DocumentReference.Kind.ATTACHMENT) {
            requireAttachmentMessage(reference);
            attachments.readMetadata(
                    AttachmentScope.workspace(reference.workspaceId()),
                    reference.attachment().orElseThrow().digest());
        } else {
            authority.source(reference.workspaceId(), reference.sourceItemId().orElseThrow());
        }
    }

    WorkspacePreviewSnapshot.Result copy(Prepared source, Path destination, CancellationToken cancellation)
            throws Exception {
        revalidate(source);
        if (source.access().isPresent()) {
            var access = source.access().orElseThrow();
            return WorkspacePreviewSnapshot.capture(
                    access.root(),
                    access.permission(),
                    source.relative(),
                    destination,
                    source.details().size(),
                    cancellation);
        }
        var digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            if (source.inline().isPresent()) {
                byte[] bytes = source.inline().orElseThrow();
                digest.update(bytes);
                output.write(bytes);
                size = bytes.length;
            } else {
                size = copyAttachment(source, output, digest, cancellation);
            }
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        if (source.details()
                .expectedDigest()
                .filter(expected -> !expected.equals(hash))
                .isPresent()) {
            throw new IOException("PREVIEW_SOURCE_CHANGED: 附件内容摘要不一致");
        }
        return new WorkspacePreviewSnapshot.Result(size, hash);
    }

    private long copyAttachment(
            Prepared source, OutputStream output, MessageDigest digest, CancellationToken cancellation)
            throws IOException {
        var reference = source.reference();
        long offset = 0;
        while (offset < source.details().size()) {
            cancellation.throwIfCancelled();
            var chunk = attachments.readChunk(
                    AttachmentScope.workspace(reference.workspaceId()),
                    reference.attachment().orElseThrow().digest(),
                    offset,
                    256 * 1024);
            byte[] bytes = chunk.content();
            digest.update(bytes);
            output.write(bytes);
            offset = chunk.nextOffsetBytes();
        }
        return offset;
    }

    private String messageText(ItemEnvelope item) {
        if (!CoreSchemas.MESSAGE.equals(item.schemaId()) || !item.producerId().equals("core")) {
            throw new SecurityException("预览来源不是持久消息");
        }
        CorePayloads.Message message = json.decode(item.payload(), CorePayloads.Message.class);
        if (message.role() != com.javaclaw.api.MessageRole.USER
                && message.role() != com.javaclaw.api.MessageRole.ASSISTANT) {
            throw new SecurityException("预览仅允许公开的用户与助手消息");
        }
        return message.text();
    }

    private static int index(String selector) {
        return Integer.parseInt(selector.substring(selector.indexOf(':') + 1));
    }

    private static String mediaType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return lower.endsWith(".gif") ? "image/gif" : "text/plain";
    }

    record Details(
            String name,
            String mediaType,
            long size,
            DocumentPreview.Origin origin,
            Optional<String> expectedDigest,
            Optional<Integer> line) {
        Details {
            if (size < 0 || size > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("PREVIEW_TOO_LARGE: 来源超过 64 MiB");
            }
        }
    }

    record Prepared(
            DocumentReference reference,
            Details details,
            Optional<PreviewReadAuthority.Access> access,
            String relative,
            Optional<byte[]> inline) {
        Prepared {
            Objects.requireNonNull(reference, "reference");
        }
    }

    private record Selection(String path, Optional<String> digest, Optional<Integer> line) {}
}
