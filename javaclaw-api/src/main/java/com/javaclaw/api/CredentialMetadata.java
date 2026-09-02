package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Secret 的非敏感元数据。
 *
 * @param reference opaque Vault 引用
 * @param revision 当前 Secret 版本
 * @param updatedAt 最近写入时间
 */
public record CredentialMetadata(CredentialRef reference, long revision, Instant updatedAt) {
    /** 校验引用、版本与时间。 */
    public CredentialMetadata {
        reference = Objects.requireNonNull(reference, "reference");
        revision = Preconditions.positive(revision, "revision");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
