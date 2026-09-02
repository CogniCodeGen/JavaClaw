package com.javaclaw.desktop.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.Workspace;

/**
 * Workspace、Thread 选择和活动 Turn 的不可变快照。
 *
 * @param workspaces 当前可见 Workspace
 * @param selectedWorkspace 当前选择；尚未选择时为空
 * @param threads 所选 Workspace 下的 Thread
 * @param selectedThread 当前选择；尚未选择时为空
 * @param activeTurn 当前运行或等待交互的 Turn；没有时为空
 */
public record ThreadState(
        List<Workspace> workspaces,
        Optional<Workspace> selectedWorkspace,
        List<ConversationThread> threads,
        Optional<ConversationThread> selectedThread,
        Optional<AgentTurn> activeTurn) {
    /** 复制集合并校验归属关系。 */
    public ThreadState {
        workspaces = List.copyOf(workspaces);
        selectedWorkspace = Objects.requireNonNull(selectedWorkspace, "selectedWorkspace");
        threads = List.copyOf(threads);
        selectedThread = Objects.requireNonNull(selectedThread, "selectedThread");
        activeTurn = Objects.requireNonNull(activeTurn, "activeTurn");
        requireWorkspaceSelection(workspaces, selectedWorkspace);
        requireThreadSelection(threads, selectedThread, selectedWorkspace);
        requireTurnSelection(activeTurn, selectedThread);
    }

    /** @return 空选择状态 */
    public static ThreadState empty() {
        return new ThreadState(List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    private static void requireContained(List<Workspace> workspaces, Workspace selected) {
        if (workspaces.stream().noneMatch(workspace -> workspace.id().equals(selected.id()))) {
            throw new IllegalArgumentException("selected workspace is not present");
        }
    }

    private static void requireWorkspaceSelection(List<Workspace> workspaces, Optional<Workspace> selectedWorkspace) {
        selectedWorkspace.ifPresent(selected -> requireContained(workspaces, selected));
    }

    private static void requireThreadSelection(
            List<ConversationThread> threads,
            Optional<ConversationThread> selectedThread,
            Optional<Workspace> selectedWorkspace) {
        selectedThread.ifPresent(selected -> requireThread(threads, selected, selectedWorkspace));
    }

    private static void requireTurnSelection(
            Optional<AgentTurn> activeTurn, Optional<ConversationThread> selectedThread) {
        activeTurn.ifPresent(turn -> requireTurn(turn, selectedThread));
    }

    private static void requireThread(
            List<ConversationThread> threads, ConversationThread selected, Optional<Workspace> selectedWorkspace) {
        boolean present = threads.stream().anyMatch(thread -> thread.id().equals(selected.id()));
        boolean sameWorkspace = selectedWorkspace
                .map(workspace -> workspace.id().equals(selected.workspaceId()))
                .orElse(false);
        if (!present || !sameWorkspace) {
            throw new IllegalArgumentException("selected thread is not in selected workspace");
        }
    }

    private static void requireTurn(AgentTurn turn, Optional<ConversationThread> selectedThread) {
        if (selectedThread.map(thread -> thread.id().equals(turn.threadId())).orElse(false)) {
            return;
        }
        throw new IllegalArgumentException("active turn is not in selected thread");
    }
}
