package com.javaclaw.protocol;

import java.util.Locale;
import java.util.Objects;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.AttachmentUploadSession;

/** Protocol v2 分块 Attachment 上传与读取请求契约。 */
public final class AttachmentRpcContracts {
    /** 单个 Attachment 允许的最大原始字节数。 */
    public static final int MAX_ATTACHMENT_BYTES = Math.toIntExact(AttachmentUploadSession.MAXIMUM_BYTES);

    /** 单个 wire chunk 允许的最大原始字节数；避免 JSON-RPC 出现大 Base64 payload。 */
    public static final int MAX_ATTACHMENT_CHUNK_BYTES = 256 * 1024;

    /** 单次上传允许的最大 chunk 数，限制极小分块造成的资源放大。 */
    public static final int MAX_ATTACHMENT_CHUNKS = AttachmentUploadSession.MAXIMUM_CHUNKS;

    private AttachmentRpcContracts() {}

    /**
     * 开始分块 Attachment 上传。
     *
     * @param scope 上传完成后写入的显式所有权范围
     * @param mediaType 最终 MIME 类型
     * @param expectedDigest 客户端有界预扫描得到的 SHA-256
     * @param expectedSizeBytes 最终原始字节数，最大 64 MiB
     */
    public record BeginPayload(AttachmentScope scope, String mediaType, String expectedDigest, long expectedSizeBytes) {
        /** 校验声明的媒体类型、摘要和总大小。 */
        public BeginPayload {
            Objects.requireNonNull(scope, "scope");
            mediaType = text(mediaType, "mediaType");
            if (mediaType.length() > 240 || !mediaType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
                throw new IllegalArgumentException("mediaType must be a simple MIME type not exceeding 240 characters");
            }
            expectedDigest = sha256Digest(expectedDigest, "expectedDigest");
            if (expectedSizeBytes < 1 || expectedSizeBytes > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("Attachment size must be between 1 byte and 64 MiB");
            }
        }
    }

    /**
     * 追加一个有序 Attachment chunk。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param uploadId 上传 UUID
     * @param chunkIndex 从 0 开始且必须连续的序号
     * @param content 原始 chunk；硬上限 256 KiB
     */
    public record ChunkPayload(AttachmentScope scope, String uploadId, int chunkIndex, byte[] content) {
        /** 校验会话、序号并取得 chunk 字节所有权。 */
        public ChunkPayload {
            Objects.requireNonNull(scope, "scope");
            uploadId = normalizedUploadId(uploadId);
            if (chunkIndex < 0 || chunkIndex >= MAX_ATTACHMENT_CHUNKS) {
                throw new IllegalArgumentException("chunkIndex is outside the supported range");
            }
            content = Objects.requireNonNull(content, "content").clone();
            if (content.length < 1 || content.length > MAX_ATTACHMENT_CHUNK_BYTES) {
                throw new IllegalArgumentException("Attachment chunk must be between 1 byte and 256 KiB");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * 完成并提交一个分块 Attachment 上传。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param uploadId 上传 UUID
     */
    public record CompletePayload(AttachmentScope scope, String uploadId) {
        /** 校验上传 UUID。 */
        public CompletePayload {
            Objects.requireNonNull(scope, "scope");
            uploadId = normalizedUploadId(uploadId);
        }
    }

    /**
     * 中止并回收一个分块 Attachment 上传。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param uploadId 上传 UUID
     * @param reason 脱敏取消原因
     */
    public record AbortPayload(AttachmentScope scope, String uploadId, String reason) {
        /** 校验上传 UUID 与原因。 */
        public AbortPayload {
            Objects.requireNonNull(scope, "scope");
            uploadId = normalizedUploadId(uploadId);
            reason = text(reason, "reason");
            if (reason.length() > 500) {
                throw new IllegalArgumentException("reason must not exceed 500 characters");
            }
        }
    }

    /**
     * 读取分块上传权威状态。
     *
     * @param scope 必须与上传会话一致的所有权范围
     * @param uploadId 上传 UUID
     */
    public record UploadReadPayload(AttachmentScope scope, String uploadId) {
        /** 校验上传 UUID。 */
        public UploadReadPayload {
            Objects.requireNonNull(scope, "scope");
            uploadId = normalizedUploadId(uploadId);
        }
    }

    /**
     * 按摘要读取已提交 Attachment。
     *
     * @param scope 要核验的显式所有权范围
     * @param digest SHA-256 小写十六进制摘要
     */
    public record ReadPayload(AttachmentScope scope, String digest) {
        /** 校验摘要。 */
        public ReadPayload {
            Objects.requireNonNull(scope, "scope");
            digest = sha256Digest(digest, "digest");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String sha256Digest(String value, String name) {
        String normalized = text(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256 hex");
        }
        return normalized;
    }

    private static String normalizedUploadId(String value) {
        String normalized = text(value, "uploadId").toLowerCase(Locale.ROOT);
        if (!java.util.UUID.fromString(normalized).toString().equals(normalized)) {
            throw new IllegalArgumentException("uploadId must be a canonical UUID");
        }
        return normalized;
    }
}
