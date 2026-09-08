package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.InputInteractionState;
import com.javaclaw.desktop.state.InteractionState;
import com.javaclaw.desktop.state.ThreadState;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.protocol.CoreRpcContracts;

/** 把 SDK 事实纯函数式投影为新的 DesktopState。 */
final class DesktopStateProjection {
    private DesktopStateProjection() {}

    static DesktopState selectWorkspace(DesktopState state, Workspace workspace) {
        ThreadState threads = new ThreadState(
                state.threads().workspaces(), Optional.of(workspace), List.of(), Optional.empty(), Optional.empty());
        return threads(state, threads, TranscriptState.empty());
    }

    static DesktopState selectThread(DesktopState state, ConversationThread thread) {
        ThreadState threads = new ThreadState(
                state.threads().workspaces(),
                state.threads().selectedWorkspace(),
                state.threads().threads(),
                Optional.of(thread),
                Optional.empty());
        return threads(state, threads, TranscriptState.empty());
    }

    static DesktopState selectRole(DesktopState state, AgentRole role) {
        return interaction(
                state, interaction(state, Optional.of(role), state.interaction().busy()));
    }

    static DesktopState clearRoleSelection(DesktopState state) {
        return interaction(
                state, interaction(state, Optional.empty(), state.interaction().busy()));
    }

    static DesktopState connected(
            DesktopState state, String detail, Instant connectedAt, DesktopConnectionCatalog catalog) {
        return new DesktopState(
                ConnectionState.connected(detail, connectedAt),
                state.navigation(),
                new ThreadState(
                        catalog.workspaces(),
                        catalog.selectedWorkspace(),
                        catalog.threads(),
                        catalog.selectedThread(),
                        Optional.empty()),
                TranscriptState.empty(),
                new InteractionState(
                        catalog.roles(),
                        Optional.empty(),
                        List.of(),
                        InputInteractionState.initial(),
                        false,
                        Optional.empty()));
    }

    static DesktopState catalog(
            DesktopState state, List<Workspace> workspaces, Workspace selected, List<ConversationThread> threads) {
        return clearObservation(new DesktopState(
                state.connection(),
                state.navigation(),
                new ThreadState(workspaces, Optional.of(selected), threads, Optional.empty(), Optional.empty()),
                TranscriptState.empty(),
                state.interaction()));
    }

    static DesktopState threadCatalog(
            DesktopState state,
            Workspace workspace,
            List<ConversationThread> threads,
            Optional<ConversationThread> selected) {
        boolean sameWorkspace = state.threads()
                .selectedWorkspace()
                .filter(current -> current.id().equals(workspace.id()))
                .isPresent();
        if (!sameWorkspace) {
            return state;
        }
        ThreadState projected = new ThreadState(
                state.threads().workspaces(), Optional.of(workspace), threads, selected, Optional.empty());
        return threads(state, projected, TranscriptState.empty());
    }

    static DesktopState transcript(
            DesktopState state, ConversationThread thread, CoreRpcContracts.ItemListResult page) {
        boolean selected = state.threads()
                .selectedThread()
                .map(current -> current.id().equals(thread.id()))
                .orElse(false);
        return selected ? transcript(state, new TranscriptState(page.items(), page.nextSequence(), true)) : state;
    }

    static DesktopState activeTurn(DesktopState state, AgentTurn turn) {
        if (state.threads()
                .selectedThread()
                .map(ConversationThread::id)
                .filter(turn.threadId()::equals)
                .isEmpty()) {
            return state;
        }
        boolean running = !terminal(turn.status());
        ThreadState threads = new ThreadState(
                state.threads().workspaces(),
                state.threads().selectedWorkspace(),
                state.threads().threads(),
                state.threads().selectedThread(),
                running ? Optional.of(turn) : Optional.empty());
        return busy(threadsOnly(state, threads), running);
    }

    static DesktopState observation(
            DesktopState state, AgentTurn turn, CoreRpcContracts.ItemListResult page, List<ApprovalRecord> approvals) {
        boolean current = state.threads()
                .activeTurn()
                .map(active -> active.id().equals(turn.id()))
                .orElse(false);
        if (!current) {
            return state;
        }
        boolean terminal = terminal(turn.status());
        TranscriptState transcript = state.transcript().append(page.items(), page.nextSequence());
        ThreadState threads = new ThreadState(
                state.threads().workspaces(),
                state.threads().selectedWorkspace(),
                state.threads().threads(),
                state.threads().selectedThread(),
                terminal ? Optional.empty() : Optional.of(turn));
        InteractionState interaction = new InteractionState(
                state.interaction().roles(),
                state.interaction().selectedRole(),
                approvals,
                state.interaction().inputs(),
                !terminal,
                Optional.empty());
        return new DesktopState(state.connection(), state.navigation(), threads, transcript, interaction);
    }

    static DesktopState busy(DesktopState state, boolean busy) {
        return interaction(
                state,
                new InteractionState(
                        state.interaction().roles(),
                        state.interaction().selectedRole(),
                        state.interaction().pendingApprovals(),
                        state.interaction().inputs(),
                        busy,
                        Optional.empty()));
    }

