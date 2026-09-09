package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 服务端带乐观锁的执行配置；全局记录的两个标识均为空，Thread 级必须同时指定 Workspace。
 *
 * <p>正常执行、子任务默认和最近选择使用各自独立的存储命名空间及版本；用途由所调用的 SDK 方法确定。
 *
 * @param workspaceId Workspace 作用域；全局记录为空
 * @param threadId Thread 作用域；全局或 Workspace 级为空
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
