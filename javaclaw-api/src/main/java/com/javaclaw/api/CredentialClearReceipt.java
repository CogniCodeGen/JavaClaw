package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Secret 清除完成后的脱敏幂等回执。
 *
 * @param reference 已失效的引用
 * @param clearedRevision 被清除的最后版本
 * @param clearedAt 清除提交时间
 */
public record CredentialClearReceipt(CredentialRef reference, long clearedRevision, Instant clearedAt) {
    /** 校验引用、版本和时间。 */
    public CredentialClearReceipt {
        reference = Objects.requireNonNull(reference, "reference");
        clearedRevision = Preconditions.positive(clearedRevision, "clearedRevision");
        clearedAt = Objects.requireNonNull(clearedAt, "clearedAt");
    }
}
