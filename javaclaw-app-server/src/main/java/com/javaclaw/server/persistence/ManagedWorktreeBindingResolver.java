package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.Objects;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;

/** 校验 Managed Worktree 只能绑定同一 Workspace 的直接父子 Thread。 */
final class ManagedWorktreeBindingResolver {
    private final H2Transactions transactions;
    private final WorkspaceRepository workspaces = new WorkspaceRepository();
    private final ThreadRepository threads = new ThreadRepository();

    ManagedWorktreeBindingResolver(H2Database database) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
    }

    Binding require(WorkspaceId workspaceId, ThreadId parentThreadId, ThreadId childThreadId) {
        return execute(connection -> {
            Workspace workspace = workspaces
                    .find(connection, Objects.requireNonNull(workspaceId, "workspaceId"))
                    .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
            ConversationThread parent = threads.find(
                            connection, Objects.requireNonNull(parentThreadId, "parentThreadId"))
                    .orElseThrow(() -> PersistenceException.invalidRequest("父 Thread 不存在"));
            ConversationThread child = threads.find(connection, Objects.requireNonNull(childThreadId, "childThreadId"))
                    .orElseThrow(() -> PersistenceException.invalidRequest("子 Thread 不存在"));
            boolean valid = workspace.lifecycle() == WorkspaceLifecycle.ACTIVE
                    && parent.workspaceId().equals(workspace.id())
                    && child.workspaceId().equals(workspace.id())
                    && child.parentThreadId().filter(parent.id()::equals).isPresent();
            if (!valid) {
                throw PersistenceException.invalidRequest("Worktree 必须绑定同一 Workspace 的直接父子 Thread");
            }
            return new Binding(workspace, parent, child);
        });
    }

    static void requireSame(ManagedWorktree current, Binding binding, Path destination) {
        boolean same = current.workspaceId().equals(binding.workspace().id())
                && current.parentThreadId().equals(binding.parent().id())
                && current.childThreadId().equals(binding.child().id())
                && current.executionRoot().equals(destination);
        if (!same || current.state() == com.javaclaw.api.ManagedWorktreeState.CLEANED) {
            throw PersistenceException.invalidRequest("子 Thread 已有不同或已清理的 Worktree 绑定");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Managed Worktree 绑定校验失败", failure);
        }
    }

    record Binding(Workspace workspace, ConversationThread parent, ConversationThread child) {}
}
