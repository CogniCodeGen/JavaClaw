package com.javaclaw.nativehost.coding;

import java.util.Objects;

/**
 * 完整文件摘要及有界内容页。
 *
 * @param path 相对文件路径，不可空
 * @param sizeBytes 完整文件字节数，非负
 * @param sha256 完整文件的 SHA-256，不是本页摘要
 * @param offsetBytes 请求的字节偏移，非负
 * @param content 本页字节，防御性复制
 */
public record WorkspaceReadPage(String path, long sizeBytes, String sha256, long offsetBytes, byte[] content) {
    /** 校验元数据并固定字节所有权。 */
    public WorkspaceReadPage {
        WorkspaceFileProtocol.requireRelative(path, false);
        if (sizeBytes < 0
                || offsetBytes < 0
                || !Objects.requireNonNull(sha256, "sha256").matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid file page metadata");
        }
        content = Objects.requireNonNull(content, "content").clone();
        if (content.length > Math.max(0, sizeBytes - offsetBytes)) {
            throw new IllegalArgumentException("file page exceeds the file size");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
