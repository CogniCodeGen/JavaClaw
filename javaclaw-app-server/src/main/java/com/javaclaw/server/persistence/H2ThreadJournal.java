package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.javaclaw.agent.conversation.ConversationWindow;
import com.javaclaw.agent.runtime.persistence.ThreadJournal;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;

/** Atomic Thread/Turn/Item projection and event journal adapter. */
public final class H2ThreadJournal implements ThreadJournal {
    private final H2PersistenceEngine engine;
    private final H2PromptArchive prompts;

    H2ThreadJournal(H2PersistenceEngine engine) {
        this.engine = engine;
        this.prompts = new H2PromptArchive(engine.database());
    }

    @Override
    public AgentThread createThread(String workspaceId, Path cwd, String title, ThreadId parent, TurnId forked) {
        return engine.createThread(workspaceId, cwd, title, parent, forked);
    }

    @Override
    public AgentThread createThread(
            String workspaceId, Path cwd, String title, ThreadId parent, TurnId forked, String key) {
        return engine.createThread(workspaceId, cwd, title, parent, forked, key);
    }

    @Override
    public AgentThread forkThread(ThreadId source, TurnId through, String title, Path cwd) {
        return engine.forkThread(source, through, title, cwd);
    }

    @Override
    public AgentThread forkThread(ThreadId source, TurnId through, String title, Path cwd, String key) {
        return engine.forkThread(source, through, title, cwd, key);
    }

    @Override
    public AgentThread forkBeforeTurn(ThreadId source, TurnId target, String title, Path cwd, String key) {
        return engine.forkBeforeTurn(source, target, title, cwd, key);
    }

    @Override
    public void rollbackRetryBranch(ThreadId id, String branchIdempotencyKey, String turnIdempotencyKey) {
        engine.rollbackRetryBranch(id, branchIdempotencyKey, turnIdempotencyKey);
    }

    @Override
    public Optional<AgentThread> findThread(ThreadId id) {
        return engine.findThread(id);
    }

    @Override
    public List<AgentThread> listThreads(boolean archived) {
        return engine.listThreads(archived);
    }

    @Override
    public AgentThread updateThreadTitle(ThreadId id, String title) {
        return engine.updateThreadTitle(id, title);
    }

    @Override
    public AgentThread updateThreadTitle(ThreadId id, String title, long revision, String key) {
        return engine.updateThreadTitle(id, title, revision, key);
    }

    @Override
    public AgentThread setThreadStatus(ThreadId id, ThreadStatus status) {
        return engine.setThreadStatus(id, status);
    }

    @Override
    public AgentThread setThreadStatus(ThreadId id, ThreadStatus status, long revision, String key) {
        return engine.setThreadStatus(id, status, revision, key);
    }

    @Override
    public void deleteThread(ThreadId id) {
        engine.deleteThread(id);
    }

    @Override
    public void deleteThread(ThreadId id, long revision, String key) {
        engine.deleteThread(id, revision, key);
    }

    @Override
    public AgentTurn startTurn(TurnStartCommand command) {
        return engine.startTurn(command);
    }

    @Override
    public Optional<AgentTurn> findTurn(TurnId id) {
        return engine.findTurn(id);
    }

    @Override
    public Optional<StoredItem> findItem(ItemId id) {
        return engine.findItem(id);
    }

    @Override
    public Optional<AgentTurn> findTurnByIdempotencyKey(ThreadId id, String key) {
        return engine.findTurnByIdempotencyKey(id, key);
    }

    @Override
    public AgentTurn transitionTurn(TurnId id, TurnStatus expected, TurnStatus next, String error) {
        return engine.transitionTurn(id, expected, next, error);
    }

    @Override
    public void recordUsage(ThreadId threadId, TurnId turnId, ModelUsage delta) {
        engine.recordUsage(threadId, turnId, delta);
    }

    @Override
    public void recordPromptSnapshot(
            ThreadId threadId, TurnId turnId, com.javaclaw.agent.prompt.PromptSnapshot snapshot, int ordinal) {
        prompts.record(threadId, turnId, snapshot, ordinal);
    }

    @Override
    public StoredItem startItem(ThreadId threadId, TurnId turnId, String kind) {
        return engine.startItem(threadId, turnId, kind);
    }

    @Override
    public StoredItem completeItem(ItemId id, ThreadItem item) {
        return engine.completeItem(id, item);
    }

    @Override
    public StoredItem failItem(ItemId id, String code, String message, boolean retryable) {
        return engine.failItem(id, code, message, retryable);
    }

    @Override
    public StoredItem appendItem(ThreadId threadId, TurnId turnId, ThreadItem item, ItemState state) {
        return engine.appendItem(threadId, turnId, item, state);
    }

    @Override
    public Optional<ConversationWindow> activeConversationWindow(ThreadId threadId) {
        return engine.activeConversationWindow(threadId);
    }

    @Override
    public void saveProviderConversationState(
            ThreadId threadId, String model, long coveredSequence, ProviderConversationState state, ModelUsage usage) {
        engine.saveProviderConversationState(threadId, model, coveredSequence, state, usage);
    }

    @Override
    public StoredItem completeCompaction(
            ItemId itemId, ThreadItem.ContextCompaction item, ConversationWindow.Replacement replacement) {
        return engine.completeCompaction(itemId, item, replacement);
    }

    @Override
    public List<StoredItem> items(ThreadId id) {
        return engine.items(id);
    }

    @Override
    public List<ThreadEvent> eventsAfter(ThreadId id, long sequence, int limit) {
        return engine.eventsAfter(id, sequence, limit);
    }

    @Override
    public ThreadSnapshot snapshot(ThreadId id) {
        return engine.snapshot(id);
    }

    @Override
    public int recoverInterruptedTurns() {
        return engine.recoverInterruptedTurns();
    }

    ExecutionAttemptRow executionAttempt(AttemptId id) {
        var value = engine.executionAttempt(id);
        return new ExecutionAttemptRow(
                value.id(),
                value.turnId(),
                value.number(),
                value.status(),
                value.provider(),
                value.model(),
                value.inputTokens(),
                value.outputTokens(),
                value.reasoningTokens(),
                value.startedAt(),
                value.completedAt(),
                value.error());
    }

    record ExecutionAttemptRow(
            AttemptId id,
            TurnId turnId,
            int number,
            TurnStatus status,
            String provider,
            String model,
            long inputTokens,
            long outputTokens,
            long reasoningTokens,
            Instant startedAt,
            Instant completedAt,
            String error) {}
}
