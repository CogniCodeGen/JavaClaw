package com.javaclaw.server.preview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.DocumentPreviewRpcContracts;
import com.javaclaw.protocol.ProtocolException;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocumentPreviewServiceTest extends PreviewServiceFixture {
    @Test
    void POSIX来源中的反斜杠不能重解释成可读取的其他文件() throws Exception {
        assumeTrue(java.io.File.separatorChar == '/');
        var permission = permission(1, List.of(workspace.root()));
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        Files.createDirectory(workspace.root().resolve("docs"));
        Files.writeString(workspace.root().resolve("docs/notes.txt"), "另一文件");
        var source = message(permission, "文件变更", List.of());
        var change = append(
                source,
                CoreSchemas.FILE_CHANGE,
                new CorePayloads.FileChange(Path.of("docs\\notes.txt"), "update", Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.file(workspace.id(), change.id(), "file:0"),
                        new CancellationSource()));
    }

    @Test
    void 附件按片读取且不能借相同摘要跨Workspace取得内容() throws Exception {
        byte[] content = "中文🙂正文".repeat(20000).getBytes(StandardCharsets.UTF_8);
        AttachmentRef reference = attachment(content);
        var result = previews.resolve(
                "first", DocumentReference.attachment(workspace.id(), reference), new CancellationSource());
        var first = previews.read("first", result.handleId(), 0, 256 * 1024);
        assertEquals(256 * 1024, first.content().length);
        var last = previews.read("first", result.handleId(), first.nextOffsetBytes(), 256 * 1024);
        var combined = new ByteArrayOutputStream();
        combined.write(first.content());
        combined.write(last.content());
        assertArrayEquals(content, combined.toByteArray());
        Workspace other = workspace("other");
        assertThrows(
                PersistenceException.class,
                () -> previews.resolve(
                        "second", DocumentReference.attachment(other.id(), reference), new CancellationSource()));
        assertThrows(SecurityException.class, () -> previews.read("second", result.handleId(), 0, 1));
    }

    @Test
    void 每次读都会重验附件声明且撤销后的缓存句柄失效() throws Exception {
        AttachmentRef reference = attachment("secret".getBytes(StandardCharsets.UTF_8));
        var result = previews.resolve(
                "first", DocumentReference.attachment(workspace.id(), reference), new CancellationSource());
        new H2Transactions(database).execute(connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM CORE.ATTACHMENT_WORKSPACE_CLAIM WHERE ATTACHMENT_DIGEST = ?")) {
                statement.setString(1, reference.digest());
                statement.executeUpdate();
            }
            return null;
        });
        assertThrows(PersistenceException.class, () -> previews.read("first", result.handleId(), 0, 1));
        assertThrows(SecurityException.class, () -> previews.renew("first", result.handleId()));
    }

    @Test
    void 重复控制请求只重放原回执且关闭后不会复活() throws Exception {
        var reference = DocumentReference.attachment(workspace.id(), attachment(new byte[] {1, 2, 3}));
        var payload = json.encode(
                new WriteCommand("resolve", 0, json.encode(new DocumentPreviewRpcContracts.ResolvePayload(reference))));
        try (var session = previews.openSession()) {
            var first = session.handle(DocumentPreviewRpcContracts.RESOLVE, payload);
            assertEquals(first, session.handle(DocumentPreviewRpcContracts.RESOLVE, payload));
            var preview = json.decode(first, DocumentPreview.class);
            session.handle(
                    DocumentPreviewRpcContracts.CLOSE,
                    json.encode(new WriteCommand(
                            "close",
                            0,
                            json.encode(new DocumentPreviewRpcContracts.HandlePayload(preview.handleId())))));
            assertEquals(first, session.handle(DocumentPreviewRpcContracts.RESOLVE, payload));
            assertThrows(
                    SecurityException.class,
                    () -> session.handle(
                            DocumentPreviewRpcContracts.READ,
                            json.encode(new DocumentPreviewRpcContracts.ReadPayload(preview.handleId(), 0, 1))));
            var changed = json.encode(new WriteCommand(
                    "resolve",
                    0,
                    json.encode(new DocumentPreviewRpcContracts.ResolvePayload(
                            DocumentReference.attachment(workspace.id(), attachment(new byte[] {4}))))));
            assertThrows(ProtocolException.class, () -> session.handle(DocumentPreviewRpcContracts.RESOLVE, changed));
        }
    }

    @Test
    void 历史消息可查看当前文件但快照不会随文件改变且撤权立即失效() throws Exception {
        var permission = permission(1, List.of(workspace.root()));
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        Files.writeString(workspace.root().resolve("a.md"), "初始文档");
        var source = message(permission, "[文档](a.md)", List.of());
        var reference = DocumentReference.file(workspace.id(), source.id(), "link:0");
        var first = previews.resolve("first", reference, new CancellationSource());
        assertEquals(DocumentPreview.Origin.CURRENT_FILE, first.origin());
        Files.writeString(workspace.root().resolve("a.md"), "后续文档");
        assertEquals(
                "初始文档",
                new String(previews.read("first", first.handleId(), 0, 100).content(), StandardCharsets.UTF_8));
        var second = previews.resolve("first", reference, new CancellationSource());
        assertEquals(
                "后续文档",
                new String(previews.read("first", second.handleId(), 0, 100).content(), StandardCharsets.UTF_8));
        var revoked = permission(2, List.of());
        profiles.update(
                CommandIdentity.from(
                        "permissionProfile/update", new WriteCommand("revoke", 1, json.encode(revoked)), json),
                revoked);
        var invalidations = new ArrayList<DocumentPreviewRpcContracts.Invalidated>();
        previews.notifications("first", invalidations::add);
        assertThrows(SecurityException.class, () -> previews.read("first", first.handleId(), 0, 1));
        assertEquals("REVOKED", invalidations.getFirst().reasonCode());
        assertThrows(SecurityException.class, () -> previews.renew("first", first.handleId()));
    }

    @Test
    void 附件相对资源必须明确属于同一条消息且缺失时不读取工作区同名文件() throws Exception {
        var permission = permission(1, List.of());
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        var original = attachment("[资源](image.txt) [缺失](missing.txt)".getBytes(StandardCharsets.UTF_8));
        var image = attachment("image".getBytes(StandardCharsets.UTF_8));
        var namedImage = new AttachmentRef(image.digest(), image.mediaType(), "image.txt", image.sizeBytes());
        var source = message(permission, "附带文档", List.of(original, namedImage));
        var parent = previews.resolve(
                "first", DocumentReference.attachment(workspace.id(), source.id(), original), new CancellationSource());
        var resource = previews.resource("first", parent.handleId(), "image.txt", new CancellationSource());
        assertEquals(image.digest(), resource.digest());
        assertThrows(
                IllegalArgumentException.class,
                () -> previews.resource("first", parent.handleId(), "missing.txt", new CancellationSource()));
        assertThrows(
                SecurityException.class,
                () -> previews.resource("first", parent.handleId(), "not-linked.txt", new CancellationSource()));
        var otherSource = message(permission, "无关联附件", List.of());
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.attachment(workspace.id(), otherSource.id(), original),
                        new CancellationSource()));
    }

    @Test
    void 消息正文代码块及UTF16文档资源都使用同一个服务端来源() throws Exception {
        var permission = permission(1, List.of(workspace.root()));
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        Files.write(
                workspace.root().resolve("说明.md"), "\ufeff[资源](<data notes.txt>)".getBytes(StandardCharsets.UTF_16LE));
        Files.writeString(workspace.root().resolve("data notes.txt"), "资源正文");
        String text = "[文档](说明.md)\n\n```java\nString value = \"中文\";\n```\n";
        var source = message(permission, text, List.of());
        var body = previews.resolve(
                "first", DocumentReference.message(workspace.id(), source.id(), "body"), new CancellationSource());
        assertEquals(
                text,
                new String(previews.read("first", body.handleId(), 0, 1024).content(), StandardCharsets.UTF_8));
        var fence = previews.resolve(
                "first", DocumentReference.message(workspace.id(), source.id(), "fence:0"), new CancellationSource());
        assertEquals(
                "String value = \"中文\";\n",
                new String(previews.read("first", fence.handleId(), 0, 1024).content(), StandardCharsets.UTF_8));
        var document = previews.resource("first", body.handleId(), "说明.md", new CancellationSource());
        var resource = previews.resource("first", document.handleId(), "data notes.txt", new CancellationSource());
        assertEquals(
                "资源正文",
                new String(previews.read("first", resource.handleId(), 0, 1024).content(), StandardCharsets.UTF_8));
        assertThrows(
                SecurityException.class,
                () -> previews.resource("first", document.handleId(), "../private.txt", new CancellationSource()));
        assertThrows(
                IllegalArgumentException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.message(workspace.id(), source.id(), "fence:1"),
                        new CancellationSource()));
    }

    @Test
    void 连接控制校验版本且关闭后不能继续查询() throws Exception {
        var reference = DocumentReference.attachment(workspace.id(), attachment(new byte[] {1}));
        var session = previews.openSession();
        try (session) {
            var invalidRevision = json.encode(
                    new WriteCommand("bad", 1, json.encode(new DocumentPreviewRpcContracts.ResolvePayload(reference))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> session.handle(DocumentPreviewRpcContracts.RESOLVE, invalidRevision));
            var resolved = session.handle(
                    DocumentPreviewRpcContracts.RESOLVE,
                    json.encode(new WriteCommand(
                            "resolve", 0, json.encode(new DocumentPreviewRpcContracts.ResolvePayload(reference)))));
            var preview = json.decode(resolved, DocumentPreview.class);
            var renewed = session.handle(
                    DocumentPreviewRpcContracts.RENEW,
                    json.encode(new WriteCommand(
                            "renew",
                            0,
                            json.encode(new DocumentPreviewRpcContracts.HandlePayload(preview.handleId())))));
            assertEquals(
                    preview.digest(),
                    json.decode(renewed, DocumentPreview.class).digest());
            var chunk = session.handle(
                    DocumentPreviewRpcContracts.READ,
                    json.encode(new DocumentPreviewRpcContracts.ReadPayload(preview.handleId(), 0, 1)));
            assertArrayEquals(
                    new byte[] {1}, json.decode(chunk, DocumentChunk.class).content());
        }
        assertThrows(
                TurnCancelledException.class,
                () -> session.handle(
                        DocumentPreviewRpcContracts.RESOLVE,
                        json.encode(new WriteCommand(
                                "later", 0, json.encode(new DocumentPreviewRpcContracts.ResolvePayload(reference))))));
    }

    @Test
    void Coding工具结果必须匹配来源调用并保留搜索行号与版本差异() throws Exception {
        var permission = permission(1, List.of(workspace.root()));
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        Files.writeString(workspace.root().resolve("data.txt"), "current");
        var source = message(permission, "读取项目文件", List.of());
        var outputs = Map.of(
                "file_read", new CodingResults.FileReadResult("data.txt", "old", "a".repeat(64), 0, 3, false, false),
                "file_list",
                        new CodingResults.FileListResult(
                                List.of(new CodingResults.FileEntry("data.txt", CodingResults.EntryKind.FILE, 7)),
                                Optional.empty()),
                "file_search",
                        new CodingResults.FileSearchResult(
                                List.of(new CodingResults.FileMatch("data.txt", 17, "current")), false, 7));
        for (var output : outputs.entrySet()) {
            String callId = UUID.randomUUID().toString();
            append(
                    source,
                    CoreSchemas.TOOL_CALL,
                    new CorePayloads.ToolCall(
                            callId, CodingContracts.EXTENSION_ID, output.getKey(), 1, json.encode(Map.of())));
            var result = append(
                    source,
                    CoreSchemas.TOOL_RESULT,
                    new CorePayloads.ToolResult(callId, true, json.encode(output.getValue()), Optional.empty()));
            var preview = previews.resolve(
                    "first", DocumentReference.file(workspace.id(), result.id(), "file:0"), new CancellationSource());
            assertEquals(
                    "current",
                    new String(previews.read("first", preview.handleId(), 0, 32).content(), StandardCharsets.UTF_8));
            assertEquals(output.getKey().equals("file_read"), preview.changed());
            assertEquals(
                    output.getKey().equals("file_search") ? Optional.of(17) : Optional.empty(), preview.startLine());
            previews.closeHandle("first", preview.handleId());
        }
        var orphan = append(
                source,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(
                        "unknown-call", true, json.encode(outputs.get("file_read")), Optional.empty()));
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.file(workspace.id(), orphan.id(), "file:0"),
                        new CancellationSource()));
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.file(workspace.id(), source.id(), "file:0"),
                        new CancellationSource()));
    }

    @Test
    void 原生Path编码的嵌套文件变更可查看但隐藏消息与目录不能充当文档() throws Exception {
        var permission = permission(1, List.of(workspace.root()));
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        // 在 Windows 上由真实 Path 编解码生成反斜杠，验证 RPC 来源进入固定 Worker 前已转换协议分隔符。
        Path relative = Path.of("docs", "nested", "design notes.txt");
        Files.createDirectories(workspace.root().resolve(relative).getParent());
        Files.writeString(workspace.root().resolve(relative), "current");
        Files.createDirectory(workspace.root().resolve("folder"));
        var source = message(permission, "[目录](folder) [缺失](missing.txt)", List.of());
        var change = append(
                source,
                CoreSchemas.FILE_CHANGE,
                new CorePayloads.FileChange(relative, "update", Optional.empty(), Optional.of("a".repeat(64))));
        var preview = previews.resolve(
                "first", DocumentReference.file(workspace.id(), change.id(), "file:0"), new CancellationSource());
        assertEquals(true, preview.changed());
        assertEquals("design notes.txt", preview.fileName());
        assertEquals(
                "current",
                new String(previews.read("first", preview.handleId(), 0, 1024).content(), StandardCharsets.UTF_8));
        assertThrows(
                IllegalArgumentException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.file(workspace.id(), source.id(), "link:0"),
                        new CancellationSource()));
        assertThrows(
                IOException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.file(workspace.id(), source.id(), "link:1"),
                        new CancellationSource()));
        var hidden = append(
                source,
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.SYSTEM, "隐藏系统内容", List.of(), Optional.empty()));
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.message(workspace.id(), hidden.id(), "body"),
                        new CancellationSource()));
    }

    @Test
    void 来源归属与附件元信息都必须匹配且独立附件不猜测资源() throws Exception {
        var permission = permission(1, List.of());
        profiles.instantiatePreset(identity("permissionProfile/preset/instantiate", permission), permission);
        var source = message(permission, "公开来源", List.of());
        var other = workspace("other");
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "first", DocumentReference.message(other.id(), source.id(), "body"), new CancellationSource()));
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "first",
                        DocumentReference.message(workspace.id(), ItemId.random(), "body"),
                        new CancellationSource()));
        var attachment = attachment("[资源](file.txt)".getBytes(StandardCharsets.UTF_8));
        var forged = new AttachmentRef(attachment.digest(), "image/png", attachment.fileName(), attachment.sizeBytes());
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "first", DocumentReference.attachment(workspace.id(), forged), new CancellationSource()));
        var parent = previews.resolve(
                "first", DocumentReference.attachment(workspace.id(), attachment), new CancellationSource());
        assertThrows(
                IllegalArgumentException.class,
                () -> previews.resource("first", parent.handleId(), "file.txt", new CancellationSource()));
        var cancellation = new CancellationSource();
        cancellation.cancel("页面已关闭");
        assertThrows(
                TurnCancelledException.class,
                () -> previews.resolve(
                        "first", DocumentReference.attachment(workspace.id(), attachment), cancellation));
    }

    @Test
    void 缓存损坏发出精确失效通知且RPC错误不包含宿主路径() throws Exception {
        var reference = DocumentReference.attachment(workspace.id(), attachment(new byte[] {1, 2, 3}));
        var invalidations = new ArrayList<DocumentPreviewRpcContracts.Invalidated>();
        try (var session = previews.openSession()) {
            session.notifications(invalidations::add);
            var preview = json.decode(
                    session.handle(
                            DocumentPreviewRpcContracts.RESOLVE,
                            json.encode(new WriteCommand(
                                    "resolve",
                                    0,
                                    json.encode(new DocumentPreviewRpcContracts.ResolvePayload(reference))))),
                    DocumentPreview.class);
            try (var files = Files.walk(database.dataRoot().resolve("cache/document-preview"))) {
                Path cached = files.filter(path -> path.getFileName().toString().equals(preview.handleId()))
                        .findFirst()
                        .orElseThrow()
                        .resolve("content");
                Files.write(cached, new byte[] {1});
            }
            var failure = assertThrows(
                    ProtocolException.class,
                    () -> session.handle(
                            DocumentPreviewRpcContracts.READ,
                            json.encode(new DocumentPreviewRpcContracts.ReadPayload(preview.handleId(), 0, 3))));
            assertEquals(
                    false, failure.getMessage().contains(database.dataRoot().toString()));
            assertEquals(
                    List.of(new DocumentPreviewRpcContracts.Invalidated(preview.handleId(), "CORRUPT")), invalidations);
        }
    }
}
