package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * H2 中带乐观锁的执行配置；安装级两个标识都为空，Thread 级必须同时指定 Workspace。
 *
 * @param workspaceId Workspace 作用域；安装级为空
 * @param threadId Thread 作用域；安装或 Workspace 级为空
 * @param overrides 当前作用域的显式执行选择
 * @param revision 当前配置版本，从 1 开始
 * @param updatedAt 最近更新时间
 */
public record ExecutionConfiguration(
        Optional<WorkspaceId> workspaceId,
        Optional<ThreadId> threadId,
        ExecutionOverrides overrides,
        long revision,
        Instant updatedAt) {
    /** 校验作用域与配置版本。 */
    public ExecutionConfiguration {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        if (threadId.isPresent() && workspaceId.isEmpty()) {
            throw new IllegalArgumentException("Thread configuration requires a Workspace");
        }
        Objects.requireNonNull(overrides, "overrides");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
