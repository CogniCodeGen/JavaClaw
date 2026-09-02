package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP 健康检查脱敏结果。
 *
 * @param endpointId 端点标识
 * @param endpointRevision 检查的精确端点版本
 * @param state 健康状态
 * @param negotiatedProtocol 远端确认的协议；不匹配时保留远端值
 * @param detail 非内容型错误摘要
 * @param checkedAt 检查时间
 */
public record McpHealth(
        String endpointId,
        long endpointRevision,
        McpHealthState state,
        Optional<String> negotiatedProtocol,
        Optional<String> detail,
        Instant checkedAt) {
    /** 校验脱敏健康快照。 */
    public McpHealth {
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        endpointRevision = Preconditions.positive(endpointRevision, "endpointRevision");
        Objects.requireNonNull(state, "state");
        negotiatedProtocol = Objects.requireNonNull(negotiatedProtocol, "negotiatedProtocol")
                .map(value -> Preconditions.text(value, "negotiatedProtocol"));
        detail = Objects.requireNonNull(detail, "detail").map(value -> Preconditions.text(value, "detail"));
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