    static DesktopState failure(DesktopState state, String message) {
        return interaction(
                state,
                new InteractionState(
                        state.interaction().roles(),
                        state.interaction().selectedRole(),
                        state.interaction().pendingApprovals(),
                        state.interaction().inputs(),
                        state.threads()
                                .activeTurn()
                                .filter(turn -> !terminal(turn.status()))
                                .isPresent(),
                        Optional.of(message)));
    }

    /** 恢复未能确认活动状态时保留错误并封锁发送，用户可通过明确重选重试。 */
    static DesktopState recoveryFailure(DesktopState state, String message) {
        var current = state.interaction();
        return interaction(
                state,
                new InteractionState(
                        current.roles(),
                        current.selectedRole(),
                        current.pendingApprovals(),
                        current.inputs(),
                        true,
                        Optional.of(message)));
    }

    static DesktopState inputOperation(DesktopState state, InputRequestRecord request, long requestEpoch) {
        InputInteractionState current = state.interaction().inputs();
        boolean available =
                current.pendingRequests().stream().anyMatch(candidate -> sameInputRevision(candidate, request));
        if (!available || requestEpoch < current.requestEpoch()) {
            return state;
        }
        InputInteractionState inputs = new InputInteractionState(
                current.pendingRequests(), Optional.of(request.request().id()), Optional.empty(), requestEpoch);
        return inputs(state, inputs);
    }

    static DesktopState inputs(DesktopState state, List<InputRequestRecord> requests, long requestEpoch) {
        InputInteractionState current = state.interaction().inputs();
        if (requestEpoch < current.requestEpoch()) {
            return state;
        }
        List<InputRequestRecord> pending =
                requests.stream().filter(InputRequestRecord::pending).toList();
        Optional<String> submitting = current.submittingRequestId()
                .filter(id -> pending.stream()
                        .anyMatch(request -> request.request().id().equals(id)));
        return inputs(state, new InputInteractionState(pending, submitting, Optional.empty(), requestEpoch));
    }

    static DesktopState inputFailure(DesktopState state, String message, long requestEpoch) {
        InputInteractionState current = state.interaction().inputs();
        if (requestEpoch < current.requestEpoch()) {
            return state;
        }
        InputInteractionState failed = new InputInteractionState(
                current.pendingRequests(), current.submittingRequestId(), Optional.of(message), requestEpoch);
        return inputs(state, failed);
    }

    static DesktopState inputOperationFailure(DesktopState state, String message, long requestEpoch) {
        InputInteractionState current = state.interaction().inputs();
        if (requestEpoch < current.requestEpoch()) {
            return state;
        }
        InputInteractionState failed = new InputInteractionState(
                current.pendingRequests(), Optional.empty(), Optional.of(message), requestEpoch);
        return inputs(state, failed);
    }

    static DesktopState connection(DesktopState state, ConnectionState connection) {
        return new DesktopState(
                connection, state.navigation(), state.threads(), state.transcript(), state.interaction());
    }

    static boolean terminal(TurnStatus status) {
        return status == TurnStatus.COMPLETED || status == TurnStatus.CANCELLED || status == TurnStatus.FAILED;
    }

    private static InteractionState interaction(DesktopState state, Optional<AgentRole> role, boolean busy) {
        return new InteractionState(
                state.interaction().roles(),
                role,
                state.interaction().pendingApprovals(),
                state.interaction().inputs(),
                busy,
                Optional.empty());
    }

    private static boolean sameInputRevision(InputRequestRecord left, InputRequestRecord right) {
        return left.request().id().equals(right.request().id()) && left.revision() == right.revision();
    }

    private static DesktopState inputs(DesktopState state, InputInteractionState inputs) {
        InteractionState interaction = new InteractionState(
                state.interaction().roles(),
                state.interaction().selectedRole(),
                state.interaction().pendingApprovals(),
                inputs,
                state.interaction().busy(),
                state.interaction().error());
        return interaction(state, interaction);
    }

    private static DesktopState threads(DesktopState state, ThreadState threads, TranscriptState transcript) {
        return clearObservation(
                new DesktopState(state.connection(), state.navigation(), threads, transcript, state.interaction()));
    }

    /** 导航只清理当前会话的临时交互；全局输入请求和用户显式角色选择继续保留。 */
    static DesktopState clearObservation(DesktopState state) {
        var threads = state.threads();
        var interaction = state.interaction();
        return new DesktopState(
                state.connection(),
                state.navigation(),
                new ThreadState(
                        threads.workspaces(),
                        threads.selectedWorkspace(),
                        threads.threads(),
                        threads.selectedThread(),
                        Optional.empty()),
                state.transcript(),
                new InteractionState(
                        interaction.roles(),
                        interaction.selectedRole(),
                        List.of(),
                        interaction.inputs(),
                        false,
                        Optional.empty()));
    }

    private static DesktopState threadsOnly(DesktopState state, ThreadState threads) {
        return new DesktopState(
                state.connection(), state.navigation(), threads, state.transcript(), state.interaction());
    }

    static DesktopState transcript(DesktopState state, TranscriptState transcript) {
        return new DesktopState(
                state.connection(), state.navigation(), state.threads(), transcript, state.interaction());
    }

    private static DesktopState interaction(DesktopState state, InteractionState interaction) {
        return new DesktopState(
                state.connection(), state.navigation(), state.threads(), state.transcript(), interaction);
    }
}
