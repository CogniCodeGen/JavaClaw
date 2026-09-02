package com.javaclaw.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.AttachmentUploadState;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.AttachmentClient;
import com.javaclaw.client.facade.AttachmentUploadOptions;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final AttachmentScope SCOPE = AttachmentScope.workspace(new WorkspaceId(new UUID(0, 42)));

    @Test
    void upload按固定上限分块且绝不发送本地路径(@TempDir Path temporary) throws Exception {
        byte[] bytes = new byte[AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES + 19];
        Arrays.fill(bytes, (byte) 7);
        Path source = Files.write(temporary.resolve("guide.bin"), bytes);
        AttachmentUploadScript script = new AttachmentUploadScript(source, "application/octet-stream", bytes);
        AttachmentClient attachments = client(script);

        AttachmentRef uploaded = attachments.upload(
                source,
                new AttachmentUploadOptions(
                        SCOPE,
                        "application/octet-stream",
                        bytes.length,
                        new CancellationSource(),
                        new CommandOptions("upload", 0)));

        assertEquals(new AttachmentRef(script.digest, "application/octet-stream", "guide.bin", bytes.length), uploaded);
        assertEquals(2, script.chunkCount);
        assertTrue(script.maximumChunkBytes <= AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES);
    }

    @Test
    void upload在RPC前拒绝已取消任务和越界文件(@TempDir Path temporary) throws Exception {
        Path source = Files.writeString(temporary.resolve("guide.txt"), "architecture");
        AttachmentClient attachments = client(request -> {
            throw new AssertionError("cancelled or oversized upload must not call RPC");
        });
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("test cancellation");

        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> attachments.upload(
                        source,
                        new AttachmentUploadOptions(
                                SCOPE, "text/plain", 1024, cancelled, new CommandOptions("cancelled", 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> attachments.upload(
                        source,
                        new AttachmentUploadOptions(
                                SCOPE, "text/plain", 4, new CancellationSource(), new CommandOptions("oversized", 0))));
    }

    @Test
    void upload在流式取消后中止服务端会话(@TempDir Path temporary) throws Exception {
        byte[] bytes = new byte[AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES + 1];
        Path source = Files.write(temporary.resolve("cancel.bin"), bytes);
        CancellationSource cancellation = new CancellationSource();
        AttachmentUploadScript script =
                new AttachmentUploadScript(source, "application/octet-stream", bytes, cancellation);

        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> client(script)
                        .upload(
                                source,
                                new AttachmentUploadOptions(
                                        SCOPE,
                                        "application/octet-stream",
                                        bytes.length,
                                        cancellation,
                                        new CommandOptions("cancel-mid-stream", 0))));
        assertTrue(script.aborted);
        assertEquals(1, script.chunkCount);
    }

    @Test
    void read始终携带调用方指定的WorkspaceScope() throws Exception {
        byte[] content = "owned attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = digest(content);
        AttachmentMetadata metadata =
                new AttachmentMetadata(digest, "text/plain", content.length, Instant.parse("2026-09-01T00:00:00Z"));
        AttachmentClient attachments = client(request -> {
            assertEquals("attachment/read", request.method());
            AttachmentRpcContracts.ReadPayload payload =
                    JSON.decode(request.params(), AttachmentRpcContracts.ReadPayload.class);
            assertEquals(SCOPE, payload.scope());
            assertEquals(digest, payload.digest());
            return JsonRpcResponse.success(request.id(), JSON.encode(new AttachmentContent(metadata, content)));
        });

        AttachmentContent read = attachments.read(SCOPE, digest);

        assertEquals(metadata, read.metadata());
        assertArrayEquals(content, read.content());
    }

    @Test
    void uploadMetadata复用已完成会话且不重复发送内容(@TempDir Path temporary) throws Exception {
        byte[] content = "already uploaded".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = Files.write(temporary.resolve("existing.txt"), content);
        String digest = digest(content);
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        AttachmentMetadata metadata = new AttachmentMetadata(digest, "text/plain", content.length, now);
        AttachmentUploadSession completed = new AttachmentUploadSession(
                "00000000-0000-0000-0000-000000000002",
                SCOPE,
                AttachmentUploadState.COMPLETED,
                "text/plain",
                digest,
                content.length,
                content.length,
                1,
                2,
                now,
                now,
                now.plus(Duration.ofMinutes(30)),
                Optional.of(metadata),
                Optional.empty());
        AttachmentClient attachments = client(request -> switch (request.method()) {
            case "attachment/upload/begin", "attachment/upload/read" ->
                JsonRpcResponse.success(request.id(), JSON.encode(completed));
            default -> throw new AssertionError("completed upload must not send " + request.method());
        });

        AttachmentMetadata uploaded = attachments.uploadMetadata(
                source,
                new AttachmentUploadOptions(
                        SCOPE,
                        "text/plain",
                        content.length,
                        new CancellationSource(),
                        new CommandOptions("existing", 0)));

        assertEquals(metadata, uploaded);
    }

    @Test
    void upload在RPC前拒绝非法选项和非普通文件(@TempDir Path temporary) throws Exception {
        AttachmentClient attachments = client(request -> {
            throw new AssertionError("invalid local upload must not call RPC");
        });
        CancellationSource cancellation = new CancellationSource();

        assertThrows(IllegalArgumentException.class, () -> options("a".repeat(240) + "/b", 1, 0, cancellation));
        assertThrows(IllegalArgumentException.class, () -> options("text plain", 1, 0, cancellation));
        assertThrows(IllegalArgumentException.class, () -> options("text/plain", 0, 0, cancellation));
        assertThrows(
                IllegalArgumentException.class,
                () -> options("text/plain", AttachmentRpcContracts.MAX_ATTACHMENT_BYTES + 1, 0, cancellation));
        assertThrows(IllegalArgumentException.class, () -> options("text/plain", 1, 1, cancellation));

        AttachmentUploadOptions valid = options("text/plain", 1024, 0, cancellation);
        Path directory = Files.createDirectory(temporary.resolve("directory"));
        assertThrows(IllegalArgumentException.class, () -> attachments.upload(directory, valid));
        Path target = Files.writeString(temporary.resolve("target.txt"), "target");
        Path link = Files.createSymbolicLink(temporary.resolve("link.txt"), target.getFileName());
        assertThrows(IllegalArgumentException.class, () -> attachments.upload(link, valid));
    }

    private static AttachmentUploadOptions options(
            String mediaType, long maximumBytes, long expectedRevision, CancellationSource cancellation) {
        return new AttachmentUploadOptions(
                SCOPE, mediaType, maximumBytes, cancellation, new CommandOptions("local-validation", expectedRevision));
    }

    private static AttachmentClient client(Function<JsonRpcRequest, JsonRpcResponse> handler) {
        return new AttachmentClient(new RpcClientConnection(new ScriptedConnection(handler), JSON, ignored -> {}));
    }

    private static String digest(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static final class AttachmentUploadScript implements Function<JsonRpcRequest, JsonRpcResponse> {
        private static final String UPLOAD_ID = "00000000-0000-0000-0000-000000000001";
        private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

        private final Path source;
        private final String mediaType;
        private final byte[] expected;
        private final String digest;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CancellationSource cancellationAfterFirstChunk;
        private AttachmentUploadSession session;
        private int chunkCount;
        private int maximumChunkBytes;
        private boolean aborted;

        private AttachmentUploadScript(Path source, String mediaType, byte[] expected) throws Exception {
            this(source, mediaType, expected, null);
        }

        private AttachmentUploadScript(
                Path source, String mediaType, byte[] expected, CancellationSource cancellationAfterFirstChunk)
                throws Exception {
            this.source = source;
            this.mediaType = mediaType;
            this.expected = expected.clone();
            this.cancellationAfterFirstChunk = cancellationAfterFirstChunk;
            digest = AttachmentClientTest.digest(expected);
        }

        @Override
        public JsonRpcResponse apply(JsonRpcRequest request) {
            assertFalse(request.params().json().contains(source.toAbsolutePath().toString()));
            return switch (request.method()) {
                case "attachment/upload/begin" -> begin(request);
                case "attachment/upload/read" -> read(request);
                case "attachment/upload/chunk" -> append(request);
                case "attachment/upload/complete" -> complete(request);
                case "attachment/upload/abort" -> abort(request);
                default -> throw new AssertionError("unexpected Attachment method " + request.method());
            };
        }

        private JsonRpcResponse begin(JsonRpcRequest request) {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            AttachmentRpcContracts.BeginPayload payload =
                    JSON.decode(command.payload(), AttachmentRpcContracts.BeginPayload.class);
            assertEquals(mediaType, payload.mediaType());
            assertEquals(SCOPE, payload.scope());
            assertEquals(digest, payload.expectedDigest());
            assertEquals(expected.length, payload.expectedSizeBytes());
            session = activeSession(0, 0, 1);
            return success(request, session);
        }

        private JsonRpcResponse append(JsonRpcRequest request) {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            AttachmentRpcContracts.ChunkPayload payload =
                    JSON.decode(command.payload(), AttachmentRpcContracts.ChunkPayload.class);
            assertEquals(SCOPE, payload.scope());
            assertEquals(session.revision(), command.expectedRevision());
            assertEquals(session.nextChunkIndex(), payload.chunkIndex());
            received.writeBytes(payload.content());
            chunkCount++;
            maximumChunkBytes = Math.max(maximumChunkBytes, payload.content().length);
            session = activeSession(received.size(), chunkCount, session.revision() + 1);
            cancelAfterFirstChunk();
            return success(request, session);
        }

        private void cancelAfterFirstChunk() {
            if (cancellationAfterFirstChunk != null && chunkCount == 1) {
                cancellationAfterFirstChunk.cancel("test mid-stream cancellation");
            }
        }

        private JsonRpcResponse complete(JsonRpcRequest request) {
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            AttachmentRpcContracts.CompletePayload payload =
                    JSON.decode(command.payload(), AttachmentRpcContracts.CompletePayload.class);
            assertEquals(SCOPE, payload.scope());
            assertTrue(Arrays.equals(expected, received.toByteArray()));
            return success(request, new AttachmentMetadata(digest, mediaType, expected.length, NOW));
        }

        private JsonRpcResponse abort(JsonRpcRequest request) {
            if (cancellationAfterFirstChunk == null) {
                throw new AssertionError("successful upload must not abort");
            }
            WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
            AttachmentRpcContracts.AbortPayload payload =
                    JSON.decode(command.payload(), AttachmentRpcContracts.AbortPayload.class);
            assertEquals(SCOPE, payload.scope());
            assertEquals(session.revision(), command.expectedRevision());
            aborted = true;
            session = terminalSession();
            return success(request, session);
        }

        private JsonRpcResponse read(JsonRpcRequest request) {
            AttachmentRpcContracts.UploadReadPayload payload =
                    JSON.decode(request.params(), AttachmentRpcContracts.UploadReadPayload.class);
            assertEquals(SCOPE, payload.scope());
            return success(request, session);
        }

        private AttachmentUploadSession terminalSession() {
            return new AttachmentUploadSession(
                    UPLOAD_ID,
                    SCOPE,
                    AttachmentUploadState.ABORTED,
                    mediaType,
                    digest,
                    expected.length,
                    received.size(),
                    chunkCount,
                    session.revision() + 1,
                    NOW,
                    NOW,
                    NOW.plus(Duration.ofMinutes(30)),
                    Optional.empty(),
                    Optional.of("客户端上传已取消"));
        }

        private AttachmentUploadSession activeSession(long receivedBytes, int nextChunk, long revision) {
            return new AttachmentUploadSession(
                    UPLOAD_ID,
                    SCOPE,
                    AttachmentUploadState.ACTIVE,
                    mediaType,
                    digest,
                    expected.length,
                    receivedBytes,
                    nextChunk,
                    revision,
                    NOW,
                    NOW,
                    NOW.plus(Duration.ofMinutes(30)),
                    Optional.empty(),
                    Optional.empty());
        }

        private static JsonRpcResponse success(JsonRpcRequest request, Object value) {
            return JsonRpcResponse.success(request.id(), JSON.encode(value));
        }
    }

    private static final class ScriptedConnection implements RpcConnection {
        private static final Object CLOSED = new Object();

        private final Function<JsonRpcRequest, JsonRpcResponse> handler;
        private final BlockingQueue<Object> inbound = new LinkedBlockingQueue<>();

        private ScriptedConnection(Function<JsonRpcRequest, JsonRpcResponse> handler) {
            this.handler = handler;
        }

        @Override
        public void send(JsonRpcMessage message) {
            inbound.add(handler.apply((JsonRpcRequest) message));
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            try {
                Object next = inbound.take();
                if (next == CLOSED) {
                    throw new IOException("scripted connection is closed");
                }
                return (JsonRpcMessage) next;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("scripted receive interrupted", interrupted);
            }
        }

        @Override
        public void close() {
            inbound.offer(CLOSED);
        }
    }
}
