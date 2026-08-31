package com.javaclaw.agent.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.agent.runtime.persistence.EventOutbox;
import com.javaclaw.agent.runtime.persistence.InteractionRepository;
import com.javaclaw.agent.runtime.persistence.RuntimePersistence;
import com.javaclaw.agent.runtime.persistence.ThreadJournal;
import com.javaclaw.agent.runtime.persistence.WorkspaceRepository;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalResolution;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.StoredItem;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.UserInputResolution;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;

/** Small deterministic store for Agent Runtime unit tests; durable behavior belongs to H2 ITs. */
final class InMemoryThreadStore implements WorkspaceRepository, ThreadJournal, EventOutbox, InteractionRepository {
    RuntimePersistence persistence() {
        return new RuntimePersistence(this, this, this, this);
    }

    private final AtomicLong ids = new AtomicLong();
    private final Map<WorkspaceId, Workspace> workspaces = new LinkedHashMap<>();
    private final Map<ThreadId, AgentThread> threads = new LinkedHashMap<>();
    private final Map<TurnId, AgentTurn> turns = new LinkedHashMap<>();
    private final Map<TurnId, String> turnKeys = new LinkedHashMap<>();
    private final Map<ThreadId, List<TurnId>> threadTurns = new LinkedHashMap<>();
    private final Map<ThreadId, List<StoredItem>> items = new LinkedHashMap<>();
    private final Map<ThreadId, List<ThreadEvent>> events = new LinkedHashMap<>();
    private final Set<String> published = new java.util.HashSet<>();
    private final Map<String, com.javaclaw.agent.prompt.PromptSnapshot> promptSnapshots = new LinkedHashMap<>();

    @Override
    public synchronized void recordPromptSnapshot(
            ThreadId threadId, TurnId turnId, com.javaclaw.agent.prompt.PromptSnapshot snapshot, int ordinal) {
        if (promptSnapshots.putIfAbsent(turnId.value() + ":" + ordinal, snapshot) != null) {
            throw new IllegalStateException("duplicate model invocation");
        }
    }

    @Override
    public synchronized Workspace create(String name, Path root, String idempotencyKey) {
        Optional<Workspace> existing = findByRoot(root);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        Workspace workspace =
                new Workspace(new WorkspaceId("ws_" + ids.incrementAndGet()), name, root, 1, false, "", now, now);
        workspaces.put(workspace.id(), workspace);
        return workspace;
    }

    @Override
    public synchronized Optional<Workspace> find(WorkspaceId id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    @Override
    public synchronized Optional<Workspace> findByRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        return workspaces.values().stream()
                .filter(value -> value.root().equals(normalized))
                .findFirst();
    }

    @Override
    public synchronized List<Workspace> list() {
        return List.copyOf(workspaces.values());
    }

    @Override
    public synchronized Workspace update(WorkspaceId id, String name, long expectedRevision) {
        Workspace current = requireWorkspace(id);
        requireRevision(current.revision(), expectedRevision);
        Workspace updated = new Workspace(
                id,
                name,
                current.root(),
                current.revision() + 1,
                current.locked(),
                current.lockReason(),
                current.createdAt(),
                Instant.now());
        workspaces.put(id, updated);
        return updated;
    }

    @Override
    public synchronized Workspace setLocked(WorkspaceId id, boolean locked, String reason, long expectedRevision) {
        Workspace current = requireWorkspace(id);
        requireRevision(current.revision(), expectedRevision);
        Workspace updated = new Workspace(
                id,
                current.name(),
                current.root(),
                current.revision() + 1,
                locked,
                reason,
                current.createdAt(),
                Instant.now());
        workspaces.put(id, updated);
        return updated;
    }

    @Override
    public synchronized void delete(WorkspaceId id, long expectedRevision) {
        Workspace current = requireWorkspace(id);
        requireRevision(current.revision(), expectedRevision);
        workspaces.remove(id);
    }

