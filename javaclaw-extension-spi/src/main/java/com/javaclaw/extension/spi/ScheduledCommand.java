package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.WorkspaceId;

/**
 * Schedule 受限命令调用的完整冻结身份。
 *
 * @param workspaceId Workspace
 * @param extensionId 目标扩展
 * @param operation 已声明为可调度的 operation
 * @param payload 冻结参数
 * @param idempotencyKey Occurrence 恢复键
 * @param expectedRevision 目标精确 revision；无版本目标为 0
 * @param actionSchemaHash 用户可选 SchedulableAction 的冻结输入 Schema 摘要；内部投递为空
 * @param unattendedExecutionScope 已创建 Occurrence 的安全来源；投递 Occurrence 本身时为空
 */
public record ScheduledCommand(
        WorkspaceId workspaceId,
        String extensionId,
        String operation,
        CanonicalPayload payload,
        String idempotencyKey,
        long expectedRevision,
        Optional<String> actionSchemaHash,
        Optional<UnattendedExecutionScope> unattendedExecutionScope) {
    /** 校验命令身份、revision、Schema 摘要和 Schedule 来源。 */
    public ScheduledCommand {
        Objects.requireNonNull(workspaceId, "workspaceId");
        extensionId = text(extensionId, "extensionId");
        operation = text(operation, "operation");
        Objects.requireNonNull(payload, "payload");
        idempotencyKey = text(idempotencyKey, "idempotencyKey");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        actionSchemaHash =
                Objects.requireNonNull(actionSchemaHash, "actionSchemaHash").map(ScheduledCommand::digest);
        unattendedExecutionScope = Objects.requireNonNull(unattendedExecutionScope, "unattendedExecutionScope");
        unattendedExecutionScope.ifPresent(scope -> {
            if (!scope.workspaceId().equals(workspaceId)) {
                throw new IllegalArgumentException("unattended execution scope belongs to another Workspace");
            }
        });
    }

    private static String digest(String value) {
        String normalized =
                Objects.requireNonNull(value, "actionSchemaHash").strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("actionSchemaHash must be a SHA-256 digest");
        }
        return normalized;
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
