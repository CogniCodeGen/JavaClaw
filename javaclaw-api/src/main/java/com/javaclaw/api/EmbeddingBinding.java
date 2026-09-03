package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 本地安装默认 Embedding 模型的精确版本绑定。
 *
 * @param provider 精确 Provider 与 Embedding 模型引用
 * @param revision 绑定自身的乐观锁版本
 * @param updatedAt 最近更新时间
 */
public record EmbeddingBinding(ProviderRef provider, long revision, Instant updatedAt) {
    /** 校验引用、版本和时间。 */
    public EmbeddingBinding {
        Objects.requireNonNull(provider, "provider");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
