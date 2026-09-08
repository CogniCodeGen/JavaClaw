package com.javaclaw.protocol;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentPreviewRpcContractsTest {
    private final CanonicalJson json = new CanonicalJson();
    private final String handle = UUID.randomUUID().toString();

    @Test
    void 所有预览控制记录保持严格wire形状和连接句柄() {
        var reference = DocumentReference.message(WorkspaceId.random(), ItemId.random(), "body");
        roundtrip(new DocumentPreviewRpcContracts.ResolvePayload(reference));
        roundtrip(new DocumentPreviewRpcContracts.HandlePayload(handle));
        roundtrip(new DocumentPreviewRpcContracts.ReadPayload(handle, 3, 256 * 1024));
        roundtrip(new DocumentPreviewRpcContracts.ResourcePayload(handle, "assets/logo.png"));
        roundtrip(new DocumentPreviewRpcContracts.CloseResult(handle, true));
        for (String reason : new String[] {"EXPIRED", "REVOKED", "CLOSED", "CORRUPT"}) {
            roundtrip(new DocumentPreviewRpcContracts.Invalidated(handle, reason));
        }
        assertThrows(
                ProtocolException.class,
                () -> json.decode(
                        json.parse("{\"handleId\":\"" + handle + "\",\"path\":\"/tmp/private\"}"),
                        DocumentPreviewRpcContracts.HandlePayload.class));
    }

    @Test
    void 预览输入拒绝大帧空资源非UUID和未确认关闭() {
        assertThrows(NullPointerException.class, () -> new DocumentPreviewRpcContracts.ResolvePayload(null));
        assertThrows(IllegalArgumentException.class, () -> new DocumentPreviewRpcContracts.HandlePayload("bad"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentPreviewRpcContracts.ReadPayload(handle, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DocumentPreviewRpcContracts.ReadPayload(handle, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentPreviewRpcContracts.ReadPayload(handle, 0, 256 * 1024 + 1));
        assertThrows(
                IllegalArgumentException.class, () -> new DocumentPreviewRpcContracts.ResourcePayload(handle, " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentPreviewRpcContracts.ResourcePayload(handle, "x".repeat(4097)));
        assertEquals(
                4096,
                new DocumentPreviewRpcContracts.ResourcePayload(handle, "x".repeat(4096))
                        .href()
                        .length());
        assertThrows(IllegalArgumentException.class, () -> new DocumentPreviewRpcContracts.CloseResult(handle, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentPreviewRpcContracts.Invalidated(handle, "PATH_CHANGED"));
    }

    @Test
    void 附件分块请求也使用有界字节游标() {
        var scope = AttachmentScope.workspace(WorkspaceId.random());
        roundtrip(new AttachmentRpcContracts.DownloadChunkPayload(scope, "a".repeat(64), 0, 256 * 1024));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentRpcContracts.DownloadChunkPayload(scope, "bad", 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentRpcContracts.DownloadChunkPayload(scope, "a".repeat(64), -1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentRpcContracts.DownloadChunkPayload(scope, "a".repeat(64), 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AttachmentRpcContracts.DownloadChunkPayload(scope, "a".repeat(64), 0, 256 * 1024 + 1));
    }

    private void roundtrip(Object value) {
        assertEquals(value, json.decode(json.encode(value), value.getClass()));
    }
}
