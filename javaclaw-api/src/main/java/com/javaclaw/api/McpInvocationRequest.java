package com.javaclaw.api;

import java.util.Objects;

/**
 * 执行前已重新校验的 MCP Tool 请求。
 *
 * @param tool 冻结 Tool 身份
 * @param arguments 规范参数
 * @param idempotencyKey 副作用去重键
 */
public record McpInvocationRequest(McpFrozenTool tool, CanonicalPayload arguments, String idempotencyKey) {
    /** 校验请求。 */
    public McpInvocationRequest {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(arguments, "arguments");
        idempotencyKey = Preconditions.text(idempotencyKey, "idempotencyKey");
    }
}
