package com.javaclaw.client.facade;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.DocumentPreviewRpcContracts;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentPreviewClientTest {
    private final CanonicalJson json = new CanonicalJson();
    private final WorkspaceId workspace = WorkspaceId.random();
    private final String handle = UUID.randomUUID().toString();
    private final String digest = "a".repeat(64);

    @Test
    void 预览控制携带零revision且分块查询保留真实字节位置() throws Exception {
        try (RpcClientConnection connection =
                new RpcClientConnection(new ScriptedRpcConnection(this::response), json, ignored -> {})) {
            DocumentPreviewClient client = new DocumentPreviewClient(connection);
            var options = new CommandOptions("control", 0);
            var reference = DocumentReference.message(workspace, ItemId.random(), "body");
            assertEquals(handle, client.resolve(reference, options).handleId());
            assertEquals(
                    handle,
                    client.resolveResource(handle, "assets/logo.png", options).handleId());
            assertEquals(handle, client.renew(handle, options).handleId());
            assertEquals(handle, client.close(handle, options).handleId());
            var chunk = client.readChunk(handle, 3, 100);
            assertEquals(3, chunk.offsetBytes());
            assertEquals(6, chunk.nextOffsetBytes());
            assertArrayEquals(new byte[] {1, 2, 3}, chunk.content());
            assertThrows(IllegalArgumentException.class, () -> client.resolve(reference, new CommandOptions("bad", 1)));
            assertThrows(NullPointerException.class, () -> client.renew(handle, null));
        }
    }

    @Test
    void 失效通知按类型过滤且关闭监听器后不再投递() throws Exception {
        var notifications = new LinkedBlockingQueue<String>();
        var invalidations = new LinkedBlockingQueue<DocumentPreviewRpcContracts.Invalidated>();
        var wire = new ScriptedRpcConnection(this::response);
        try (RpcClientConnection connection =
                new RpcClientConnection(wire, json, value -> notifications.add(value.method()))) {
            DocumentPreviewClient client = new DocumentPreviewClient(connection);
            var listener = client.onInvalidated(invalidations::add);
            wire.emit(new JsonRpcNotification("other/event", json.parse("{}")));
            assertEquals("other/event", notifications.poll(2, TimeUnit.SECONDS));
            assertNull(invalidations.poll());
            var event = new DocumentPreviewRpcContracts.Invalidated(handle, "REVOKED");
            wire.emit(new JsonRpcNotification(DocumentPreviewRpcContracts.INVALIDATED, json.encode(event)));
            assertEquals(event, invalidations.poll(2, TimeUnit.SECONDS));
            assertNotNull(notifications.poll(2, TimeUnit.SECONDS));
            listener.close();
            wire.emit(new JsonRpcNotification(DocumentPreviewRpcContracts.INVALIDATED, json.encode(event)));
            assertNotNull(notifications.poll(2, TimeUnit.SECONDS));
            assertNull(invalidations.poll());
        }
    }

    @Test
    void 附件metadata与分块不请求旧的全量read() throws Exception {
        var scope = AttachmentScope.workspace(workspace);
        var metadata = new AttachmentMetadata(digest, "text/plain", 100, Instant.EPOCH);
        var wire = new ScriptedRpcConnection(request -> {
            if (request.method().equals("attachment/metadata")) {
                var payload = json.decode(request.params(), AttachmentRpcContracts.ReadPayload.class);
                assertEquals(scope, payload.scope());
                return JsonRpcResponse.success(request.id(), json.encode(metadata));
            }
            assertEquals("attachment/readChunk", request.method());
            var payload = json.decode(request.params(), AttachmentRpcContracts.DownloadChunkPayload.class);
            assertEquals(3, payload.offsetBytes());
            return JsonRpcResponse.success(
                    request.id(), json.encode(new DocumentChunk(3, new byte[] {1}, 4, false, digest)));
        });
        try (RpcClientConnection connection = new RpcClientConnection(wire, json, ignored -> {})) {
            AttachmentClient client = new AttachmentClient(connection);
            assertEquals(metadata, client.metadata(scope, digest));
            assertEquals(4, client.readChunk(scope, digest, 3, 100).nextOffsetBytes());
        }
    }

    private JsonRpcResponse response(JsonRpcRequest request) {
        if (request.method().equals(DocumentPreviewRpcContracts.READ)) {
            var payload = json.decode(request.params(), DocumentPreviewRpcContracts.ReadPayload.class);
            assertEquals(3, payload.offsetBytes());
            return JsonRpcResponse.success(
                    request.id(), json.encode(new DocumentChunk(3, new byte[] {1, 2, 3}, 6, true, digest)));
        }
        var command = json.decode(request.params(), WriteCommand.class);
        assertEquals(0, command.expectedRevision());
        if (request.method().equals(DocumentPreviewRpcContracts.CLOSE)) {
            return JsonRpcResponse.success(
                    request.id(), json.encode(new DocumentPreviewRpcContracts.CloseResult(handle, true)));
        }
        validateControlPayload(request.method(), command);
        return JsonRpcResponse.success(
                request.id(),
                json.encode(new DocumentPreview(
                        handle,
                        workspace,
                        "file",
                        "text/plain",
                        6,
                        digest,
                        DocumentPreview.Origin.REFERENCED_VERSION,
                        Instant.EPOCH,
                        Optional.empty(),
                        false)));
    }

    private void validateControlPayload(String method, WriteCommand command) {
        switch (method) {
            case DocumentPreviewRpcContracts.RESOLVE -> {
                var payload = json.decode(command.payload(), DocumentPreviewRpcContracts.ResolvePayload.class);
                assertEquals(workspace, payload.reference().workspaceId());
                assertEquals(
                        DocumentReference.Kind.MESSAGE_CONTENT,
                        payload.reference().kind());
            }
            case DocumentPreviewRpcContracts.RESOURCE -> {
                var payload = json.decode(command.payload(), DocumentPreviewRpcContracts.ResourcePayload.class);
                assertEquals(handle, payload.parentHandleId());
                assertEquals("assets/logo.png", payload.href());
            }
            case DocumentPreviewRpcContracts.RENEW ->
                assertEquals(
                        handle,
                        json.decode(command.payload(), DocumentPreviewRpcContracts.HandlePayload.class)
                                .handleId());
            default -> throw new AssertionError("unexpected preview control: " + method);
        }
    }
}
