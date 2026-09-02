package com.javaclaw.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 内容寻址附件的持久元数据，不暴露宿主文件路径。
 *
 * @param digest 内容的 SHA-256 小写十六进制摘要
 * @param mediaType MIME 类型
 * @param sizeBytes 原始内容字节数
 * @param createdAt 首次写入时间
 */
public record AttachmentMetadata(String digest, String mediaType, long sizeBytes, Instant createdAt) {
    /** 校验摘要、媒体类型、大小和时间。 */
    public AttachmentMetadata {
        digest = Preconditions.text(digest, "digest").toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be SHA-256 hex");
        }
        mediaType = Preconditions.text(mediaType, "mediaType");
        sizeBytes = Preconditions.nonNegative(sizeBytes, "sizeBytes");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