    @Override
    public synchronized AgentThread createThread(
            String workspaceId, Path workingDirectory, String title, ThreadId parentThreadId, TurnId forkedFromTurnId) {
        Instant now = Instant.now();
        ThreadId id = ThreadId.random();
        AgentThread thread = new AgentThread(
                id,
                workspaceId,
                parentThreadId,
                forkedFromTurnId,
                title,
                workingDirectory,
                ThreadStatus.ACTIVE,
                0,
                0,
                1,
                now,
                now);
        threads.put(id, thread);
        threadTurns.put(id, new ArrayList<>());
        items.put(id, new ArrayList<>());
        events.put(id, new ArrayList<>());
        emit(id, null, "thread/started", Map.of("threadId", id.value()));
        return threads.get(id);
    }

    @Override
    public synchronized AgentThread forkThread(
            ThreadId source, TurnId throughTurn, String title, Path workingDirectory) {
        AgentThread parent = requireThread(source);
        AgentThread fork = createThread(
                parent.workspaceId(),
                workingDirectory == null ? parent.workingDirectory() : workingDirectory,
                title,
                source,
                throughTurn);
        List<TurnId> copiedTurns = threadTurns.getOrDefault(source, List.of()).stream()
                .takeWhile(value -> !value.equals(throughTurn))
                .toList();
        threadTurns.get(fork.id()).addAll(copiedTurns);
        return fork;
    }

    @Override
    public synchronized Optional<AgentThread> findThread(ThreadId id) {
        return Optional.ofNullable(threads.get(id));
    }

    @Override
    public synchronized List<AgentThread> listThreads(boolean includeArchived) {
        return threads.values().stream()
                .filter(value -> includeArchived || value.status() == ThreadStatus.ACTIVE)
                .toList();
    }

    @Override
    public synchronized AgentThread updateThreadTitle(ThreadId id, String title) {
        AgentThread current = requireThread(id);
        AgentThread updated = copy(current, title, current.status(), current.lastSequence(), current.revision() + 1);
        threads.put(id, updated);
        emit(id, null, "thread/updated", Map.of("title", title));
        return threads.get(id);
    }

    @Override
    public synchronized AgentThread setThreadStatus(ThreadId id, ThreadStatus status) {
        AgentThread current = requireThread(id);
        threads.put(id, copy(current, current.title(), status, current.lastSequence(), current.revision() + 1));
        emit(id, null, "thread/status", Map.of("status", status.name()));
        return threads.get(id);
    }

    @Override
    public synchronized void deleteThread(ThreadId id) {
        threads.remove(id);
        threadTurns.remove(id);
        items.remove(id);
        events.remove(id);
    }

    @Override
    public synchronized AgentTurn startTurn(TurnStartCommand command) {
        Optional<AgentTurn> replay = findTurnByIdempotencyKey(command.threadId(), command.idempotencyKey());
        if (replay.isPresent()) {
            return replay.get();
        }
        AgentThread thread = requireThread(command.threadId());
        if (threadTurns.get(thread.id()).stream()
                .map(turns::get)
                .anyMatch(value -> !value.status().terminal())) {
            throw new IllegalStateException("thread already has an active turn");
        }
        Instant now = Instant.now();
        AgentTurn turn = new AgentTurn(
                TurnId.random(),
                thread.id(),
                AttemptId.random(),
                TurnStatus.QUEUED,
                command.input(),
                command.config(),
                null,
                now,
                null);
        turns.put(turn.id(), turn);
        turnKeys.put(turn.id(), command.idempotencyKey());
        threadTurns.get(thread.id()).add(turn.id());
        emit(
                thread.id(),
                turn.id(),
                "turn/queued",
                Map.of("turnId", turn.id().value(), "status", TurnStatus.QUEUED.name()));
        if (!"COMPACTION".equals(command.config().attributes().get("invocationPurpose"))) {
            for (TurnInput input : command.input()) {
                String text = input instanceof TurnInput.Text value ? value.text() : input.toString();
                appendItem(
                        thread.id(),
                        turn.id(),
                        new ThreadItem.UserMessage(
                                text,
                                input instanceof TurnInput.AttachmentRef reference ? List.of(reference) : List.of()),
                        ItemState.COMPLETED);
            }
        }
        return turn;
    }

