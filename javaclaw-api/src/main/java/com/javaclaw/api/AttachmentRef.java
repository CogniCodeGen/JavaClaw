package com.javaclaw.api;

/**
 * 内容寻址附件引用，不包含宿主绝对路径。
 *
 * @param digest SHA-256 十六进制摘要
 * @param mediaType MIME 类型
 * @param fileName 用户可见名称
 * @param sizeBytes 原始字节数
 */
public record AttachmentRef(String digest, String mediaType, String fileName, long sizeBytes) {
    /** 校验摘要、媒体类型、名称和大小。 */
    public AttachmentRef {
        digest = Preconditions.text(digest, "digest").toLowerCase();
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be SHA-256 hex");
        }
        mediaType = Preconditions.text(mediaType, "mediaType");
        fileName = Preconditions.text(fileName, "fileName");
        sizeBytes = Preconditions.nonNegative(sizeBytes, "sizeBytes");
    }
}
