package com.javaclaw.api;

import java.util.Objects;

/**
 * 经模型生成、等待治理层执行的工具请求。
 *
 * @param turnId 所属 Turn
 * @param callId Turn 内唯一调用 ID
 * @param tool 冻结工具身份
 * @param arguments 规范化参数
 * @param idempotencyKey 副作用恢复键
 * @param expectedCatalogRevision 冻结目录版本
 */
public record ToolCallRequest(
        TurnId turnId,
        String callId,
        ToolIdentity tool,
        CanonicalPayload arguments,
        String idempotencyKey,
        long expectedCatalogRevision) {
    /** 校验调用身份和 revision。 */
    public ToolCallRequest {
        Objects.requireNonNull(turnId, "turnId");
        callId = Preconditions.text(callId, "callId");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(arguments, "arguments");
        idempotencyKey = Preconditions.text(idempotencyKey, "idempotencyKey");
        expectedCatalogRevision = Preconditions.positive(expectedCatalogRevision, "expectedCatalogRevision");
    }
}