    @Override
    public synchronized Optional<AgentTurn> findTurn(TurnId id) {
        return Optional.ofNullable(turns.get(id));
    }

    @Override
    public synchronized Optional<StoredItem> findItem(ItemId id) {
        return items.values().stream()
                .flatMap(List::stream)
                .filter(value -> value.id().equals(id))
                .findFirst();
    }

    @Override
    public synchronized Optional<AgentTurn> findTurnByIdempotencyKey(ThreadId threadId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return threadTurns.getOrDefault(threadId, List.of()).stream()
                .filter(id -> idempotencyKey.equals(turnKeys.get(id)))
                .map(turns::get)
                .findFirst();
    }

    @Override
    public synchronized AgentTurn transitionTurn(TurnId id, TurnStatus expected, TurnStatus next, String error) {
        AgentTurn current = requireTurn(id);
        if (current.status() != expected) {
            throw new IllegalStateException("turn state conflict");
        }
        AgentTurn updated = new AgentTurn(
                current.id(),
                current.threadId(),
                current.attemptId(),
                next,
                current.input(),
                current.config(),
                error,
                current.startedAt(),
                next.terminal() ? Instant.now() : null);
        turns.put(id, updated);
        String type =
                switch (next) {
                    case IN_PROGRESS -> expected == TurnStatus.QUEUED ? "turn/started" : "turn/resumed";
                    case WAITING_FOR_APPROVAL -> "turn/waitingForApproval";
                    case WAITING_FOR_INPUT -> "turn/waitingForInput";
                    default -> "turn/completed";
                };
        emit(current.threadId(), id, type, Map.of("turnId", id.value(), "status", next.name()));
        return updated;
    }

    @Override
    public synchronized void recordUsage(ThreadId threadId, TurnId turnId, ModelUsage delta) {
        emit(
                threadId,
                turnId,
                "usage/updated",
                Map.of(
                        "inputTokens", Long.toString(delta.inputTokens()),
                        "outputTokens", Long.toString(delta.outputTokens())));
    }

    @Override
    public synchronized StoredItem startItem(ThreadId threadId, TurnId turnId, String kind) {
        long ordinal = nextOrdinal(threadId, turnId);
        Instant now = Instant.now();
        StoredItem item =
                new StoredItem(ItemId.random(), threadId, turnId, ordinal, ItemState.STARTED, kind, null, now, now);
        items.get(threadId).add(item);
        emit(threadId, turnId, "item/started", itemPayload(item));
        return item;
    }

    @Override
    public synchronized StoredItem completeItem(ItemId itemId, ThreadItem item) {
        return finishItem(itemId, ItemState.COMPLETED, item);
    }

    @Override
    public synchronized StoredItem failItem(ItemId itemId, String code, String message, boolean retryable) {
        return finishItem(itemId, ItemState.FAILED, new ThreadItem.ErrorItem(code, message, retryable));
    }

    @Override
    public synchronized StoredItem appendItem(ThreadId threadId, TurnId turnId, ThreadItem item, ItemState state) {
        requireTurn(turnId);
        long ordinal = nextOrdinal(threadId, turnId);
        Instant now = Instant.now();
        StoredItem stored = new StoredItem(ItemId.random(), threadId, turnId, ordinal, state, item, now, now);
        items.get(threadId).add(stored);
        emit(threadId, turnId, "item/" + state.name().toLowerCase(), itemPayload(stored));
        return stored;
    }

    @Override
    public synchronized Optional<ApprovalResolution> resolveApproval(String approvalId, boolean approved) {
        return Optional.empty();
    }

    @Override
    public synchronized Optional<UserInputResolution> resolveUserInput(
            String requestId, String value, boolean cancelled) {
        return Optional.empty();
    }

    @Override
    public synchronized List<StoredItem> items(ThreadId threadId) {
        return List.copyOf(items.getOrDefault(threadId, List.of()));
    }

