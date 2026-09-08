package com.javaclaw.client.facade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;
import com.javaclaw.api.AttachmentUploadState;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.AttachmentRpcContracts;

/** 内容寻址 Attachment Core 方法的强类型 facade。 */
public final class AttachmentClient {
    private static final int SCAN_BUFFER_BYTES = 64 * 1024;

    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public AttachmentClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 有界预扫描并分块上传一个本地普通文件。
     *
     * <p>本地绝对路径不会进入 RPC。wire 的单个 Base64 chunk 硬上限为 256 KiB；服务端只有在总大小和 SHA-256 与预扫描声明完全一致后才提交
     * Attachment。取消或本地异常会尽力中止会话，断线残留由服务端超时回收。
     *
     * @param source 本地普通文件；不接受符号链接
     * @param options 媒体类型、大小、取消和稳定幂等键
     * @return 不包含宿主路径的 Attachment 引用
     */
    public AttachmentRef upload(Path source, AttachmentUploadOptions options) {
        Path selected = checkedPath(source);
        AttachmentMetadata stored = uploadVerified(selected, options);
        return new AttachmentRef(
                stored.digest(), stored.mediaType(), selected.getFileName().toString(), stored.sizeBytes());
    }

    /**
     * 有界预扫描并分块上传本地文件，返回服务端持久元数据。
     *
     * <p>适用于 Bundle 等只需要 Core Attachment 摘要、媒体类型和大小的 SDK 组合；本地文件名和绝对路径都不会进入 RPC。
     *
     * @param source 本地普通文件；不接受符号链接
     * @param options 媒体类型、大小、取消和稳定幂等键
     * @return 服务端校验并提交的 Attachment 元数据
     */
    public AttachmentMetadata uploadMetadata(Path source, AttachmentUploadOptions options) {
        return uploadVerified(checkedPath(source), options);
    }

    private AttachmentMetadata uploadVerified(Path selected, AttachmentUploadOptions options) {
        AttachmentUploadOptions checked = Objects.requireNonNull(options, "options");
        LocalDescriptor local = scan(selected, checked);
        AttachmentUploadSession session = null;
        try {
            session = begin(local, checked);
            session = current(checked.scope(), session.id());
            requireScope(session, checked.scope());
            requireDeclared(session, local, checked.mediaType());
            AttachmentMetadata stored = session.state() == AttachmentUploadState.COMPLETED
                    ? session.attachment().orElseThrow()
                    : uploadAndComplete(selected, local, checked, session);
            requireSame(local, stored, checked.mediaType());
            return stored;
        } catch (RuntimeException failure) {
            abortQuietly(session);
            throw failure;
        }
    }

    /**
     * 按 SHA-256 摘要读取附件。
     *
     * @param scope 要核验的显式所有权范围
     * @param digest 内容摘要
     * @return 元数据与原始字节
     */
    public AttachmentContent read(AttachmentScope scope, String digest) {
        return connection.query(
                "attachment/read",
                new AttachmentRpcContracts.ReadPayload(Objects.requireNonNull(scope, "scope"), digest),
                AttachmentContent.class);
    }

    private AttachmentUploadSession begin(LocalDescriptor local, AttachmentUploadOptions options) {
        AttachmentRpcContracts.BeginPayload payload = new AttachmentRpcContracts.BeginPayload(
                options.scope(), options.mediaType(), local.digest(), local.sizeBytes());
        return connection.command(
                "attachment/upload/begin",
                payload,
                derived(options.command(), "begin", 0),
                AttachmentUploadSession.class);
    }

    private AttachmentMetadata uploadAndComplete(
            Path source, LocalDescriptor local, AttachmentUploadOptions options, AttachmentUploadSession initial) {
        if (initial.state() != AttachmentUploadState.ACTIVE) {
            throw new IllegalStateException("Attachment upload session is not active: " + initial.state());
        }
        AttachmentUploadSession uploaded = streamChunks(source, local, options, initial);
        options.cancellation().throwIfCancelled();
        requireStableSize(source, local.sizeBytes());
        return connection.command(
                "attachment/upload/complete",
                new AttachmentRpcContracts.CompletePayload(options.scope(), uploaded.id()),
                derived(options.command(), "complete", uploaded.revision()),
                AttachmentMetadata.class);
    }

