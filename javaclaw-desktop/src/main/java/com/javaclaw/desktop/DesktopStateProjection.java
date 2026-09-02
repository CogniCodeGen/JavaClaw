package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
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

    static DesktopState selectProfile(DesktopState state, AgentProfile profile) {
        return interaction(state, interaction(state, Optional.of(profile), false));
    }

    static DesktopState clearProfileSelection(DesktopState state) {
        return interaction(state, interaction(state, Optional.empty(), false));
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
                        catalog.profiles(),
                        Optional.empty(),
                        List.of(),
                        InputInteractionState.initial(),
                        false,
                        Optional.empty()));
    }

    static DesktopState catalog(
            DesktopState state, List<Workspace> workspaces, Workspace selected, List<ConversationThread> threads) {
        return new DesktopState(
                state.connection(),
                state.navigation(),
                new ThreadState(workspaces, Optional.of(selected), threads, Optional.empty(), Optional.empty()),
                TranscriptState.empty(),
                state.interaction());
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
        ThreadState threads = new ThreadState(
                state.threads().workspaces(),
                state.threads().selectedWorkspace(),
                state.threads().threads(),
                state.threads().selectedThread(),
                Optional.of(turn));
        return threadsOnly(state, threads);
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
                state.interaction().profiles(),
                state.interaction().selectedProfile(),
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
                        state.interaction().profiles(),
                        state.interaction().selectedProfile(),
                        state.interaction().pendingApprovals(),
                        state.interaction().inputs(),
                        busy,
                        Optional.empty()));
    }

    static DesktopState failure(DesktopState state, String message) {
        return interaction(
                state,
                new InteractionState(
                        state.interaction().profiles(),
                        state.interaction().selectedProfile(),
                        state.interaction().pendingApprovals(),
                        state.interaction().inputs(),
                        false,
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

    private static InteractionState interaction(DesktopState state, Optional<AgentProfile> profile, boolean busy) {
        return new InteractionState(
                state.interaction().profiles(),
                profile,
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
                state.interaction().profiles(),
                state.interaction().selectedProfile(),
                state.interaction().pendingApprovals(),
                inputs,
                state.interaction().busy(),
                state.interaction().error());
        return interaction(state, interaction);
    }

    private static DesktopState threads(DesktopState state, ThreadState threads, TranscriptState transcript) {
        return new DesktopState(state.connection(), state.navigation(), threads, transcript, state.interaction());
    }

    private static DesktopState threadsOnly(DesktopState state, ThreadState threads) {
        return new DesktopState(
                state.connection(), state.navigation(), threads, state.transcript(), state.interaction());
    }

    private static DesktopState transcript(DesktopState state, TranscriptState transcript) {
        return new DesktopState(
                state.connection(), state.navigation(), state.threads(), transcript, state.interaction());
    }

    private static DesktopState interaction(DesktopState state, InteractionState interaction) {
        return new DesktopState(
                state.connection(), state.navigation(), state.threads(), state.transcript(), interaction);
    }
}
