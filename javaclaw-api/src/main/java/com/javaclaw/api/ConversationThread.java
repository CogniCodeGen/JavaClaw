package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread 的不可变快照。
 *
 * @param id Thread 标识
 * @param workspaceId 所属 Workspace
 * @param parentThreadId 父 Thread；根 Thread 为空
 * @param executionIntent 服务端验证的文件执行隔离意图
 * @param title 用户可见标题
 * @param status 生命周期状态
 * @param revision 乐观锁版本，从 1 开始
 * @param createdAt 创建时间
 * @param updatedAt 最近修改时间
 */
public record ConversationThread(
        ThreadId id,
        WorkspaceId workspaceId,
        Optional<ThreadId> parentThreadId,
        ThreadExecutionIntent executionIntent,
        String title,
        ThreadStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验父子关系和时间顺序。 */
    public ConversationThread {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        parentThreadId = Objects.requireNonNull(parentThreadId, "parentThreadId");
        if (parentThreadId.filter(id::equals).isPresent()) {
            throw new IllegalArgumentException("thread must not be its own parent");
        }
        Objects.requireNonNull(executionIntent, "executionIntent");
        boolean rootIntent = parentThreadId.isEmpty() && executionIntent == ThreadExecutionIntent.WORKSPACE;
        boolean childIntent = parentThreadId.isPresent() && executionIntent != ThreadExecutionIntent.WORKSPACE;
        if (!rootIntent && !childIntent) {
            throw new IllegalArgumentException("root and child Thread execution intent do not match");
        }
        title = Preconditions.text(title, "title");
        Objects.requireNonNull(status, "status");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
