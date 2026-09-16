package com.javaclaw.server.preview;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ThreadId;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.server.persistence.H2TurnJournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserPreviewSourceTest extends PreviewServiceFixture {
    @Test
    void 成功浏览器附件按原Item和Thread授权预览() throws Exception {
        var permission = permission(1, List.of(workspace.root()));
        var source = message(permission, "浏览器下载", List.of());
        AttachmentRef file = attachment("浏览器生成的文件".getBytes(StandardCharsets.UTF_8));
        ThreadId thread = core.findTurn(source.turnId()).orElseThrow().threadId();
        ItemEnvelope result = result(source, BuiltinExtensionIds.SITE, true, output(thread, file));
        var preview = previews.resolve(
                "test", DocumentReference.attachment(workspace.id(), result.id(), file), new CancellationSource());
        assertEquals(file.sizeBytes(), preview.sizeBytes());
    }

    @Test
    void 伪造工具来源失败结果和跨Thread附件都不能生成预览权限() throws Exception {
        var source = message(permission(1, List.of(workspace.root())), "浏览器下载", List.of());
        AttachmentRef file = attachment(new byte[] {1, 2, 3});
        ThreadId thread = core.findTurn(source.turnId()).orElseThrow().threadId();
        List<ItemEnvelope> rejected = List.of(
                result(source, "external.extension", true, output(thread, file)),
                result(source, BuiltinExtensionIds.SITE, false, output(thread, file)),
                result(source, BuiltinExtensionIds.SITE, true, output(ThreadId.random(), file)));
        for (ItemEnvelope value : rejected) {
            assertThrows(
                    SecurityException.class,
                    () -> previews.resolve(
                            "test",
                            DocumentReference.attachment(workspace.id(), value.id(), file),
                            new CancellationSource()));
        }
        ItemEnvelope valid = result(source, BuiltinExtensionIds.SITE, true, output(thread, file));
        AttachmentRef other = attachment(new byte[] {4, 5});
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "test",
                        DocumentReference.attachment(workspace.id(), valid.id(), other),
                        new CancellationSource()));
    }

    @Test
    void 后到调用或重复调用身份不能签发浏览器附件预览() throws Exception {
        var source = message(permission(1, List.of(workspace.root())), "浏览器下载", List.of());
        AttachmentRef file = attachment(new byte[] {1, 2, 3});
        ThreadId thread = core.findTurn(source.turnId()).orElseThrow().threadId();
        String late = UUID.randomUUID().toString();
        ItemEnvelope beforeCall = append(
                source,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(late, true, json.encode(output(thread, file)), Optional.empty()));
        append(
                source,
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall(late, BuiltinExtensionIds.SITE, "browser_act", 1, json.encode(Map.of())));
        String duplicate = UUID.randomUUID().toString();
        var call =
                new CorePayloads.ToolCall(duplicate, BuiltinExtensionIds.SITE, "browser_act", 1, json.encode(Map.of()));
        append(source, CoreSchemas.TOOL_CALL, call);
        append(source, CoreSchemas.TOOL_CALL, call);
        ItemEnvelope duplicated = append(
                source,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(duplicate, true, json.encode(output(thread, file)), Optional.empty()));
        for (var invalid : List.of(beforeCall, duplicated)) {
            assertThrows(
                    SecurityException.class,
                    () -> previews.resolve(
                            "test",
                            DocumentReference.attachment(workspace.id(), invalid.id(), file),
                            new CancellationSource()));
        }
    }

    @Test
    void 未完成调用和失败重复身份不能为成功结果签发附件权限() throws Exception {
        var source = message(permission(1, List.of(workspace.root())), "浏览器下载", List.of());
        AttachmentRef file = attachment(new byte[] {1, 2, 3});
        ThreadId thread = core.findTurn(source.turnId()).orElseThrow().threadId();
        for (var status : List.of(ItemStatus.IN_PROGRESS, ItemStatus.CANCELLED, ItemStatus.FAILED)) {
            String id = UUID.randomUUID().toString();
            appendCall(source, id, status);
            ItemEnvelope result = append(
                    source,
                    CoreSchemas.TOOL_RESULT,
                    new CorePayloads.ToolResult(id, true, json.encode(output(thread, file)), Optional.empty()));
            assertThrows(
                    SecurityException.class,
                    () -> previews.resolve(
                            "test",
                            DocumentReference.attachment(workspace.id(), result.id(), file),
                            new CancellationSource()));
        }
        String duplicate = UUID.randomUUID().toString();
        appendCall(source, duplicate, ItemStatus.COMPLETED);
        ItemEnvelope result = append(
                source,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(duplicate, true, json.encode(output(thread, file)), Optional.empty()));
        // 不能在 SQL 中过滤未完成状态，否则后到的失败重复身份会被隐藏。
        appendCall(source, duplicate, ItemStatus.FAILED);
        assertThrows(
                SecurityException.class,
                () -> previews.resolve(
                        "test",
                        DocumentReference.attachment(workspace.id(), result.id(), file),
                        new CancellationSource()));
    }

    private void appendCall(ItemEnvelope source, String callId, ItemStatus status) {
        var call = new CorePayloads.ToolCall(callId, BuiltinExtensionIds.SITE, "browser_act", 1, json.encode(Map.of()));
        new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock)
                .append(source.turnId(), CoreSchemas.TOOL_CALL, CoreSchemas.TOOL_CALL, call, status);
    }

    private ItemEnvelope result(ItemEnvelope source, String producer, boolean success, BrowserResult output) {
        String call = UUID.randomUUID().toString();
        append(
                source,
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall(call, producer, "browser_act", 1, json.encode(Map.of())));
        return append(
                source,
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(call, success, json.encode(output), Optional.empty()));
    }

    private BrowserResult output(ThreadId thread, AttachmentRef file) {
        URI uri = URI.create("https://example.com");
        var owner = new BrowserContracts.Owner(workspace.id(), thread, Optional.empty());
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT,
                "lease",
                1,
                clock.instant().plusSeconds(60),
                Set.of(uri));
        var session = new BrowserContracts.SessionView(
                UUID.randomUUID().toString(), owner, BrowserContracts.SessionState.OPEN, lease, List.of());
        var page = new BrowserContracts.PageSnapshot("page", uri, "页面", "", List.of(), List.of());
        var artifact = new BrowserContracts.Artifact(
                new BrowserContracts.FileSpec(file.fileName(), file.mediaType()), file.sizeBytes());
        return new BrowserResult(
                1,
                new BrowserContracts.Observation(session, page, Optional.empty(), Optional.of(artifact)),
                Optional.of(file));
    }
}