    private AttachmentUploadSession streamChunks(
            Path source, LocalDescriptor local, AttachmentUploadOptions options, AttachmentUploadSession initial) {
        try (SeekableByteChannel channel = open(source)) {
            channel.position(initial.receivedSizeBytes());
            AttachmentUploadSession current = initial;
            while (current.receivedSizeBytes() < local.sizeBytes()) {
                options.cancellation().throwIfCancelled();
                int maximum = Math.toIntExact(Math.min(
                        AttachmentRpcContracts.MAX_ATTACHMENT_CHUNK_BYTES,
                        local.sizeBytes() - current.receivedSizeBytes()));
                byte[] chunk = readChunk(channel, maximum);
                current = append(current, chunk, options.command());
            }
            return current;
        } catch (IOException failure) {
            throw new UncheckedIOException("Attachment source cannot be streamed", failure);
        }
    }

    private AttachmentUploadSession append(AttachmentUploadSession current, byte[] chunk, CommandOptions baseCommand) {
        AttachmentRpcContracts.ChunkPayload payload =
                new AttachmentRpcContracts.ChunkPayload(current.scope(), current.id(), current.nextChunkIndex(), chunk);
        AttachmentUploadSession advanced = connection.command(
                "attachment/upload/chunk",
                payload,
                derived(baseCommand, "chunk:" + current.nextChunkIndex(), current.revision()),
                AttachmentUploadSession.class);
        requireAdvanced(current, advanced, chunk.length);
        return advanced;
    }

    /**
     * 读取附件元信息，不下载正文。
     *
     * @param scope 所有权范围
     * @param digest 内容摘要
     * @return 已确认所有权的元信息
     */
    public AttachmentMetadata metadata(AttachmentScope scope, String digest) {
        return connection.query(
                "attachment/metadata", new AttachmentRpcContracts.ReadPayload(scope, digest), AttachmentMetadata.class);
    }

    /**
     * 下载有界块；调用方须以返回整体 digest 校验完整版本。
     *
     * @param scope 所有权范围
     * @param digest 内容摘要
     * @param offsetBytes 起始字节
     * @param maximumBytes 最多 256 KiB
     * @return 连续内容块
     */
    public com.javaclaw.api.DocumentChunk readChunk(
            AttachmentScope scope, String digest, long offsetBytes, int maximumBytes) {
        return connection.query(
                "attachment/readChunk",
                new AttachmentRpcContracts.DownloadChunkPayload(scope, digest, offsetBytes, maximumBytes),
                com.javaclaw.api.DocumentChunk.class);
    }

    private AttachmentUploadSession current(AttachmentScope scope, String uploadId) {
        return connection.query(
                "attachment/upload/read",
                new AttachmentRpcContracts.UploadReadPayload(scope, uploadId),
                AttachmentUploadSession.class);
    }

    private void abortQuietly(AttachmentUploadSession known) {
        if (known == null) {
            return;
        }
        try {
            AttachmentUploadSession current = current(known.scope(), known.id());
            if (current.state() != AttachmentUploadState.ACTIVE) {
                return;
            }
            connection.command(
                    "attachment/upload/abort",
                    new AttachmentRpcContracts.AbortPayload(current.scope(), current.id(), "客户端上传已取消"),
                    derived(new CommandOptions(known.id(), 0), "abort", current.revision()),
                    AttachmentUploadSession.class);
        } catch (RuntimeException ignored) {
            // 断线时服务端会按固定 TTL 回收；不得用另一个非受管通道清理。
        }
    }

