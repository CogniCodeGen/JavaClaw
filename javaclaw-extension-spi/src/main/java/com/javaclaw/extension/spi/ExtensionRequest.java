package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.WorkspaceId;

/**
 * Extension query 或 command 的统一调用输入。
 *
 * @param workspaceId Workspace
 * @param threadId 可选 Thread
 * @param turnId 可选 Turn
 * @param operation 扩展内操作名
 * @param payload 规范化输入
 * @param idempotencyKey command 必填、query 为空
 * @param expectedRevision 写入所针对的版本；创建时可为 0
 * @param unattendedExecutionScope 仅平台 Schedule 路由可注入的无人值守来源
 */
public record ExtensionRequest(
        WorkspaceId workspaceId,
        Optional<ThreadId> threadId,
        Optional<TurnId> turnId,
        String operation,
        CanonicalPayload payload,
        Optional<String> idempotencyKey,
        long expectedRevision,
        Optional<UnattendedExecutionScope> unattendedExecutionScope) {
    /** 校验调用上下文与并发版本。 */
    public ExtensionRequest {
        Objects.requireNonNull(workspaceId, "workspaceId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        operation = requireText(operation, "operation");
        Objects.requireNonNull(payload, "payload");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey")
                .map(value -> requireText(value, "idempotencyKey"));
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        unattendedExecutionScope = Objects.requireNonNull(unattendedExecutionScope, "unattendedExecutionScope");
        unattendedExecutionScope.ifPresent(scope -> {
            if (!scope.workspaceId().equals(workspaceId)) {
                throw new IllegalArgumentException("unattended execution scope belongs to another Workspace");
            }
        });
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
