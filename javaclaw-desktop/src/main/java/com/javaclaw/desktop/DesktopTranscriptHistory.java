package com.javaclaw.desktop;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.TranscriptState;

/** 最近100条与向前分页；新能力只使用摘要 DTO，不把截断正文伪装成原 ItemEnvelope。 */
final class DesktopTranscriptHistory {
    private DesktopTranscriptHistory() {}

    static TranscriptState latest(JavaClawClient client, ConversationThread thread) {
        return latest(client, thread, () -> false);
    }

    static TranscriptState latest(
            JavaClawClient client, ConversationThread thread, java.util.function.BooleanSupplier cancelled) {
        if (client.streams().available()) {
            ItemHistoryResult result = client.items().history(thread.id(), 0, 100);
            return new TranscriptState(
                    List.of(), result.latestSequence(), true, Optional.empty(), result.hasEarlier(), result.items());
        }
        return DesktopLegacyHistory.latest(client, thread, cancelled);
    }

    /** 历史只提供候选 TurnId；活动状态和所属 Thread 必须通过 SDK 权威复核，不能从摘要推测。 */
    static Optional<com.javaclaw.api.AgentTurn> active(
            JavaClawClient client, ConversationThread thread, TranscriptState transcript) {
        Optional<com.javaclaw.api.TurnId> latest = !transcript.history().isEmpty()
                ? Optional.of(transcript.history().getLast().turnId())
                : transcript.items().isEmpty()
                        ? Optional.empty()
                        : Optional.of(transcript.items().getLast().turnId());
        return latest.map(id -> {
                    var turn = client.turns().read(id);
                    if (!turn.threadId().equals(thread.id())) {
                        throw new IllegalStateException("历史 Turn 不属于目标会话");
                    }
                    return turn;
                })
                .filter(turn -> !DesktopStateProjection.terminal(turn.status()));
    }

    static Navigation navigation(
            JavaClawClient connected, com.javaclaw.api.ThreadId id, java.util.function.BooleanSupplier cancelled) {
        ConversationThread thread = connected.threads().read(id);
        var workspace = connected.workspaces().list().stream()
                .filter(candidate -> candidate.id().equals(thread.workspaceId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Thread 所属 Workspace 不可用"));
        return new Navigation(
                workspace, connected.threads().list(workspace.id()), thread, latest(connected, thread, cancelled));
    }

    record Navigation(
            com.javaclaw.api.Workspace workspace,
            List<ConversationThread> threads,
            ConversationThread thread,
            TranscriptState transcript) {}

    static void earlier(
            java.util.function.Supplier<JavaClawClient> client,
            DesktopStore store,
            java.util.function.Consumer<java.util.function.UnaryOperator<DesktopState>> update,
            java.util.concurrent.ExecutorService workers) {
        DesktopState current = store.state();
        var selected = current.threads().selectedThread();
        if (selected.isEmpty()
                || current.transcript().history().isEmpty()
                || !current.transcript().hasEarlier()) {
            return;
        }
        long before = current.transcript().history().getFirst().sequence();
        workers.submit(() -> {
            try {
                var result = client.get().items().history(selected.orElseThrow().id(), before, 100);
                update.accept(state -> state.connection()
                                .connectedAt()
                                .equals(current.connection().connectedAt())
                        ? apply(
                                state,
                                selected.orElseThrow(),
                                state.transcript().earlier(result))
                        : state);
            } catch (RuntimeException failure) {
                update.accept(state -> DesktopStateProjection.failure(state, DesktopFailures.safeMessage(failure)));
            }
        });
    }

    static void follow(
            boolean following,
            java.util.function.Supplier<JavaClawClient> client,
            DesktopStore store,
            java.util.function.Consumer<java.util.function.UnaryOperator<DesktopState>> update,
            java.util.concurrent.ExecutorService workers,
            java.util.function.BooleanSupplier cancelled) {
        DesktopState captured = store.state();
        update.accept(state ->
                DesktopStateProjection.transcript(state, state.transcript().following(following)));
        if (!following
                || captured.threads().selectedThread().isEmpty()
                || captured.transcript().history().isEmpty()) {
            return;
        }
        var selected = captured.threads().selectedThread().orElseThrow();
        workers.submit(() -> {
            try {
                TranscriptState latest = latest(client.get(), selected, cancelled);
                update.accept(state -> {
                    if (!state.connection()
                                    .connectedAt()
                                    .equals(captured.connection().connectedAt())
                            || !state.transcript().following()
                            || latest.nextSequence() < state.transcript().nextSequence()) {
                        return state;
                    }
                    TranscriptState next = new TranscriptState(
                            latest.items(),
                            latest.nextSequence(),
                            true,
                            state.transcript().stream(),
                            latest.hasEarlier(),
                            latest.history());
                    return apply(state, selected, next);
                });
            } catch (RuntimeException failure) {
                update.accept(state -> DesktopStateProjection.failure(state, DesktopFailures.safeMessage(failure)));
            }
        });
    }

    static DesktopState apply(DesktopState state, ConversationThread thread, TranscriptState transcript) {
        return state.threads()
                        .selectedThread()
                        .filter(value -> value.id().equals(thread.id()))
                        .isPresent()
                ? DesktopStateProjection.transcript(state, transcript)
                : state;
    }
}
