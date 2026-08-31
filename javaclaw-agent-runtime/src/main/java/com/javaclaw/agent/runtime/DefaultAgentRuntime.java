package com.javaclaw.agent.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.javaclaw.agent.kernel.AgentKernel;
import com.javaclaw.agent.runtime.persistence.EventOutbox;
import com.javaclaw.agent.runtime.persistence.InteractionRepository;
import com.javaclaw.agent.runtime.persistence.RuntimePersistence;
import com.javaclaw.agent.runtime.persistence.ThreadJournal;
import com.javaclaw.agent.runtime.persistence.WorkspaceRepository;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalResolution;
import com.javaclaw.core.api.ItemDelta;
import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.ItemState;
import com.javaclaw.core.api.LiveItemEvent;
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

/** Process-level owner of thread scheduling, cancellation and durable event publication. */
public final class DefaultAgentRuntime implements AgentRuntime {
    private final WorkspaceRepository workspaces;
    private final ThreadJournal journal;
    private final EventOutbox outbox;
    private final InteractionRepository interactions;
    private final AgentKernel kernel;
    private final RuntimeEventBus events;
    private final LiveItemEventBus liveItems;
    private final ExecutorService turns;
    private final RuntimeLimits limits;
    private final WorkspaceWriteCoordinator writers = new WorkspaceWriteCoordinator();
    private final Map<ThreadId, ActiveTurn> activeByThread = new ConcurrentHashMap<>();
    private final Map<TurnId, ActiveTurn> activeByTurn = new ConcurrentHashMap<>();
    private final Map<ThreadId, Object> threadGates = new ConcurrentHashMap<>();
    private final Map<String, Integer> activeByWorkspace = new java.util.HashMap<>();
    private final Map<ThreadId, Integer> activeSubagentsByParent = new java.util.HashMap<>();
    private final Object quotaGate = new Object();
    private final Set<Path> maintenanceDirectories = new java.util.HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 使用默认并发上限和内存 Item 总线装配运行时；立即恢复遗留 Turn 并补发 Outbox，close 释放执行器和总线。 */
    public DefaultAgentRuntime(RuntimePersistence persistence, AgentKernel kernel, RuntimeEventBus events) {
        this(persistence, kernel, events, new LiveItemEventBus(), RuntimeLimits.defaults());
    }

    /** 使用指定安全配额和独立虚拟线程执行器；构造时恢复遗留执行并补发 Outbox。 */
    public DefaultAgentRuntime(
            RuntimePersistence persistence, AgentKernel kernel, RuntimeEventBus events, RuntimeLimits limits) {
        this(
                persistence,
                kernel,
                events,
                new LiveItemEventBus(),
                limits,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 装配共享持久端口、Kernel 与两类事件流；构造时进行恢复，close 接管执行器及总线的关闭。 */
    public DefaultAgentRuntime(
            RuntimePersistence persistence,
            AgentKernel kernel,
            RuntimeEventBus events,
            LiveItemEventBus liveItems,
            RuntimeLimits limits) {
        this(
                persistence,
                kernel,
                events,
                liveItems,
                limits,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    DefaultAgentRuntime(
            RuntimePersistence persistence,
            AgentKernel kernel,
            RuntimeEventBus events,
            LiveItemEventBus liveItems,
            RuntimeLimits limits,
            ExecutorService turns) {
        Objects.requireNonNull(persistence, "persistence");
        this.workspaces = persistence.workspaces();
        this.journal = persistence.journal();
        this.outbox = persistence.outbox();
        this.interactions = persistence.interactions();
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.events = Objects.requireNonNull(events, "events");
        this.liveItems = Objects.requireNonNull(liveItems, "liveItems");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.turns = Objects.requireNonNull(turns, "turns");
        journal.recoverInterruptedTurns();
        drainOutbox();
    }

    DefaultAgentRuntime(
            RuntimePersistence persistence,
            AgentKernel kernel,
            RuntimeEventBus events,
            RuntimeLimits limits,
            ExecutorService turns) {
        this(persistence, kernel, events, new LiveItemEventBus(), limits, turns);
    }

    /** 返回运行时持久事件的实时投影总线；重放仍应使用 eventsAfter。 */
    public RuntimeEventBus liveEvents() {
        return events;
    }

    /** 返回非持久 Item 增量总线；仅供进程内装配，不能作为权威历史。 */
    public LiveItemEventBus liveItemEvents() {
        return liveItems;
    }

    @Override
    public java.util.concurrent.Flow.Publisher<ThreadEvent> events() {
        return events;
    }

    @Override
    public LiveItemSource liveItems() {
        return liveItems;
    }

    @Override
    public Workspace createWorkspace(String name, Path root, String idempotencyKey) {
        requireOpen();
        return workspaces.create(name, root, idempotencyKey);
    }

    @Override
    public Optional<Workspace> readWorkspace(WorkspaceId id) {
        requireOpen();
        return workspaces.find(id);
    }

    @Override
    public List<Workspace> listWorkspaces() {
        requireOpen();
        return workspaces.list();
    }

    @Override
    public Workspace updateWorkspace(WorkspaceId id, String name, long expectedRevision) {
        return updateWorkspace(id, name, expectedRevision, null);
    }

    @Override
    public Workspace updateWorkspace(WorkspaceId id, String name, long expectedRevision, String idempotencyKey) {
        requireOpen();
        return workspaces.update(id, name, expectedRevision, idempotencyKey);
    }

    @Override
    public void deleteWorkspace(WorkspaceId id, long expectedRevision) {
        deleteWorkspace(id, expectedRevision, null);
    }

    @Override
    public void deleteWorkspace(WorkspaceId id, long expectedRevision, String idempotencyKey) {
        requireOpen();
        workspaces.delete(id, expectedRevision, idempotencyKey);
    }

    @Override
    public AgentThread startThread(WorkspaceId workspaceId, String title) {
        return startThread(workspaceId, title, null);
    }

    @Override
    public AgentThread startThread(WorkspaceId workspaceId, String title, String idempotencyKey) {
        requireOpen();
        Workspace workspace = workspaces
                .find(workspaceId)
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + workspaceId));
        if (workspace.locked()) {
            throw new IllegalStateException("workspace is locked: " + workspace.lockReason());
        }
        AgentThread created =
                journal.createThread(workspaceId.value(), workspace.root(), title, null, null, idempotencyKey);
        publishAfter(created.id(), 0);
        return created;
    }

    @Override
    public AgentThread startChildThread(ThreadId parentThreadId, String title, Path workingDirectory) {
        requireOpen();
        AgentThread parent = requireThread(parentThreadId);
        Workspace workspace = workspaces
                .find(new WorkspaceId(parent.workspaceId()))
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + parent.workspaceId()));
        if (workspace.locked()) {
            throw new IllegalStateException("workspace is locked: " + workspace.lockReason());
        }
        Path directory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        if (!java.nio.file.Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("child working directory must exist");
        }
        AgentThread child = journal.createThread(parent.workspaceId(), directory, title, parent.id(), null);
        publishAfter(child.id(), 0);
        return child;
    }

