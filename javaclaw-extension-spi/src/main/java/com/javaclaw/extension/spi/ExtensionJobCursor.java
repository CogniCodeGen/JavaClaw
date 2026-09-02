package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * Extension Job 稳定 keyset 游标。
 *
 * @param updatedAt 上一页最后一项更新时间
 * @param id 上一页最后一项稳定标识
 */
public record ExtensionJobCursor(Instant updatedAt, String id) {
    /** 校验游标字段。 */
    public ExtensionJobCursor {
        Objects.requireNonNull(updatedAt, "updatedAt");
        id = Objects.requireNonNull(id, "id").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("Job cursor id is invalid");
        }
    }
}
