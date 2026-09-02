package com.javaclaw.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 分块 Attachment 上传的权威快照，不包含客户端路径或服务端 staging 路径。
 *
 * @param id 服务端生成的上传 UUID
 * @param scope 上传完成后写入的所有权 claim
 * @param state 当前持久状态
 * @param mediaType 最终 Attachment 的 MIME 类型
 * @param expectedDigest 客户端预扫描得到的 SHA-256
 * @param expectedSizeBytes 预期总字节数
 * @param receivedSizeBytes 已持久确认的字节数
 * @param nextChunkIndex 下一个允许提交的分块序号
 * @param revision 乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 最近持久变更时间
 * @param expiresAt ACTIVE 状态的回收期限
 * @param attachment COMPLETED 状态对应的 Attachment 元数据
 * @param failureReason FAILED、ABORTED 或 EXPIRED 的脱敏原因
 */
public record AttachmentUploadSession(
        String id,
        AttachmentScope scope,
        AttachmentUploadState state,
        String mediaType,
        String expectedDigest,
        long expectedSizeBytes,
        long receivedSizeBytes,
        int nextChunkIndex,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Optional<AttachmentMetadata> attachment,
        Optional<String> failureReason) {
    /** 单个 Attachment 上传允许声明的最大原始字节数。 */
    public static final long MAXIMUM_BYTES = 64L * 1024 * 1024;

    /** 一个会话最多允许确认的 chunk 数。 */
    public static final int MAXIMUM_CHUNKS = 1_024;

    /** 校验上传状态、计数与终态不变量。 */
    public AttachmentUploadSession {
        id = normalizedUuid(id);
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        mediaType = Preconditions.text(mediaType, "mediaType");
        expectedDigest = Preconditions.text(expectedDigest, "expectedDigest").toLowerCase(Locale.ROOT);
        if (!expectedDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedDigest must be SHA-256 hex");
        }
        if (expectedSizeBytes < 1
                || expectedSizeBytes > MAXIMUM_BYTES
                || receivedSizeBytes < 0
                || receivedSizeBytes > expectedSizeBytes) {
            throw new IllegalArgumentException("invalid Attachment upload byte counts");
        }
        if (nextChunkIndex < 0 || nextChunkIndex > MAXIMUM_CHUNKS || revision < 1) {
            throw new IllegalArgumentException("invalid Attachment upload sequence or revision");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        attachment = Objects.requireNonNull(attachment, "attachment");
        failureReason = normalizedReason(failureReason);
        validateTerminalState(state, expectedDigest, expectedSizeBytes, receivedSizeBytes, attachment, failureReason);
    }

    private static String normalizedUuid(String value) {
        String normalized = Preconditions.text(value, "id").toLowerCase(Locale.ROOT);
        if (!UUID.fromString(normalized).toString().equals(normalized)) {
            throw new IllegalArgumentException("id must be a canonical UUID");
        }
        return normalized;
    }

    private static Optional<String> normalizedReason(Optional<String> value) {
        Optional<String> checked = Objects.requireNonNull(value, "failureReason")
                .map(reason -> Preconditions.text(reason, "failureReason"));
        if (checked.map(String::length).orElse(0) > 500) {
            throw new IllegalArgumentException("failureReason must not exceed 500 characters");
        }
        return checked;
    }

    private static void validateTerminalState(
            AttachmentUploadState state,
            String expectedDigest,
            long expectedSize,
            long receivedSize,
            Optional<AttachmentMetadata> attachment,
            Optional<String> failureReason) {
        if (state == AttachmentUploadState.COMPLETED) {
            AttachmentMetadata metadata = attachment.orElseThrow(
                    () -> new IllegalArgumentException("completed upload requires Attachment metadata"));
            if (!metadata.digest().equals(expectedDigest)
                    || metadata.sizeBytes() != expectedSize
                    || receivedSize != expectedSize
                    || failureReason.isPresent()) {
                throw new IllegalArgumentException("completed upload metadata differs from its declaration");
            }
            return;
        }
        if (attachment.isPresent()) {
            throw new IllegalArgumentException("only completed upload may reference an Attachment");
        }
        boolean failed = state == AttachmentUploadState.FAILED
                || state == AttachmentUploadState.ABORTED
                || state == AttachmentUploadState.EXPIRED;
        if (failed != failureReason.isPresent()) {
            throw new IllegalArgumentException("terminal upload state requires one failure reason");
        }
    }
}
