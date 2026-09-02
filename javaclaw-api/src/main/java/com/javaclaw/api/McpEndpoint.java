package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * MCP 端点不可变版本。
 *
 * @param id 稳定标识
 * @param revision 配置版本
 * @param state 实时开关
 * @param catalogRevision 已提交目录版本；尚未发现时为零
 * @param spec 完整非敏感配置
 * @param createdAt 首次创建时间
 * @param updatedAt 当前版本时间
 */
public record McpEndpoint(
        String id,
        long revision,
        McpEndpointState state,
        long catalogRevision,
        McpEndpointSpec spec,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验版本和时间。 */
    public McpEndpoint {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(state, "state");
        if (catalogRevision < 0) {
            throw new IllegalArgumentException("catalogRevision must not be negative");
        }
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }
}
