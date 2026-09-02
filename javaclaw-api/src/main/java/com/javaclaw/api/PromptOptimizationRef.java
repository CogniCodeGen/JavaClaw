package com.javaclaw.api;

import java.util.Objects;

/**
 * Prompt 优化任务及其普通 Thread/Turn 的稳定关联。
 *
 * @param id 优化任务标识
 * @param workspaceId 所属 Workspace
 * @param sourceProfile 启动时冻结的源 Agent Profile
 * @param threadId 承载审计对话的普通只读 Thread
 * @param turnId 由 Thin Harness 执行的普通 Turn
 */
public record PromptOptimizationRef(
        PromptOptimizationId id,
        WorkspaceId workspaceId,
        AgentProfileRef sourceProfile,
        ThreadId threadId,
        TurnId turnId) {
    /** 校验关联中的所有标识。 */
    public PromptOptimizationRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sourceProfile, "sourceProfile");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
    }
}