    @Override
    public AgentThread updateThread(ThreadId id, String title) {
        requireOpen();
        AgentThread before = requireThread(id);
        AgentThread updated = journal.updateThreadTitle(id, title);
        publishAfter(id, before.lastSequence());
        return updated;
    }

    @Override
    public AgentThread updateThread(ThreadId id, String title, long expectedRevision, String idempotencyKey) {
        requireOpen();
        AgentThread before = requireThread(id);
        AgentThread updated = journal.updateThreadTitle(id, title, expectedRevision, idempotencyKey);
        publishAfter(id, before.lastSequence());
        return updated;
    }

    @Override
    public AgentThread forkThread(ThreadId source, TurnId throughTurn, String title, Path workingDirectory) {
        return forkThread(source, throughTurn, title, workingDirectory, null);
    }

    @Override
    public AgentThread forkThread(
            ThreadId source, TurnId throughTurn, String title, Path workingDirectory, String idempotencyKey) {
        requireOpen();
        AgentThread fork = journal.forkThread(source, throughTurn, title, workingDirectory, idempotencyKey);
        publishAfter(fork.id(), 0);
        return fork;
    }

    @Override
    public AgentThread forkBeforeTurn(
            ThreadId source, TurnId targetTurn, String title, Path workingDirectory, String idempotencyKey) {
        requireOpen();
        AgentThread fork = journal.forkBeforeTurn(source, targetTurn, title, workingDirectory, idempotencyKey);
        publishAfter(fork.id(), 0);
        return fork;
    }

    @Override
    public void rollbackRetryBranch(ThreadId id, String branchIdempotencyKey, String turnIdempotencyKey) {
        requireOpen();
        if (activeByThread.containsKey(id)) {
            throw new IllegalStateException("cannot roll back a retry branch with an active Turn: " + id);
        }
        journal.rollbackRetryBranch(id, branchIdempotencyKey, turnIdempotencyKey);
    }

    @Override
    public Optional<ThreadSnapshot> readThread(ThreadId id) {
        requireOpen();
        return journal.findThread(id).map(ignored -> journal.snapshot(id));
    }

    @Override
    public Optional<AgentTurn> readTurn(TurnId id) {
        requireOpen();
        return journal.findTurn(Objects.requireNonNull(id, "id"));
    }

    @Override
    public Optional<AgentTurn> readTurnByIdempotencyKey(ThreadId threadId, String idempotencyKey) {
        requireOpen();
        return journal.findTurnByIdempotencyKey(Objects.requireNonNull(threadId, "threadId"), idempotencyKey);
    }

