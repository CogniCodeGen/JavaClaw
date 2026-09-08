package com.javaclaw.desktop.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextPagesTest {
    @Test
    void 任意字节边界保留BOM中文代理对与真实行号() throws Exception {
        byte[] bytes = ("\ufeff第一行😀\r\n第二行").getBytes(StandardCharsets.UTF_8);
        Fixture gateway = new Fixture(bytes, 1, false);
        var result = new DocumentTextPages(gateway.preview, gateway).next();
        assertTrue(result.complete());
        assertEquals(2, result.lines().size());
        assertEquals("第一行😀", result.lines().getFirst().text());
        assertEquals(2, result.lines().getLast().number());
    }

    @Test
    void 长行分段不超过16KiB且分页不改变真实行号() throws Exception {
        String longLine = "中😀".repeat(10_000);
        Fixture gateway =
                new Fixture((longLine + "\n" + "一行\n".repeat(510)).getBytes(StandardCharsets.UTF_8), 256 * 1024, false);
        DocumentTextPages reader = new DocumentTextPages(gateway.preview, gateway);
        var first = reader.next();
        assertEquals(500, first.lines().size());
        StringBuilder reconstructed = new StringBuilder();
        first.lines().stream().filter(line -> line.number() == 1).forEach(line -> {
            assertTrue(line.text().getBytes(StandardCharsets.UTF_8).length <= 16_384);
            reconstructed.append(line.text());
        });
        assertEquals(longLine, reconstructed.toString());
        var second = reader.next();
        assertTrue(second.complete());
        assertTrue(second.lines().getFirst().number() > 490);
    }

    @Test
    void 末块摘要不符拒绝展示损坏内容() throws Exception {
        Fixture gateway = new Fixture("hello".getBytes(StandardCharsets.UTF_8), 3, true);
        assertThrows(IllegalStateException.class, () -> new DocumentTextPages(gateway.preview, gateway).next());
    }

    private static final class Fixture implements DocumentPreviewGateway {
        private final byte[] bytes;
        private final int chunkSize;
        private final DocumentPreview preview;

        private Fixture(byte[] bytes, int chunkSize, boolean corrupt) throws Exception {
            this.bytes = bytes;
            this.chunkSize = chunkSize;
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            preview = new DocumentPreview(
                    java.util.UUID.randomUUID().toString(),
                    WorkspaceId.random(),
                    "document.txt",
                    "text/plain",
                    bytes.length,
                    corrupt ? "0".repeat(64) : digest,
                    DocumentPreview.Origin.REFERENCED_VERSION,
                    Instant.now().plusSeconds(300),
                    Optional.empty(),
                    false);
        }

        @Override
        public CompletionStage<DocumentPreview> resolve(DocumentReference reference) {
            return CompletableFuture.completedFuture(preview);
        }

        @Override
        public CompletionStage<DocumentChunk> read(String handle, long offset) {
            int end = Math.min(bytes.length, (int) offset + chunkSize);
            return CompletableFuture.completedFuture(new DocumentChunk(
                    offset, Arrays.copyOfRange(bytes, (int) offset, end), end, end == bytes.length, preview.digest()));
        }

        @Override
        public CompletionStage<DocumentPreview> resource(String handle, String href) {
            return CompletableFuture.failedFuture(new IllegalStateException("没有相对资源"));
        }

        @Override
        public CompletionStage<DocumentPreview> renew(String handle) {
            return CompletableFuture.completedFuture(preview);
        }

        @Override
        public CompletionStage<Void> close(String handle) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
