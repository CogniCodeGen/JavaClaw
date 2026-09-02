package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider 端点的不可变版本快照。
 *
 * @param id 稳定端点标识
 * @param revision 不可变版本，从 1 开始
 * @param lifecycle 生命周期
 * @param spec 完整可编辑配置
 * @param createdAt 首次创建时间
 * @param updatedAt 当前版本写入时间
 */
public record ProviderEndpoint(
        String id,
        long revision,
        ProviderLifecycle lifecycle,
        ProviderEndpointSpec spec,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验端点身份和时间。 */
    public ProviderEndpoint {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
