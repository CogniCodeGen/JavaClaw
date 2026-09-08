package com.javaclaw.api;

import java.util.Objects;

/**
 * 同一个不可变版本中的有界原始字节块。
 *
 * @param offsetBytes 当前块起始字节位置，非负
 * @param content 至多 256 KiB 的字节；防御性复制
 * @param nextOffsetBytes 下一块位置，等于 offsetBytes 加本块长度
 * @param complete 是否已到该版本末尾
 * @param digest 完整版本 SHA-256，用于客户端跨块校验
 */
public record DocumentChunk(long offsetBytes, byte[] content, long nextOffsetBytes, boolean complete, String digest) {
    /** 校验分块边界并取得独立字节副本。 */
    public DocumentChunk {
        content = Objects.requireNonNull(content, "content").clone();
        if (offsetBytes < 0
                || nextOffsetBytes < offsetBytes
                || content.length > 256 * 1024
                || nextOffsetBytes != offsetBytes + content.length
                || (!complete && content.length == 0)) {
            throw new IllegalArgumentException("无效的文档分块边界");
        }
        digest = Preconditions.digest(digest, "digest");
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
