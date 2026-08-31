package com.javaclaw.core.api;

import java.util.Locale;
import java.util.Objects;

/**
 * 内容寻址附件的元数据，不包含客户端文件路径或附件明文。
 *
 * @param sha256 内容的 SHA-256，小写十六进制 64 位字符串
 * @param mediaType 非空白 MIME 类型
 * @param sizeBytes 附件总大小，单位字节，非负
 * @param referenceCount 所有者引用数，非负；零引用 blob 可在恢复宽限期内保留
 */
public record AttachmentMetadata(String sha256, String mediaType, long sizeBytes, long referenceCount) {
    /** 校验摘要、MIME 类型及非负大小/引用数；允许零引用以支持崩溃后的 reconciliation。 */
    public AttachmentMetadata {
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
        mediaType = ThreadId.required(mediaType, "mediaType");
        if (mediaType.length() > 300) {
            throw new IllegalArgumentException("mediaType exceeds 300 characters");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
        // Zero is a valid internal grace-period state: the reconciler keeps the
        // content-addressed blob briefly so an interrupted owner transaction can recover it.
        if (referenceCount < 0) {
            throw new IllegalArgumentException("referenceCount must be non-negative");
        }
    }
}
