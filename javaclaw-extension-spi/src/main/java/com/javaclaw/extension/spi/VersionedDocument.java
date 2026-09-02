package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * 托管存储中的版本化记录。
 *
 * @param key 记录键
 * @param revision 单调版本
 * @param payload 内容
 * @param updatedAt 最后提交时间
 */
public record VersionedDocument(String key, long revision, CanonicalPayload payload, Instant updatedAt) {
    /** 校验记录。 */
    public VersionedDocument {
        key = Objects.requireNonNull(key, "key").strip();
        if (key.isEmpty() || revision < 1) {
            throw new IllegalArgumentException("key and positive revision are required");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