    private static LocalDescriptor scan(Path source, AttachmentUploadOptions options) {
        try (SeekableByteChannel channel = open(source)) {
            long declaredSize = Files.size(source);
            requireSize(declaredSize, options.maximumBytes());
            MessageDigest digest = sha256();
            ByteBuffer buffer = ByteBuffer.allocate(SCAN_BUFFER_BYTES);
            long total = 0;
            while (channel.read(buffer) >= 0) {
                options.cancellation().throwIfCancelled();
                buffer.flip();
                total = Math.addExact(total, buffer.remaining());
                requireSize(total, options.maximumBytes());
                digest.update(buffer);
                buffer.clear();
            }
            if (total != declaredSize) {
                throw new IllegalArgumentException("Attachment source changed while it was being scanned");
            }
            options.cancellation().throwIfCancelled();
            return new LocalDescriptor(HexFormat.of().formatHex(digest.digest()), total);
        } catch (IOException failure) {
            throw new UncheckedIOException("Attachment source cannot be scanned", failure);
        }
    }

    private static SeekableByteChannel open(Path source) throws IOException {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        return Files.newByteChannel(source, options);
    }

    private static byte[] readChunk(SeekableByteChannel channel, int maximum) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(maximum);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) {
                break;
            }
        }
        if (buffer.position() < 1) {
            throw new IllegalArgumentException("Attachment source ended before its declared size");
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static Path checkedPath(Path source) {
        Path selected =
                Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(selected) || !Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Attachment source must be a regular file and not a symbolic link");
        }
        return selected;
    }

    private static void requireStableSize(Path source, long expected) {
        try {
            if (Files.size(source) != expected) {
                throw new IllegalArgumentException("Attachment source changed while it was being uploaded");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Attachment source size cannot be verified", failure);
        }
    }

    private static void requireSize(long size, long maximum) {
        if (size < 1 || size > maximum) {
            throw new IllegalArgumentException("Attachment size must be between 1 and " + maximum + " bytes");
        }
    }

    private static void requireDeclared(AttachmentUploadSession session, LocalDescriptor local, String mediaType) {
        if (!session.expectedDigest().equals(local.digest())
                || session.expectedSizeBytes() != local.sizeBytes()
                || !session.mediaType().equals(mediaType)) {
            throw new IllegalStateException("Server Attachment upload declaration differs from the local file");
        }
    }

    private static void requireScope(AttachmentUploadSession session, AttachmentScope expected) {
        if (!session.scope().equals(expected)) {
            throw new IllegalStateException("Server Attachment upload scope differs from the requested owner");
        }
    }

    private static void requireAdvanced(AttachmentUploadSession before, AttachmentUploadSession after, int chunkBytes) {
        requireDeclared(
                after, new LocalDescriptor(before.expectedDigest(), before.expectedSizeBytes()), before.mediaType());
        if (after.state() != AttachmentUploadState.ACTIVE
                || !after.id().equals(before.id())
                || !after.scope().equals(before.scope())
                || after.revision() != before.revision() + 1
                || after.nextChunkIndex() != before.nextChunkIndex() + 1
                || after.receivedSizeBytes() != before.receivedSizeBytes() + chunkBytes) {
            throw new IllegalStateException("Server returned an invalid Attachment upload transition");
        }
    }

    private static void requireSame(LocalDescriptor local, AttachmentMetadata stored, String mediaType) {
        if (!stored.digest().equals(local.digest())
                || stored.sizeBytes() != local.sizeBytes()
                || !stored.mediaType().equals(mediaType)) {
            throw new IllegalStateException("Server Attachment metadata differs from the uploaded local file");
        }
    }

    private static CommandOptions derived(CommandOptions base, String operation, long expectedRevision) {
        String input = base.idempotencyKey() + '|' + operation;
        String digest = HexFormat.of().formatHex(sha256().digest(input.getBytes(StandardCharsets.UTF_8)));
        return new CommandOptions("attachment-" + digest, expectedRevision);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record LocalDescriptor(String digest, long sizeBytes) {
        private LocalDescriptor {
            digest = Objects.requireNonNull(digest, "digest");
            if (sizeBytes < 1) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
        }
    }
}
