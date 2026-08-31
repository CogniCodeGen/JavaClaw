package com.javaclaw.core.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable resumable upload state.
 *
 * @param uploadId 非空白上传会话标识
 * @param expectedSha256 预期内容摘要；空字符串表示开始上传时未提供摘要
 * @param mediaType 非空白 MIME 类型
 * @param displayName 非空白展示文件名，不用于定位客户端路径
 * @param expectedSize 声明的总字节数，非负
 * @param receivedBytes 已接收字节数，范围为 0 到 expectedSize
 * @param expiresAt 会话过期时间，非空
 */
public record AttachmentUpload(
        String uploadId,
        String expectedSha256,
        String mediaType,
        String displayName,
        long expectedSize,
        long receivedBytes,
        Instant expiresAt) {
    /** 校验摘要及断点范围；receivedBytes 不得超过声明大小，避免恢复时错误推进上传游标。 */
    public AttachmentUpload {
        uploadId = ThreadId.required(uploadId, "uploadId");
        expectedSha256 = expectedSha256 == null ? "" : expectedSha256.toLowerCase(java.util.Locale.ROOT);
        if (!expectedSha256.isEmpty() && !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedSha256 is invalid");
        }
        mediaType = ThreadId.required(mediaType, "mediaType");
        displayName = ThreadId.required(displayName, "displayName");
        if (expectedSize < 0 || receivedBytes < 0 || receivedBytes > expectedSize) {
            throw new IllegalArgumentException("upload byte counts are invalid");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
