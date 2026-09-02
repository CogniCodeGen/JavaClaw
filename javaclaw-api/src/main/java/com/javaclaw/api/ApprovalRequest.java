package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 等待人工决策的工具调用摘要。
 *
 * @param id 审批 ID
 * @param turnId 所属 Turn
 * @param callId 模型工具调用 ID
 * @param tool 工具身份
 * @param risk 风险等级
 * @param explanation 用户可理解的副作用说明
 * @param requestDigest 工具参数 SHA-256；不持久化可能含敏感信息的原始参数
 * @param createdAt 创建时间
 * @param expiresAt 失效时间
 */
public record ApprovalRequest(
        String id,
        TurnId turnId,
        String callId,
        ToolIdentity tool,
        ToolRisk risk,
        String explanation,
        String requestDigest,
        Instant createdAt,
        Instant expiresAt) {
    /** 校验审批字段。 */
    public ApprovalRequest {
        id = Preconditions.text(id, "id");
        Objects.requireNonNull(turnId, "turnId");
        callId = Preconditions.text(callId, "callId");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(risk, "risk");
        explanation = Preconditions.text(explanation, "explanation");
        requestDigest = Preconditions.text(requestDigest, "requestDigest").toLowerCase(java.util.Locale.ROOT);
        if (!requestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestDigest must be SHA-256 hex");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
