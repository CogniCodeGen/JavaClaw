package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Workspace 默认 Profile 或 Thread 覆盖的版本化绑定。
 *
 * @param workspaceId 所属 Workspace
 * @param threadId Thread 覆盖；Workspace 默认绑定时为空
 * @param profile 精确 Profile 引用
 * @param revision 绑定自身的乐观锁版本
 * @param updatedAt 最近更新时间
 */
public record ProfileBinding(
        WorkspaceId workspaceId,
        Optional<ThreadId> threadId,
        AgentProfileRef profile,
        long revision,
        Instant updatedAt) {
    /** 校验绑定。 */
    public ProfileBinding {
        Objects.requireNonNull(workspaceId, "workspaceId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(profile, "profile");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
