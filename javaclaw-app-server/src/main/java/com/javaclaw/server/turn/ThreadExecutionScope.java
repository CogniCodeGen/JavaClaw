package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.util.Objects;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;

/** 服务端根据 Thread 意图解析的执行根与写入能力。 */
record ThreadExecutionScope(ConversationThread thread, Workspace workspace, Path root, boolean writable) {
    /** 校验执行根为规范绝对路径。 */
    ThreadExecutionScope {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(workspace, "workspace");
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    static ThreadExecutionScope resolve(CoreCommandService core, ManagedWorktreeService worktrees, ThreadId threadId) {
        ConversationThread thread = core.findThread(Objects.requireNonNull(threadId, "threadId"))
                .orElseThrow(() -> new IllegalArgumentException("Thread 不存在"));
        Workspace workspace = core.workspaceForThread(thread.id());
        return switch (thread.executionIntent()) {
            case WORKSPACE -> new ThreadExecutionScope(thread, workspace, workspace.root(), true);
            case READ_ONLY -> new ThreadExecutionScope(thread, workspace, workspace.root(), false);
            case ISOLATED_WRITE -> isolated(thread, workspace, worktrees);
        };
    }

    private static ThreadExecutionScope isolated(
            ConversationThread thread, Workspace workspace, ManagedWorktreeService worktrees) {
        ManagedWorktree worktree = worktrees.requireForExecution(thread.id());
        if (!worktree.workspaceId().equals(workspace.id())
                || !worktree.childThreadId().equals(thread.id())
                || thread.parentThreadId()
                        .filter(worktree.parentThreadId()::equals)
                        .isEmpty()) {
            throw new IllegalStateException("Managed Worktree 与 Thread 权威绑定不一致");
        }
        return new ThreadExecutionScope(thread, workspace, worktree.executionRoot(), true);
    }

    boolean isolatedWrite() {
        return thread.executionIntent() == ThreadExecutionIntent.ISOLATED_WRITE;
    }
}
