package com.javaclaw.agent.runtime.persistence;

import java.util.Objects;

/**
 * Explicit assembly of narrow persistence ports; this type has no persistence behavior.
 *
 * @param workspaces 非空工作区登记端口
 * @param journal 非空 Thread/Turn/Item 原子日志端口
 * @param outbox 非空事件补发端口
 * @param interactions 非空审批/用户输入决议端口
 */
public record RuntimePersistence(
        WorkspaceRepository workspaces, ThreadJournal journal, EventOutbox outbox, InteractionRepository interactions) {
    /** 要求四个持久端口完整；此聚合不持有额外连接，也不改变各端口事务边界。 */
    public RuntimePersistence {
        Objects.requireNonNull(workspaces, "workspaces");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(interactions, "interactions");
    }
}
