package com.javaclaw.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次安全授权判断的可解释、脱敏审计记录。
 *
 * @param id 稳定追踪标识
 * @param workspaceId 所属 Workspace
 * @param grantKind 授权类型
 * @param grantId 被检查的授权标识
 * @param operation 受治理操作
 * @param resource 脱敏资源描述，只能包含 Origin 或工具身份
 * @param allowed 最终结果
 * @param steps 按顺序记录的约束判断
 * @param denialReason 拒绝时的稳定通俗原因
 * @param decidedAt 判断时间
 */
public record PermissionDecisionTrace(
        String id,
        WorkspaceId workspaceId,
        SecurityGrantKind grantKind,
        String grantId,
        String operation,
        String resource,
        boolean allowed,
        List<Step> steps,
        Optional<String> denialReason,
        Instant decidedAt) {
    /** 复制步骤并校验拒绝原因与结果一致。 */
    public PermissionDecisionTrace {
        id = Preconditions.identifier(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(grantKind, "grantKind");
        grantId = Preconditions.identifier(grantId, "grantId");
        operation = bounded(operation, "operation", 160);
        resource = bounded(resource, "resource", 1000);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("decision steps must not be empty");
        }
        denialReason = Objects.requireNonNull(denialReason, "denialReason")
                .map(reason -> bounded(reason, "denialReason", 500));
        if (allowed == denialReason.isPresent()) {
            throw new IllegalArgumentException("denialReason must be present exactly when access is denied");
        }
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    /**
     * 单层权限约束的判断。
     *
     * @param source 约束来源
     * @param revision 来源 revision；无版本的系统约束为 0
     * @param allowed 本层是否允许
     * @param explanation 不含敏感数据的说明
     */
    public record Step(String source, long revision, boolean allowed, String explanation) {
        /** 校验来源、版本和说明。 */
        public Step {
            source = Preconditions.identifier(source, "source");
            revision = Preconditions.nonNegative(revision, "revision");
            explanation = bounded(explanation, "explanation", 500);
        }
    }

    private static String bounded(String value, String name, int maximumLength) {
        String checked = Preconditions.text(value, name);
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return checked;
    }
}
