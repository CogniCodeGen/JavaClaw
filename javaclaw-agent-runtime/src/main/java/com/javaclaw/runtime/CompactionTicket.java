package com.javaclaw.runtime;

import java.util.Objects;

/**
 * 已提交压缩意图的身份，用于拒绝过期或跨类型结果。
 *
 * @param ordinal Turn 内从 1 开始的压缩序号
 * @param digest 精确压缩输入摘要
 * @param nativeCall 是否执行计费外部压缩
 */
public record CompactionTicket(int ordinal, String digest, boolean nativeCall) {
    /** 校验序号和 SHA-256。 */
    public CompactionTicket {
        if (ordinal < 1 || !Objects.requireNonNull(digest, "digest").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid compaction ticket");
        }
    }
}