    @Override
    public Optional<StoredItem> readItem(ItemId id) {
        requireOpen();
        return journal.findItem(Objects.requireNonNull(id, "id"));
    }

    @Override
    public List<AgentThread> listThreads(boolean includeArchived) {
        requireOpen();
        return journal.listThreads(includeArchived);
    }

    @Override
    public AgentThread archiveThread(ThreadId id) {
        return changeStatus(id, ThreadStatus.ARCHIVED);
    }

    @Override
    public AgentThread archiveThread(ThreadId id, long expectedRevision, String idempotencyKey) {
        return changeStatus(id, ThreadStatus.ARCHIVED, expectedRevision, idempotencyKey);
    }

    @Override
    public AgentThread unarchiveThread(ThreadId id) {
        return changeStatus(id, ThreadStatus.ACTIVE);
    }

    @Override
    public AgentThread unarchiveThread(ThreadId id, long expectedRevision, String idempotencyKey) {
        return changeStatus(id, ThreadStatus.ACTIVE, expectedRevision, idempotencyKey);
    }

    @Override
    public void deleteThread(ThreadId id) {
        requireOpen();
        ActiveTurn active = activeByThread.get(id);
        if (active != null) {
            interrupt(active.turnId);
        }
        journal.deleteThread(id);
    }

    @Override
    public void deleteThread(ThreadId id, long expectedRevision, String idempotencyKey) {
        requireOpen();
        ActiveTurn active = activeByThread.get(id);
        if (active != null) {
            interrupt(active.turnId);
        }
        journal.deleteThread(id, expectedRevision, idempotencyKey);
    }

    @Override
    public AgentTurn startTurn(TurnStartCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        Object gate = threadGates.computeIfAbsent(command.threadId(), ignored -> new Object());
        synchronized (gate) {
            AgentThread thread = journal.findThread(command.threadId())
                    .orElseThrow(() -> new NoSuchElementException("thread not found: " + command.threadId()));
            if (thread.status() != ThreadStatus.ACTIVE) {
                throw new IllegalStateException("thread is not active: " + command.threadId());
            }
            ActiveTurn existing = activeByThread.get(command.threadId());
            if (existing != null) {
                if (command.idempotencyKey() != null && command.idempotencyKey().equals(existing.idempotencyKey)) {
                    return journal.findTurn(existing.turnId).orElseThrow();
                }
                throw new IllegalStateException("thread already has an active turn: " + command.threadId());
            }
            if (command.idempotencyKey() != null) {
                Optional<AgentTurn> duplicate =
                        journal.findTurnByIdempotencyKey(command.threadId(), command.idempotencyKey());
                if (duplicate.isPresent()) {
                    return duplicate.get();
                }
            }
            if (!thread.workingDirectory().equals(command.config().workingDirectory())) {
                throw new IllegalArgumentException("turn working directory must equal the thread working directory");
            }
            return schedule(command, thread);
        }
    }

