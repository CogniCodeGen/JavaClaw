package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * 托管扩展文档的不可变历史版本。
 *
 * @param key 文档键
 * @param revision 单调递增版本
 * @param payload 该版本内容；tombstone 保留删除前最后一版，便于审计和显式恢复
 * @param tombstone 是否为删除标记
 * @param updatedAt 提交时间
 */
public record DocumentRevision(
        String key, long revision, CanonicalPayload payload, boolean tombstone, Instant updatedAt) {
    /** 校验历史版本。 */
    public DocumentRevision {
        key = Objects.requireNonNull(key, "key").strip();
        if (key.isEmpty() || revision < 1) {
            throw new IllegalArgumentException("document revision identity is invalid");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
