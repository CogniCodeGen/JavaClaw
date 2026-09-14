package com.javaclaw.desktop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.state.TranscriptState;

/**
 * 进程内发送记录；仅由 UI 调度器访问，导航不能撤销原请求的正文或幂等身份。
 *
 * <p>记录不形成自动发送队列。未确认消息只能由用户显式重试；编辑区永远不是重试载荷的来源。
 */
final class DesktopSendLedger {
    private final Map<Scope, LinkedHashMap<String, Entry>> scopes = new LinkedHashMap<>();
    private DesktopState observed;

    OutgoingMessage begin(Command command, long attempt) {
        return begin(command, attempt, Long.MAX_VALUE);
    }

    OutgoingMessage begin(Command command, long attempt, long afterSequence) {
        LinkedHashMap<String, Entry> entries = entries(command.thread());
        String id = id(command.options());
        Entry previous = entries.get(id);
        if (previous != null && !previous.command().samePayload(command)) {
            throw new IllegalArgumentException("同一发送身份不能更改正文或执行配置");
        }
        OutgoingMessage message = new OutgoingMessage(
                id,
                command.text(),
                previous == null ? Optional.empty() : previous.message().turnId(),
                OutgoingMessage.Status.SENDING,
                attempt,
                previous == null ? afterSequence : previous.message().afterSequence());
        entries.put(id, new Entry(previous == null ? command : previous.command(), message));
        return message;
    }

    Command retry(ConversationThread thread, String id) {
        Entry entry = entries(thread).get(id);
        if (entry == null || entry.message().status() != OutgoingMessage.Status.UNCONFIRMED) {
            throw new IllegalStateException("这条消息已不需要重试，或不属于当前对话");
        }
        return entry.command();
    }

    boolean sending(ConversationThread thread) {
        return entries(thread).values().stream()
                .anyMatch(entry -> entry.message().status() == OutgoingMessage.Status.SENDING);
    }

    void accepted(Command command, AgentTurn turn) {
        update(command, OutgoingMessage.Status.ACCEPTED, Optional.of(turn.id()));
    }

    void unconfirmed(Command command) {
        update(command, OutgoingMessage.Status.UNCONFIRMED, Optional.empty());
    }

    private void update(Command command, OutgoingMessage.Status status, Optional<com.javaclaw.api.TurnId> turnId) {
        entries(command.thread()).computeIfPresent(id(command.options()), (id, entry) -> {
            OutgoingMessage before = entry.message();
            return new Entry(
                    entry.command(),
                    new OutgoingMessage(
                            id,
                            before.text(),
                            turnId.or(before::turnId),
                            status,
                            before.attempt(),
                            before.afterSequence()));
        });
    }

    TranscriptState project(ConversationThread thread, TranscriptState transcript) {
        reconcile(thread, transcript);
        return transcript.outgoings(messages(thread));
    }

    List<OutgoingMessage> messages(ConversationThread thread) {
        return entries(thread).values().stream().map(Entry::message).toList();
    }

    void observe(DesktopState state) {
        state.threads().selectedThread().ifPresent(thread -> {
            reconcile(thread, state.transcript());
            if (sameObservation(state, thread)) {
                // 摘要分页可能确认窗口外的用户消息；状态层已经去重的记录不能因后续导航再次出现。
                var before = observed.transcript().outgoings().stream()
                        .map(OutgoingMessage::id)
                        .toList();
                var visible = state.transcript().outgoings().stream()
                        .map(OutgoingMessage::id)
                        .toList();
                entries(thread)
                        .values()
                        .removeIf(entry -> entry.message().status() == OutgoingMessage.Status.ACCEPTED
                                && before.contains(entry.message().id())
                                && !visible.contains(entry.message().id()));
            }
        });
        observed = state;
    }

    private boolean sameObservation(DesktopState state, ConversationThread thread) {
        return observed != null
                && observed.connection().connectedAt().equals(state.connection().connectedAt())
                && observed.threads()
                        .selectedThread()
                        .filter(value -> value.id().equals(thread.id())
                                && value.workspaceId().equals(thread.workspaceId()))
                        .isPresent()
                && state.transcript().nextSequence() > 0
                && state.transcript().nextSequence() >= observed.transcript().nextSequence();
    }

    private void reconcile(ConversationThread thread, TranscriptState transcript) {
        entries(thread).values().removeIf(entry -> entry.message().committed(transcript.items(), transcript.history()));
    }

    private LinkedHashMap<String, Entry> entries(ConversationThread thread) {
        return scopes.computeIfAbsent(new Scope(thread.workspaceId(), thread.id()), ignored -> new LinkedHashMap<>());
    }

    static String id(CommandOptions options) {
        return "outgoing:" + options.idempotencyKey();
    }

    /** 冻结的原请求；thread、正文、覆盖和命令选项均不可空，重试不读取当前设置。 */
    record Command(ConversationThread thread, String text, ExecutionOverrides execution, CommandOptions options) {
        boolean samePayload(Command other) {
            return thread.id().equals(other.thread().id())
                    && thread.workspaceId().equals(other.thread().workspaceId())
                    && text.equals(other.text())
                    && execution.equals(other.execution())
                    && options.equals(other.options());
        }
    }

    /** 固定消息及展示确认状态；组件均不可空。 */
    private record Entry(Command command, OutgoingMessage message) {}

    /** 消息所属工作区和对话；组件均不可空。 */
    private record Scope(WorkspaceId workspace, ThreadId thread) {}
}