    @Override
    public ThreadMaintenanceUseCases.Lease acquireInactiveDirectory(ThreadId threadId) {
        requireOpen();
        synchronized (quotaGate) {
            Path directory = requireThread(threadId).workingDirectory();
            if (maintenanceDirectories.stream().anyMatch(root -> overlaps(root, directory))) {
                throw new IllegalStateException("worktree already has an active maintenance operation");
            }
            // 终态投影可以先于物理子进程退出；以 Runtime 活动所有权为准，不能只查询 H2 的 completedAt。
            for (ThreadId active : activeByThread.keySet()) {
                if (overlaps(requireThread(active).workingDirectory(), directory)) {
                    throw new IllegalStateException("worktree still has an active Turn; interrupt and wait first");
                }
            }
            maintenanceDirectories.add(directory);
            AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) {
                    synchronized (quotaGate) {
                        maintenanceDirectories.remove(directory);
                    }
                }
            };
        }
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private AgentTurn schedule(TurnStartCommand command, AgentThread thread) {
        // 注册子任务与父任务退出共用门闩；数据库写入有界，模型和工具执行不持有此锁。
        synchronized (quotaGate) {
            if (maintenanceDirectories.stream().anyMatch(root -> overlaps(root, thread.workingDirectory()))) {
                throw new IllegalStateException("thread directory is undergoing maintenance");
            }
            ThreadId parentId = managedParent(thread);
            ActiveTurn parent = parentId == null ? null : activeByThread.get(parentId);
            if (parentId != null && (parent == null || !parent.acceptsChildren || parent.interrupted.get())) {
                throw new IllegalStateException("a subagent Turn requires an active parent Turn");
            }
            reserve(thread);
            WorkspaceWriteCoordinator.Lease writeLease;
            try {
                writeLease = writers.acquire(thread, command.config(), journal::findThread);
            } catch (Throwable failure) {
                release(thread.workspaceId(), parentId);
                throw failure;
            }
            AtomicBoolean interrupted = new AtomicBoolean();
            TurnScope scope;
            try {
                scope = parent == null
                        ? TurnScope.from(command.config(), interrupted)
                        : parent.scope.child(command.config(), interrupted);
            } catch (Throwable failure) {
                writeLease.close();
                release(thread.workspaceId(), parentId);
                throw failure;
            }
            long before = thread.lastSequence();
            AgentTurn queued;
            try {
                queued = journal.startTurn(command);
            } catch (Throwable failure) {
                scope.close();
                writeLease.close();
                release(thread.workspaceId(), parentId);
                throw failure;
            }
            if (queued.status().terminal()) {
                scope.close();
                writeLease.close();
                release(thread.workspaceId(), parentId);
                return queued;
            }
            if (queued.status() != TurnStatus.QUEUED) {
                scope.close();
                writeLease.close();
                release(thread.workspaceId(), parentId);
                throw new IllegalStateException("cannot attach to orphaned active turn: " + queued.id());
            }
            ActiveTurn active = new ActiveTurn(
                    command.threadId(),
                    queued.id(),
                    thread.workspaceId(),
                    parentId,
                    command.idempotencyKey(),
                    writeLease,
                    parent,
                    interrupted,
                    scope);
            activeByThread.put(command.threadId(), active);
            activeByTurn.put(queued.id(), active);
            try {
                publishAfter(command.threadId(), before);
                active.task = turns.submit(() -> execute(active, queued));
            } catch (Throwable failure) {
                remove(active);
                long failureSequence = requireThread(command.threadId()).lastSequence();
                AgentTurn failed = journal.transitionTurn(
                        queued.id(), TurnStatus.QUEUED, TurnStatus.FAILED, "turn scheduler rejected execution");
                publishAfter(command.threadId(), failureSequence);
                active.completion.complete(failed);
                throw failure;
            }
            return queued;
        }
    }

    @Override
    public boolean steer(TurnId turnId, TurnInput input) {
        requireOpen();
        ActiveTurn active = activeByTurn.get(turnId);
        if (active == null || active.interrupted.get()) {
            return false;
        }
        Objects.requireNonNull(input, "input");
        long before = requireThread(active.threadId).lastSequence();
        ThreadItem item = input instanceof TurnInput.Text text
                ? new ThreadItem.UserMessage(text.text())
                : new ThreadItem.UserMessage(describe(input), List.of((TurnInput.AttachmentRef) input));
        journal.appendItem(active.threadId, turnId, item, ItemState.COMPLETED);
        active.steering.add(input);
        publishAfter(active.threadId, before);
        return true;
    }

    @Override
    public boolean interrupt(TurnId turnId) {
        ActiveTurn active = activeByTurn.get(turnId);
        if (active == null) {
            return false;
        }
        boolean interrupted = interruptActive(active);
        if (interrupted) {
            activeByThread.values().stream()
                    .filter(candidate -> isDescendantOf(candidate, active))
                    .toList()
                    .forEach(this::interruptActive);
        }
        return interrupted;
    }

    private boolean interruptActive(ActiveTurn active) {
        if (!active.interrupted.compareAndSet(false, true)) {
            return false;
        }
        // 尚未开始的 Future 被取消后不会进入 finally；正在执行的任务则必须退出后才能释放写租约。
        boolean cancelledBeforeStart = active.dispatchState.compareAndSet(0, 2);
        Future<?> task = active.task;
        if (task != null) {
            task.cancel(true);
        }
        AgentTurn current = journal.findTurn(active.turnId).orElse(null);
        if (current != null && !current.status().terminal()) {
            long before = requireThread(active.threadId).lastSequence();
            AgentTurn terminal = transitionToInterrupted(current);
            publishAfter(active.threadId, before);
            active.completion.complete(terminal);
        }
        if (cancelledBeforeStart) {
            remove(active);
        }
        return true;
    }

    @Override
    public List<ThreadEvent> eventsAfter(ThreadId threadId, long afterSequence, int limit) {
        requireOpen();
        return journal.eventsAfter(threadId, afterSequence, limit);
    }

    @Override
    public boolean respondToApproval(String approvalId, boolean approved) {
        requireOpen();
        return resolveApproval(approvalId, approved);
    }

    private boolean resolveApproval(String approvalId, boolean approved) {
        Optional<ApprovalResolution> result = interactions.resolveApproval(approvalId, approved);
        if (result.isEmpty()) {
            return false;
        }
        ApprovalResolution resolution = result.get();
        long after = Math.max(0, requireThread(resolution.threadId()).lastSequence() - 1);
        publishAfter(resolution.threadId(), after);
        AgentTurn turn = journal.findTurn(resolution.turnId()).orElse(null);
        if (turn != null && turn.status() == TurnStatus.WAITING_FOR_APPROVAL) {
            long before = requireThread(resolution.threadId()).lastSequence();
            journal.transitionTurn(turn.id(), TurnStatus.WAITING_FOR_APPROVAL, TurnStatus.IN_PROGRESS, null);
            publishAfter(resolution.threadId(), before);
        }
        return true;
    }

    @Override
    public boolean respondToUserInput(String requestId, String value, boolean cancelled) {
        requireOpen();
        Optional<UserInputResolution> result = interactions.resolveUserInput(requestId, value, cancelled);
        if (result.isEmpty()) {
            return false;
        }
        UserInputResolution resolution = result.get();
        long after = Math.max(0, requireThread(resolution.threadId()).lastSequence() - 1);
        publishAfter(resolution.threadId(), after);
        AgentTurn turn = journal.findTurn(resolution.turnId()).orElse(null);
        if (turn != null && turn.status() == TurnStatus.WAITING_FOR_INPUT) {
            long before = requireThread(resolution.threadId()).lastSequence();
            journal.transitionTurn(turn.id(), TurnStatus.WAITING_FOR_INPUT, TurnStatus.IN_PROGRESS, null);
            publishAfter(resolution.threadId(), before);
        }
        return true;
    }

    private void execute(ActiveTurn active, AgentTurn queued) {
        if (!active.dispatchState.compareAndSet(0, 1)) {
            return;
        }
        try {
            if (active.interrupted.get()) {
                return;
            }
            long before = requireThread(active.threadId).lastSequence();
            AgentTurn running = journal.transitionTurn(queued.id(), TurnStatus.QUEUED, TurnStatus.IN_PROGRESS, null);
            publishAfter(active.threadId, before);
            ThreadSnapshot snapshot = journal.snapshot(active.threadId);
            List<StoredItem> prior = priorItems(snapshot.items(), running.id());
            kernel.execute(
                    new TurnExecutionContext(
                            snapshot.thread(),
                            running,
                            prior,
                            active.interrupted,
                            () -> drain(active.steering),
                            active.scope,
                            journal.activeConversationWindow(active.threadId).orElse(null)),
                    new ItemSink() {
                        @Override
                        public StoredItem append(ThreadItem item) {
                            return DefaultAgentRuntime.this.append(active, running.id(), item);
                        }

                        @Override
                        public ItemEmitter start(String kind) {
                            return DefaultAgentRuntime.this.startItem(active, running.id(), kind);
                        }

                        @Override
                        public void usage(ModelUsage delta) {
                            DefaultAgentRuntime.this.recordUsage(active, running.id(), delta);
                        }

                        @Override
                        public void providerConversationState(
                                String model, com.javaclaw.core.api.ProviderConversationState state, ModelUsage usage) {
                            journal.saveProviderConversationState(
                                    active.threadId,
                                    model,
                                    requireThread(active.threadId).lastSequence(),
                                    state,
                                    usage);
                        }

                        @Override
                        public void promptSnapshot(com.javaclaw.agent.prompt.PromptSnapshot snapshot, int ordinal) {
                            journal.recordPromptSnapshot(active.threadId, running.id(), snapshot, ordinal);
                        }

                        @Override
                        public void budget(int calls, long tokens) {
                            String execution = running.config().attributes().get("automationExecutionId");
                            if (execution != null) {
                                append(new ThreadItem.DynamicToolCall(
                                        "execution_budget",
                                        Map.of(
                                                "executionId",
                                                execution,
                                                "modelCalls",
                                                Integer.toString(calls),
                                                "tokens",
                                                Long.toString(tokens))));
                            }
                        }

                        @Override
                        public void approvalResolved(String approvalId, boolean approved) {
                            DefaultAgentRuntime.this.resolveApproval(approvalId, approved);
                        }
                    });
            synchronized (quotaGate) {
                active.acceptsChildren = false;
            }
            active.scope.check();
            if (active.interrupted.get()) {
                return;
            }
            remove(active);
            before = requireThread(active.threadId).lastSequence();
            AgentTurn completed =
                    journal.transitionTurn(running.id(), TurnStatus.IN_PROGRESS, TurnStatus.COMPLETED, null);
            publishAfter(active.threadId, before);
            active.completion.complete(completed);
        } catch (com.javaclaw.agent.automation.ExecutionPausedException paused) {
            completeInterrupted(active);
        } catch (InterruptedException interrupted) {
            java.lang.Thread.currentThread().interrupt();
            completeInterrupted(active);
        } catch (Throwable failure) {
            if (active.interrupted.get()) {
                completeInterrupted(active);
            } else {
                completeFailed(active, failure);
            }
        } finally {
            remove(active);
        }
    }

    private StoredItem append(ActiveTurn active, TurnId turnId, ThreadItem item) {
        long before = requireThread(active.threadId).lastSequence();
        StoredItem stored = journal.appendItem(active.threadId, turnId, item, ItemState.COMPLETED);
        publishAfter(active.threadId, before);
        AgentTurn current = journal.findTurn(turnId).orElseThrow();
        if (item instanceof ThreadItem.ApprovalRequest && current.status() == TurnStatus.IN_PROGRESS) {
            before = requireThread(active.threadId).lastSequence();
            journal.transitionTurn(turnId, TurnStatus.IN_PROGRESS, TurnStatus.WAITING_FOR_APPROVAL, null);
            publishAfter(active.threadId, before);
        } else if (item instanceof ThreadItem.UserInputRequest && current.status() == TurnStatus.IN_PROGRESS) {
            before = requireThread(active.threadId).lastSequence();
            journal.transitionTurn(turnId, TurnStatus.IN_PROGRESS, TurnStatus.WAITING_FOR_INPUT, null);
            publishAfter(active.threadId, before);
        } else if (item instanceof ThreadItem.UserInputResponse && current.status() == TurnStatus.WAITING_FOR_INPUT) {
            before = requireThread(active.threadId).lastSequence();
            journal.transitionTurn(turnId, TurnStatus.WAITING_FOR_INPUT, TurnStatus.IN_PROGRESS, null);
            publishAfter(active.threadId, before);
        } else if (!(item instanceof ThreadItem.ApprovalRequest)
                && current.status() == TurnStatus.WAITING_FOR_APPROVAL) {
            before = requireThread(active.threadId).lastSequence();
            journal.transitionTurn(turnId, TurnStatus.WAITING_FOR_APPROVAL, TurnStatus.IN_PROGRESS, null);
            publishAfter(active.threadId, before);
        }
        return stored;
    }

    private ItemSink.ItemEmitter startItem(ActiveTurn active, TurnId turnId, String kind) {
        long before = requireThread(active.threadId).lastSequence();
        StoredItem started = journal.startItem(active.threadId, turnId, kind);
        publishAfter(active.threadId, before);
        RuntimeItemEmitter emitter = new RuntimeItemEmitter(active, started);
        active.liveItems.put(started.id(), emitter);
        liveItems.publish(new LiveItemEvent(
                active.threadId, turnId, started.id(), kind, 0, null, LiveItemEvent.Phase.STARTED, Instant.now()));
        return emitter;
    }

    private void recordUsage(ActiveTurn active, TurnId turnId, ModelUsage delta) {
        long before = requireThread(active.threadId).lastSequence();
        journal.recordUsage(active.threadId, turnId, delta);
        publishAfter(active.threadId, before);
    }

    private void completeInterrupted(ActiveTurn active) {
        active.liveItems.values().forEach(emitter -> emitter.failIfOpen("interrupted", "turn was interrupted", true));
        AgentTurn current = journal.findTurn(active.turnId).orElse(null);
        if (current == null || current.status().terminal()) {
            if (current != null) {
                active.completion.complete(current);
            }
            return;
        }
        long before = requireThread(active.threadId).lastSequence();
        AgentTurn terminal = transitionToInterrupted(current);
        publishAfter(active.threadId, before);
        active.completion.complete(terminal);
    }

    private void completeFailed(ActiveTurn active, Throwable failure) {
        AgentTurn current = journal.findTurn(active.turnId).orElse(null);
        if (current == null || current.status().terminal()) {
            return;
        }
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        active.liveItems.values().forEach(emitter -> emitter.failIfOpen("turn_failed", message, false));
        append(active, active.turnId, new ThreadItem.ErrorItem("turn_failed", message, false));
        current = journal.findTurn(active.turnId).orElse(null);
        if (current == null || current.status().terminal()) {
            return;
        }
        long before = requireThread(active.threadId).lastSequence();
        AgentTurn terminal = journal.transitionTurn(current.id(), current.status(), TurnStatus.FAILED, message);
        publishAfter(active.threadId, before);
        active.completion.complete(terminal);
    }

    private AgentTurn transitionToInterrupted(AgentTurn current) {
        for (int retry = 0; retry < 4; retry++) {
            if (current.status().terminal()) {
                return current;
            }
            try {
                return journal.transitionTurn(current.id(), current.status(), TurnStatus.INTERRUPTED, "interrupted");
            } catch (IllegalStateException raced) {
                current = journal.findTurn(current.id()).orElseThrow(() -> raced);
            }
        }
        throw new IllegalStateException("could not settle interrupted turn: " + current.id());
    }

    private AgentThread changeStatus(ThreadId id, ThreadStatus status) {
        return changeStatus(id, status, -1, null, false);
    }

    private AgentThread changeStatus(ThreadId id, ThreadStatus status, long expectedRevision, String idempotencyKey) {
        return changeStatus(id, status, expectedRevision, idempotencyKey, true);
    }

    private AgentThread changeStatus(
            ThreadId id, ThreadStatus status, long expectedRevision, String idempotencyKey, boolean guarded) {
        requireOpen();
        AgentThread before = requireThread(id);
        if (activeByThread.containsKey(id)) {
            throw new IllegalStateException("cannot change status with an active turn: " + id);
        }
        AgentThread changed = guarded
                ? journal.setThreadStatus(id, status, expectedRevision, idempotencyKey)
                : journal.setThreadStatus(id, status);
        publishAfter(id, before.lastSequence());
        return changed;
    }

    private AgentThread requireThread(ThreadId id) {
        return journal.findThread(id).orElseThrow(() -> new NoSuchElementException("thread not found: " + id));
    }

    private void publishAfter(ThreadId id, long sequence) {
        journal.eventsAfter(id, sequence, 10_000).forEach(this::publish);
    }

    private void drainOutbox() {
        List<ThreadEvent> pending;
        do {
            pending = outbox.unpublishedEvents(1_000);
            pending.forEach(this::publish);
        } while (pending.size() == 1_000);
    }

    private void publish(ThreadEvent event) {
        events.publish(event);
        outbox.markEventPublished(event.threadId(), event.sequence());
    }

    private static List<StoredItem> priorItems(List<StoredItem> items, TurnId current) {
        List<StoredItem> result = new ArrayList<>();
        for (StoredItem item : items) {
            if (!item.turnId().equals(current)) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    private static List<TurnInput> drain(Queue<TurnInput> queue) {
        List<TurnInput> values = new ArrayList<>();
        TurnInput value;
        while ((value = queue.poll()) != null) {
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String describe(TurnInput input) {
        if (input instanceof TurnInput.AttachmentRef attachment) {
            return "[attachment] " + attachment.displayName() + " sha256:" + attachment.sha256();
        }
        return input.toString();
    }

    private void remove(ActiveTurn active) {
        List<ActiveTurn> descendants;
        synchronized (quotaGate) {
            active.acceptsChildren = false;
            descendants = activeByTurn.values().stream()
                    .filter(candidate -> isDescendantOf(candidate, active))
                    .toList();
            activeByThread.remove(active.threadId, active);
            activeByTurn.remove(active.turnId, active);
            if (active.released.compareAndSet(false, true)) {
                active.scope.close();
                active.writeLease.close();
                release(active.workspaceId, active.parentThreadId);
            }
        }
        // 父 Turn 正常返回也不会遗留孤儿子任务；等待应通过协作工具显式完成，不能靠脱离预算继续运行。
        descendants.forEach(this::interruptActive);
    }

    private void reserve(AgentThread thread) {
        synchronized (quotaGate) {
            int workspaceCount = activeByWorkspace.getOrDefault(thread.workspaceId(), 0);
            if (workspaceCount >= limits.maxActiveTurnsPerWorkspace()) {
                throw new IllegalStateException("workspace active turn quota exceeded: " + thread.workspaceId());
            }
            ThreadId parent = managedParent(thread);
            if (parent != null) {
                int childCount = activeSubagentsByParent.getOrDefault(parent, 0);
                if (childCount >= limits.maxSubagentsPerParent()) {
                    throw new IllegalStateException("subagent quota exceeded for parent thread: " + parent);
                }
                activeSubagentsByParent.put(parent, childCount + 1);
            }
            activeByWorkspace.put(thread.workspaceId(), workspaceCount + 1);
        }
    }

    private void release(String workspaceId, ThreadId parentThreadId) {
        synchronized (quotaGate) {
            decrement(activeByWorkspace, workspaceId);
            if (parentThreadId != null) {
                decrement(activeSubagentsByParent, parentThreadId);
            }
        }
    }

    private static boolean isDescendantOf(ActiveTurn candidate, ActiveTurn ancestor) {
        for (ActiveTurn parent = candidate.parent; parent != null; parent = parent.parent) {
            if (parent == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static ThreadId managedParent(AgentThread thread) {
        // forkedFromTurnId 表示用户对话分支，不是拥有父预算与取消生命周期的子智能体。
        return thread.forkedFromTurnId() == null ? thread.parentThreadId() : null;
    }

    private static <K> void decrement(Map<K, Integer> counts, K key) {
        int value = counts.getOrDefault(key, 0);
        if (value <= 1) {
            counts.remove(key);
        } else {
            counts.put(key, value - 1);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("thread service is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(activeByTurn.keySet()).forEach(this::interrupt);
        turns.close();
        events.close();
        liveItems.close();
    }

    private static final class ActiveTurn {
        private final ThreadId threadId;
        private final TurnId turnId;
        private final String workspaceId;
        private final ThreadId parentThreadId;
        private final String idempotencyKey;
        private final WorkspaceWriteCoordinator.Lease writeLease;
        private final ActiveTurn parent;
        private final TurnScope scope;
        private final AtomicBoolean interrupted;
        private final AtomicInteger dispatchState = new AtomicInteger();
        private boolean acceptsChildren = true;
        private final AtomicBoolean released = new AtomicBoolean();
        private final Queue<TurnInput> steering = new ConcurrentLinkedQueue<>();
        private final Map<ItemId, RuntimeItemEmitter> liveItems = new ConcurrentHashMap<>();
        private final CompletableFuture<AgentTurn> completion = new CompletableFuture<>();
        private volatile Future<?> task;

        private ActiveTurn(
                ThreadId threadId,
                TurnId turnId,
                String workspaceId,
                ThreadId parentThreadId,
                String idempotencyKey,
                WorkspaceWriteCoordinator.Lease writeLease,
                ActiveTurn parent,
                AtomicBoolean interrupted,
                TurnScope scope) {
            this.threadId = threadId;
            this.turnId = turnId;
            this.workspaceId = workspaceId;
            this.parentThreadId = parentThreadId;
            this.idempotencyKey = idempotencyKey;
            this.writeLease = writeLease;
            this.parent = parent;
            this.interrupted = interrupted;
            this.scope = scope;
        }
    }

    private final class RuntimeItemEmitter implements ItemSink.ItemEmitter {
        private final ActiveTurn active;
        private final StoredItem started;
        private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private RuntimeItemEmitter(ActiveTurn active, StoredItem started) {
            this.active = active;
            this.started = started;
        }

        @Override
        public ItemId id() {
            return started.id();
        }

        @Override
        public void delta(ItemDelta value) {
            Objects.requireNonNull(value, "value");
            if (terminal.get()) {
                throw new IllegalStateException("item is already terminal");
            }
            long next = sequence.incrementAndGet();
            liveItems.publish(new LiveItemEvent(
                    started.threadId(),
                    started.turnId(),
                    started.id(),
                    started.kind(),
                    next,
                    value,
                    LiveItemEvent.Phase.DELTA,
                    Instant.now()));
        }

        @Override
        public StoredItem complete(ThreadItem value) {
            Objects.requireNonNull(value, "value");
            if (!terminal.compareAndSet(false, true)) {
                throw new IllegalStateException("item is already terminal");
            }
            try {
                long before = requireThread(started.threadId()).lastSequence();
                StoredItem completed = journal.completeItem(started.id(), value);
                publishAfter(started.threadId(), before);
                liveItems.publish(new LiveItemEvent(
                        started.threadId(),
                        started.turnId(),
                        started.id(),
                        started.kind(),
                        sequence.get(),
                        null,
                        LiveItemEvent.Phase.COMPLETED,
                        Instant.now()));
                return completed;
            } finally {
                active.liveItems.remove(started.id(), this);
            }
        }

        @Override
        public StoredItem completeCompaction(
                ThreadItem.ContextCompaction value,
                com.javaclaw.agent.conversation.ConversationWindow.Replacement replacement) {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(replacement, "replacement");
            if (!terminal.compareAndSet(false, true)) {
                throw new IllegalStateException("item is already terminal");
            }
            try {
                long before = requireThread(started.threadId()).lastSequence();
                StoredItem completed = journal.completeCompaction(started.id(), value, replacement);
                publishAfter(started.threadId(), before);
                liveItems.publish(new LiveItemEvent(
                        started.threadId(),
                        started.turnId(),
                        started.id(),
                        started.kind(),
                        sequence.get(),
                        null,
                        LiveItemEvent.Phase.COMPLETED,
                        Instant.now()));
                return completed;
            } finally {
                active.liveItems.remove(started.id(), this);
            }
        }

        @Override
        public StoredItem fail(String code, String message, boolean retryable) {
            if (!terminal.compareAndSet(false, true)) {
                throw new IllegalStateException("item is already terminal");
            }
            try {
                long before = requireThread(started.threadId()).lastSequence();
                StoredItem failed = journal.failItem(started.id(), code, message, retryable);
                publishAfter(started.threadId(), before);
                liveItems.publish(new LiveItemEvent(
                        started.threadId(),
                        started.turnId(),
                        started.id(),
                        started.kind(),
                        sequence.get(),
                        null,
                        LiveItemEvent.Phase.FAILED,
                        Instant.now()));
                return failed;
            } finally {
                active.liveItems.remove(started.id(), this);
            }
        }

        private void failIfOpen(String code, String message, boolean retryable) {
            if (!terminal.get()) {
                try {
                    fail(code, message, retryable);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }
}
