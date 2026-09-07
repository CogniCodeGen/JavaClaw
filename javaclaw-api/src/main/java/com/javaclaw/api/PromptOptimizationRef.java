package com.javaclaw.api;

import java.util.Objects;

/**
 * Prompt 优化任务及其普通 Thread/Turn 的稳定关联。
 *
 * @param id 优化任务标识
 * @param workspaceId 所属 Workspace
 * @param sourceRole 启动时冻结的源 Agent Role
 * @param threadId 承载审计对话的普通只读 Thread
 * @param turnId 由 Thin Harness 执行的普通 Turn
 */
public record PromptOptimizationRef(
        PromptOptimizationId id, WorkspaceId workspaceId, AgentRoleRef sourceRole, ThreadId threadId, TurnId turnId) {
    /** 校验关联中的所有标识。 */
    public PromptOptimizationRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sourceRole, "sourceRole");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
    }
}
