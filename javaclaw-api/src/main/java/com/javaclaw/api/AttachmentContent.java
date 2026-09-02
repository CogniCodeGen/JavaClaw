package com.javaclaw.api;

import java.util.Objects;

/**
 * 附件读取结果；字节数组在构造和读取时都会复制。
 *
 * @param metadata 附件元数据
 * @param content 原始内容
 */
public record AttachmentContent(AttachmentMetadata metadata, byte[] content) {
    /** 校验元数据并取得内容所有权。 */
    public AttachmentContent {
        Objects.requireNonNull(metadata, "metadata");
        content = Objects.requireNonNull(content, "content").clone();
        if (content.length != metadata.sizeBytes()) {
            throw new IllegalArgumentException("content length does not match metadata");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
