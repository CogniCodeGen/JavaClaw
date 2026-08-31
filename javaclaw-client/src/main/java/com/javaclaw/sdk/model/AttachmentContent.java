package com.javaclaw.sdk.model;

/**
 * 校验 SHA-256 后的有界附件内容；适用于图片或小文档预览，大文件应使用分块下载。
 *
 * @param attachment 服务器元数据，不包含本地路径
 * @param bytes 完整字节，构造和读取均防御性复制
 */
public record AttachmentContent(AttachmentInfo attachment, byte[] bytes) {
    /** 固定附件元数据和字节所有权，拒绝不完整内容。 */
    public AttachmentContent {
        java.util.Objects.requireNonNull(attachment);
        bytes = java.util.Objects.requireNonNull(bytes).clone();
        if (bytes.length != attachment.sizeBytes()) {
            throw new IllegalArgumentException("attachment size mismatch");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
