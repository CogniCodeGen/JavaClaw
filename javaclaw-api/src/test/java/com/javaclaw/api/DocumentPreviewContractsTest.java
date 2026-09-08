package com.javaclaw.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentPreviewContractsTest {
    private final WorkspaceId workspace = WorkspaceId.random();
    private final ItemId item = ItemId.random();
    private final AttachmentRef attachment = new AttachmentRef("a".repeat(64), "text/plain", "note.txt", 3);

    @Test
    void 引用工厂保留准确来源而没有宿主路径() {
        assertTrue(DocumentReference.attachment(workspace, attachment)
                .sourceItemId()
                .isEmpty());
        assertEquals(
                Optional.of(item),
                DocumentReference.attachment(workspace, item, attachment).sourceItemId());
        assertEquals("body", DocumentReference.message(workspace, item, "body").selector());
        assertEquals(
                "fence:999999",
                DocumentReference.message(workspace, item, "fence:999999").selector());
        assertEquals("link:0", DocumentReference.file(workspace, item, "link:0").selector());
        assertEquals(
                "file:999999",
                DocumentReference.file(workspace, item, "file:999999").selector());
    }

    @Test
    void 引用拒绝缺少来源跨类别字段和任意路径() {
        for (DocumentReference.Kind kind : DocumentReference.Kind.values()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DocumentReference(kind, workspace, Optional.empty(), "", Optional.empty()));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentReference(
                        DocumentReference.Kind.ATTACHMENT,
                        workspace,
                        Optional.empty(),
                        "body",
                        Optional.of(attachment)));
        for (String selector : new String[] {"/tmp/private", "../data", "link:-1", "file:1000000", "body"}) {
            assertThrows(IllegalArgumentException.class, () -> DocumentReference.file(workspace, item, selector));
        }
        for (String selector : new String[] {"file:0", "fence:-1", "fence:1000000", "https://example.com"}) {
            assertThrows(IllegalArgumentException.class, () -> DocumentReference.message(workspace, item, selector));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentReference(
                        DocumentReference.Kind.WORKSPACE_FILE,
                        workspace,
                        Optional.of(item),
                        "file:0",
                        Optional.of(attachment)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentReference(
                        DocumentReference.Kind.MESSAGE_CONTENT,
                        workspace,
                        Optional.of(item),
                        "body",
                        Optional.of(attachment)));
    }

    @Test
    void 预览元信息限制内容预算并区分当前文件和历史版本() {
        assertEquals(0, preview(0, Optional.empty()).sizeBytes());
        assertEquals(
                64L * 1024 * 1024, preview(64L * 1024 * 1024, Optional.of(1)).sizeBytes());
        assertTrue(preview(1, Optional.of(2)).changed());
        assertThrows(IllegalArgumentException.class, () -> preview(-1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> preview(64L * 1024 * 1024 + 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> preview(0, Optional.of(0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentPreview(
                        "bad",
                        workspace,
                        "file",
                        "text/plain",
                        0,
                        "a".repeat(64),
                        DocumentPreview.Origin.REFERENCED_VERSION,
                        Instant.EPOCH,
                        Optional.empty(),
                        false));
    }

    @Test
    void 分块防御性复制且必须推进真实字节位置() {
        byte[] source = {1, 2, 3};
        var chunk = new DocumentChunk(4, source, 7, false, "a".repeat(64));
        source[0] = 9;
        byte[] exposed = chunk.content();
        exposed[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, chunk.content());
        assertEquals(7, chunk.nextOffsetBytes());
        assertTrue(new DocumentChunk(7, new byte[0], 7, true, "a".repeat(64)).complete());
        assertThrows(IllegalArgumentException.class, () -> chunk(-1, 2, 1, true));
        assertThrows(IllegalArgumentException.class, () -> chunk(2, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> chunk(0, 256 * 1024 + 1, 256 * 1024 + 1, true));
        assertThrows(IllegalArgumentException.class, () -> chunk(2, 1, 4, true));
        assertThrows(IllegalArgumentException.class, () -> chunk(2, 0, 2, false));
        assertThrows(IllegalArgumentException.class, () -> new DocumentChunk(0, new byte[1], 1, true, "not-digest"));
    }

    private DocumentPreview preview(long size, Optional<Integer> line) {
        return new DocumentPreview(
                UUID.randomUUID().toString(),
                workspace,
                "file",
                "text/plain",
                size,
                "a".repeat(64),
                DocumentPreview.Origin.CURRENT_FILE,
                Instant.EPOCH,
                line,
                true);
    }

    private static DocumentChunk chunk(long offset, int length, long next, boolean complete) {
        return new DocumentChunk(offset, new byte[length], next, complete, "a".repeat(64));
    }
}
