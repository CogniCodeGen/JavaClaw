package com.javaclaw.sdk;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.AttachmentChunk;
import com.javaclaw.sdk.model.AttachmentContent;
import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.AttachmentUploadInfo;

/** 有界附件上传/下载客户端；本地 Path 仅在 SDK 读取，协议只传内容和摘要。远程失败通过 Future 异常返回。 */
public final class AttachmentClient {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    public static final int CHUNK_BYTES = 1024 * 1024;
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    AttachmentClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 按声明摘要、媒体类型和字节大小创建可续传会话；总量最多 256 MiB，key 可用于重试去重。 */
    public CompletableFuture<AttachmentUploadInfo> startUpload(
            String sha256, String mediaType, String displayName, long sizeBytes, String key) {
        return protocol.startAttachmentUpload(sha256, mediaType, displayName, sizeBytes, key)
                .thenApply(mapper::upload);
    }

    /** 提交准确 offset 处不超过 1 MiB 的字节块；返回服务端已接受的位置，错位或超量会失败。 */
    public CompletableFuture<AttachmentUploadInfo> appendChunk(String uploadId, long offset, byte[] data) {
        return protocol.appendAttachmentChunk(uploadId, offset, data).thenApply(mapper::upload);
    }

    /** 请求服务端校验已收大小和 SHA-256 并提交附件；返回元数据，不返回 blob 路径。 */
    public CompletableFuture<AttachmentInfo> completeUpload(String uploadId) {
        return protocol.completeAttachmentUpload(uploadId).thenApply(mapper::attachment);
    }

    /** 异步读取本地 file、计算摘要并分块上传；本地路径不会作为 Turn 输入发送给服务端。 */
    public CompletableFuture<AttachmentInfo> upload(Path file, String mediaType, String key) {
        return protocol.uploadAttachment(file, mediaType, key).thenApply(mapper::attachment);
    }

    /** 读取 SHA-256 对应附件的字节块，maximumBytes 为块上限；返回 EOF 与元数据。 */
    public CompletableFuture<AttachmentChunk> read(String sha256, long offset, int maximumBytes) {
        return protocol.readAttachment(sha256, offset, maximumBytes)
                .thenApply(value -> new AttachmentChunk(
                        mapper.attachment(value.attachment()), value.offset(), value.data(), value.eof()));
    }

    /**
     * 分块下载并验证总大小、offset 和 SHA-256 后发布本地文件；路径不发送到 App Server。 replaceExisting=false 不覆盖现有文件；取消或校验失败仅清理本次临时文件，不修改既有目标。
     */
    public CompletableFuture<Path> download(String sha256, Path destination, boolean replaceExisting) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid attachment SHA-256"));
        }
        var result = new CompletableFuture<Path>();
        Thread worker = Thread.ofVirtual()
                .name("javaclaw-sdk-attachment-download")
                .start(() -> {
                    Path staging = null;
                    try {
                        Path requested = java.util.Objects.requireNonNull(destination)
                                .toAbsolutePath()
                                .normalize();
                        Path target = requested.getParent().toRealPath().resolve(requested.getFileName());
                        if (java.nio.file.Files.isSymbolicLink(target)
                                || java.nio.file.Files.isDirectory(target)
                                || (!replaceExisting && java.nio.file.Files.exists(target))) {
                            throw new java.nio.file.FileAlreadyExistsException(target.toString());
                        }
                        staging =
                                java.nio.file.Files.createTempFile(target.getParent(), ".javaclaw-download-", ".part");
                        try (var output = java.nio.file.Files.newOutputStream(staging)) {
                            receive(sha256, output, MAX_BYTES, result);
                        }
                        if (result.isCancelled()) {
                            throw new InterruptedException("attachment download cancelled");
                        }
                        if (replaceExisting) {
                            java.nio.file.Files.move(
                                    staging,
                                    target,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            // 同目录硬链接原子声明不存在的名称，避免 ATOMIC_MOVE 在部分平台隐式替换目标。
                            java.nio.file.Files.createLink(target, staging);
                        }
                        result.complete(target);
                    } catch (Exception failure) {
                        if (failure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        result.completeExceptionally(failure);
                    } finally {
                        if (staging != null) {
                            try {
                                java.nio.file.Files.deleteIfExists(staging);
                            } catch (java.io.IOException ignored) {
                                // 只遗留本次拥有的 .part 文件；不能为清理而删除用户目标。
                            }
                        }
                    }
                });
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                worker.interrupt();
            }
        });
        return result;
    }

    /** 读取不超过 maximumBytes 的完整附件并校验摘要；最大允许 8 MiB，超出时应改用 download。 取消立即中断读取等待，不自动重试失败的校验，也不打开外部 URI。 */
    public CompletableFuture<AttachmentContent> readContent(String sha256, int maximumBytes) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || maximumBytes < 1 || maximumBytes > 8 * CHUNK_BYTES) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("invalid attachment preview limit or SHA-256"));
        }
        var result = new CompletableFuture<AttachmentContent>();
        Thread worker = Thread.ofVirtual()
                .name("javaclaw-sdk-attachment-preview")
                .start(() -> {
                    try (var output = new java.io.ByteArrayOutputStream()) {
                        var metadata = receive(sha256, output, maximumBytes, result);
                        result.complete(new AttachmentContent(metadata, output.toByteArray()));
                    } catch (Exception failure) {
                        if (failure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        result.completeExceptionally(failure);
                    }
                });
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                worker.interrupt();
            }
        });
        return result;
    }

    private AttachmentInfo receive(
            String sha256, java.io.OutputStream output, long maximumBytes, CompletableFuture<?> result)
            throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        long offset = 0;
        AttachmentInfo metadata = null;
        while (true) {
            if (result.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("attachment transfer cancelled");
            }
            var chunk = read(sha256, offset, (int) Math.min(maximumBytes, CHUNK_BYTES))
                    .get();
            byte[] bytes = chunk.data();
            if (metadata == null) {
                metadata = chunk.attachment();
            }
            if (!sha256.equals(metadata.sha256())
                    || metadata.sizeBytes() < 0
                    || metadata.sizeBytes() > maximumBytes
                    || !metadata.sha256().equals(chunk.attachment().sha256())
                    || metadata.sizeBytes() != chunk.attachment().sizeBytes()
                    || !metadata.mediaType().equals(chunk.attachment().mediaType())
                    || chunk.offset() != offset
                    || bytes.length > CHUNK_BYTES
                    || offset + bytes.length > metadata.sizeBytes()
                    || (bytes.length == 0 && !chunk.eof())) {
                throw new java.io.IOException("invalid attachment chunk or preview size exceeded");
            }
            output.write(bytes);
            digest.update(bytes);
            offset += bytes.length;
            if (chunk.eof()) {
                if (offset != metadata.sizeBytes()) {
                    throw new java.io.IOException("attachment size mismatch");
                }
                break;
            }
        }
        if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
            throw new java.io.IOException("attachment digest mismatch");
        }
        return metadata;
    }

    /** 异步释放一份上传或直接保留的附件引用，不释放 Thread/Knowledge 等领域引用。 返回 true 表示附件已最终移除；false 可能表示仍有其他所有者，不等于释放操作失败。 */
    public CompletableFuture<Boolean> release(String sha256) {
        return protocol.releaseAttachment(sha256);
    }
}