    @Override
    public synchronized List<ThreadEvent> eventsAfter(ThreadId threadId, long afterSequence, int limit) {
        return events.getOrDefault(threadId, List.of()).stream()
                .filter(value -> value.sequence() > afterSequence)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<ThreadEvent> unpublishedEvents(int limit) {
        return events.values().stream()
                .flatMap(List::stream)
                .filter(value -> !published.contains(eventKey(value)))
                .sorted(Comparator.comparing(ThreadEvent::timestamp))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean markEventPublished(ThreadId threadId, long sequence) {
        return published.add(threadId.value() + ":" + sequence);
    }

    @Override
    public synchronized ThreadSnapshot snapshot(ThreadId threadId) {
        return new ThreadSnapshot(
                requireThread(threadId),
                threadTurns.getOrDefault(threadId, List.of()).stream()
                        .map(turns::get)
                        .toList(),
                items(threadId));
    }

    @Override
    public int recoverInterruptedTurns() {
        return 0;
    }

    private StoredItem finishItem(ItemId id, ItemState state, ThreadItem content) {
        for (Map.Entry<ThreadId, List<StoredItem>> entry : items.entrySet()) {
            List<StoredItem> values = entry.getValue();
            for (int index = 0; index < values.size(); index++) {
                StoredItem current = values.get(index);
                if (!current.id().equals(id)) {
                    continue;
                }
                StoredItem updated = new StoredItem(
                        id,
                        current.threadId(),
                        current.turnId(),
                        current.ordinal(),
                        state,
                        current.kind(),
                        content,
                        current.createdAt(),
                        Instant.now());
                values.set(index, updated);
                emit(
                        updated.threadId(),
                        updated.turnId(),
                        "item/" + state.name().toLowerCase(),
                        itemPayload(updated));
                return updated;
            }
        }
        throw new NoSuchElementException("item not found: " + id);
    }

    private long nextOrdinal(ThreadId threadId, TurnId turnId) {
        return items.getOrDefault(threadId, List.of()).stream()
                        .filter(value -> value.turnId().equals(turnId))
                        .count()
                + 1;
    }

    private void emit(ThreadId threadId, TurnId turnId, String type, Map<String, String> payload) {
        AgentThread current = requireThread(threadId);
        long sequence = current.lastSequence() + 1;
        Instant now = Instant.now();
        threads.put(threadId, copy(current, current.title(), current.status(), sequence, current.revision()));
        events.get(threadId)
                .add(new ThreadEvent(
                        "evt_" + ids.incrementAndGet(),
                        threadId,
                        turnId,
                        sequence,
                        type,
                        1,
                        turnId == null ? threadId.value() : turnId.value(),
                        null,
                        payload,
                        now));
    }

    private static Map<String, String> itemPayload(StoredItem item) {
        return Map.of(
                "itemId",
                item.id().value(),
                "kind",
                item.kind(),
                "state",
                item.state().name(),
                "ordinal",
                Long.toString(item.ordinal()));
    }

    private static String eventKey(ThreadEvent event) {
        return event.threadId().value() + ":" + event.sequence();
    }

    private AgentThread requireThread(ThreadId id) {
        AgentThread value = threads.get(id);
        if (value == null) {
            throw new NoSuchElementException("thread not found: " + id);
        }
        return value;
    }

    private AgentTurn requireTurn(TurnId id) {
        AgentTurn value = turns.get(id);
        if (value == null) {
            throw new NoSuchElementException("turn not found: " + id);
        }
        return value;
    }

    private Workspace requireWorkspace(WorkspaceId id) {
        Workspace value = workspaces.get(id);
        if (value == null) {
            throw new NoSuchElementException("workspace not found: " + id);
        }
        return value;
    }

    private static AgentThread copy(
            AgentThread source, String title, ThreadStatus status, long sequence, long revision) {
        return new AgentThread(
                source.id(),
                source.workspaceId(),
                source.parentThreadId(),
                source.forkedFromTurnId(),
                title,
                source.workingDirectory(),
                status,
                source.baseSequence(),
                sequence,
                revision,
                source.createdAt(),
                Instant.now());
    }

    private static void requireRevision(long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException("revision conflict");
        }
    }
}
