package com.javaclaw.framework.core;

import com.javaclaw.framework.spi.TaskAcceptanceContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ExecutionPlanStore;
import com.javaclaw.framework.spi.CreateRunResult;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import com.javaclaw.framework.spi.ModelTaskGateway;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * The single Agent run state machine. Product features submit RunRequest through AgentClient;
 * they never own model loops, subscriptions, checkpoints or cancellation state.
 */
public final class AgentEngine implements AgentClient, AutoCloseable {
    java.util.Optional<RunEventEnvelope> appendTransition(RunId id, Set<RunState> expected,
            RunState next, RunEventDraft event, JsonNode output, String error) {
        return runs.append(id, expected, next, event, output, error);
    }

    java.util.Optional<List<RunEventEnvelope>> appendTransitionBatch(RunId id, Set<RunState> expected,
            RunState next, List<RunEventDraft> events) {
        return runs.appendBatch(id, expected, next, events);
    }

    RunHandle activeHandle(ActiveEngineRun active) { return new EngineRunHandle(this, active); }

    private static final Set<RunState> CANCELLABLE = Set.of(
            RunState.CREATED, RunState.RUNNING, RunState.WAITING_INPUT,
            RunState.WAITING_APPROVAL, RunState.WAITING_CHILD, RunState.WAITING_EVENT, RunState.PAUSED,
            RunState.RECOVERY_BLOCKED_MISSING_EXTENSION);

    private final AgentCompiler compiler;
    final RunStore runs;
    private final ExecutionPlanStore plans;
    private final ReasoningGateway reasoning;
    final Executor executor;
    final ObjectMapper json;
    final Clock clock;
    final RunUsageLedger usage;
    final TaskHarnessLifecycle taskHarness;
    private final RunRecoveryCoordinator recovery;
    final com.javaclaw.framework.spi.InteractionEventSource interactionEvents;
    final BiConsumer<com.javaclaw.framework.spi.ToolContext, RunHandle> interactionObserver;
    final EngineWaitScheduler waitTimers;
    final ConcurrentHashMap<RunId, ActiveEngineRun> activeRuns = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final EngineResumeCoordinator resumption = new EngineResumeCoordinator(this);
    private final EngineInteractionWaits interactionWaits = new EngineInteractionWaits(this);
    private final EngineRunEvents runEvents = new EngineRunEvents(this);

    public AgentEngine(
            AgentCompiler compiler,
            RunStore runs,
            ExecutionPlanStore plans,
            ReasoningGateway reasoning,
            Executor executor,
            ObjectMapper json,
            Clock clock,
            RunUsageLedger usage) {
        this(compiler, runs, plans, reasoning, executor, json, clock, usage, null);
    }

    public AgentEngine(
            AgentCompiler compiler,
            RunStore runs,
            ExecutionPlanStore plans,
            ReasoningGateway reasoning,
            Executor executor,
            ObjectMapper json,
            Clock clock,
            RunUsageLedger usage,
            ModelTaskGateway contractPlanningGateway) {
        this(compiler, runs, plans, reasoning, executor, json, clock, usage,
                contractPlanningGateway, (request, evidence) -> { });
    }

    public AgentEngine(
            AgentCompiler compiler,
            RunStore runs,
            ExecutionPlanStore plans,
            ReasoningGateway reasoning,
            Executor executor,
            ObjectMapper json,
            Clock clock,
            RunUsageLedger usage,
            ModelTaskGateway contractPlanningGateway,
            BiConsumer<RunRequest, TaskResultEvaluator.VerifiedActionEvidence> verifiedDesktopEffect) {
        this(compiler, runs, plans, reasoning, executor, json, clock, usage, contractPlanningGateway,
                verifiedDesktopEffect, (request, wait, cancellation) -> CompletableFuture.failedFuture(
                        new IllegalStateException("native event source is unavailable")));
    }

    public AgentEngine(AgentCompiler compiler, RunStore runs, ExecutionPlanStore plans,
            ReasoningGateway reasoning, Executor executor, ObjectMapper json, Clock clock,
            RunUsageLedger usage, ModelTaskGateway contractPlanningGateway,
            BiConsumer<RunRequest, TaskResultEvaluator.VerifiedActionEvidence> verifiedDesktopEffect,
            com.javaclaw.framework.spi.InteractionEventSource interactionEvents) {
        this(compiler, runs, plans, reasoning, executor, json, clock, usage, contractPlanningGateway,
                verifiedDesktopEffect, interactionEvents, (parent, child) -> { });
    }

    public AgentEngine(AgentCompiler compiler, RunStore runs, ExecutionPlanStore plans,
            ReasoningGateway reasoning, Executor executor, ObjectMapper json, Clock clock,
            RunUsageLedger usage, ModelTaskGateway contractPlanningGateway,
            BiConsumer<RunRequest, TaskResultEvaluator.VerifiedActionEvidence> verifiedDesktopEffect,
            com.javaclaw.framework.spi.InteractionEventSource interactionEvents,
            BiConsumer<com.javaclaw.framework.spi.ToolContext, RunHandle> interactionObserver) {
        this(compiler, runs, plans, reasoning, executor, json, clock, usage, contractPlanningGateway,
                verifiedDesktopEffect, interactionEvents, interactionObserver, null);
    }

    public AgentEngine(AgentCompiler compiler, RunStore runs, ExecutionPlanStore plans,
            ReasoningGateway reasoning, Executor executor, ObjectMapper json, Clock clock,
            RunUsageLedger usage, ModelTaskGateway contractPlanningGateway,
            BiConsumer<RunRequest, TaskResultEvaluator.VerifiedActionEvidence> verifiedDesktopEffect,
            com.javaclaw.framework.spi.InteractionEventSource interactionEvents,
            BiConsumer<com.javaclaw.framework.spi.ToolContext, RunHandle> interactionObserver,
            com.javaclaw.platform.execution.ManagedTaskExecutor managedTasks) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.reasoning = Objects.requireNonNull(reasoning, "reasoning");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.interactionEvents = Objects.requireNonNull(interactionEvents, "interactionEvents");
        this.interactionObserver = Objects.requireNonNull(interactionObserver, "interactionObserver");
        this.taskHarness = new TaskHarnessLifecycle(runs, json,
                contractPlanningGateway == null ? null
                        : new TaskContractCompiler(contractPlanningGateway, json), verifiedDesktopEffect);
        this.recovery = new RunRecoveryCoordinator(compiler, runs, plans, json, clock, usage);
        this.waitTimers = new EngineWaitScheduler(managedTasks);
        try {
            runs.recoverClaims();
            recovery.recover(this::cancel, this::attachRecovered, activeRuns::remove);
        } catch (RuntimeException | Error failure) {
            waitTimers.close();
            throw failure;
        }
    }

    @Override
    public RunHandle start(RunRequest request) {
        ensureOpen();
        if (request.idempotencyKey() != null) {
            var existing = runs.findByIdempotencyKey(
                    request.scope(), request.idempotencyKey());
            if (existing.isPresent()) return handle(existing.get().snapshot().id());
        }
        JsonNode maintenanceOrigin = MaintenanceRunProvenance.consume(request);
        request = MaintenanceRunProvenance.withoutPermit(request);
        InteractionRequestGuard.validate(runs, json, request);

        new RunUsageRecovery(runs, plans, json, usage).restore(request.linkage().parentRunId());
        request = runs.prepare(request);
        ExecutionPlan plan = compiler.compile(request);
        RunRequest effectiveRequest = request;
        RunId openedAccount = null;
        boolean durableCreated = false;
        boolean planTransferred = false;
        try {
            effectiveRequest = plan.annotate(request);
            plans.save(plan.descriptor().id(), plan.descriptor().extensionGeneration(),
                    json.valueToTree(plan.descriptor()), plan.descriptor().checksum());
            RunId id = RunId.random();
            ActiveEngineRun active = new ActiveEngineRun(id, effectiveRequest, plan,
                    new RunControl(plan.descriptor().budget(), clock));
            recovery.inheritEffects(active.id, active.request, active.control);
            usage.open(id, plan.descriptor().budget(), effectiveRequest.scope(), RunUsageRecovery.budgetParent(effectiveRequest));
            openedAccount = id;
            ActiveEngineRun collision = activeRuns.putIfAbsent(id, active);
            if (collision != null) throw new IllegalStateException("run id collision: " + id);

            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("executionPlanId", plan.descriptor().id());
            payload.put("source", request.source().kind());
            if (maintenanceOrigin != null) payload.set(MaintenanceRunProvenance.CREATED_FIELD, maintenanceOrigin);
            payload.put("deadline", active.control.deadline().toString());
            if (taskHarness.active()) payload.put("taskHarnessV3", true);
            CreateRunResult created = runs.create(id, effectiveRequest, plan.descriptor().id(),
                    event(effectiveRequest, "core.run.created", "framework.core", payload, null));
            if (!created.created()) {
                activeRuns.remove(id, active);
                usage.close(id);
                return handle(created.run().snapshot().id());
            }
            durableCreated = true;
            replayPersisted(active);
            if (runs.claim(id)) launch(active, null, null, Set.of(RunState.CREATED));
            RunHandle handle = new EngineRunHandle(this, active);
            planTransferred = true;
            return handle;
        } catch (RuntimeException failure) {
            if (openedAccount != null) {
                if (durableCreated) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    describeFailure(payload, failure);
                    ActiveEngineRun current = activeRuns.get(openedAccount);
                    TaskResult taskResult = taskHarness.persist(openedAccount, effectiveRequest,
                            Set.of(RunState.CREATED), "RUN_FAILED: " + failure,
                            current == null ? null : current.sink::tryEmitNext);
                    if (taskResult != null) payload.set("taskResult", json.valueToTree(taskResult));
                    runs.append(openedAccount, Set.of(RunState.CREATED), RunState.FAILED,
                            event(effectiveRequest, "core.run.failed", "framework.core", payload, null),
                            null, failure.toString());
                }
                activeRuns.remove(openedAccount);
                usage.close(openedAccount);
                if (durableCreated) releaseAndLaunch(openedAccount);
            }
            throw failure;
        } finally {
            // 初始化成功后由运行持有计划；其他退出路径都归还编译时取得的租约。
            if (!planTransferred) plan.close();
        }
    }

    @Override public boolean supportsManagedTurns() { return true; }
    @Override public java.util.Optional<RunSnapshot> activeTurn(RunScope scope) {
        if (!runs.readable(scope)) return java.util.Optional.empty();
        return runs.nonTerminalRuns().stream().filter(run -> run.request().scope().equals(scope))
                .map(StoredRun::snapshot).filter(run -> run.state() != RunState.CREATED).findFirst();
    }

    @Override public ManagedTurn beginTurn(RunRequest request) {
        RunHandle handle = start(request.withAttribute("framework.managed", JsonNodeFactory.instance.booleanNode(true)));
        ActiveEngineRun active = activeRuns.get(handle.id());
        if (active != null) {
            if (!active.request.attributes().getOrDefault("framework.managed", JsonNodeFactory.instance.booleanNode(false)).asBoolean())
                throw new IllegalStateException("existing turn is not managed");
            if (!active.managedAttached.compareAndSet(false, true)) throw new IllegalStateException("managed turn already has a driver");
            try {
                RunState state = get(handle.id()).state();
                if (state == RunState.PAUSED || state == RunState.WAITING_INPUT)
                    resume(handle.id(), new ResumeCommand("managed.continue", JsonNodeFactory.instance.objectNode()));
                else if (state == RunState.CREATED && !active.executing.get() && runs.claim(active.id))
                    launch(active, null, null, Set.of(RunState.CREATED));
            } catch (RuntimeException failure) {
                active.managedAttached.set(false);
                throw failure;
            }
        }
        return new ManagedTurnAdapter(handle,
                active == null ? CompletableFuture.completedFuture(null) : active.ready,
                (type, payload) -> { if (active != null) runEvents.appendReasoningEvent(active, type, 1, "framework.orchestration", payload); },
                result -> { if (active != null) finishTurn(active, result); },
                failure -> { if (active != null) fail(active, failure); },
                () -> active != null ? active.control.cancelled() : get(handle.id()).state() == RunState.CANCELLED,
                () -> { if (active != null) finishManagedExecution(active); });
    }

    private void finishManagedExecution(ActiveEngineRun active) {
        active.managedAttached.set(false);
        if (!active.detached.get() && !active.terminated.get()) {
            RunState state = requireRun(active.id).snapshot().state();
            if (state == RunState.RUNNING) finishTurn(active, new ReasoningResult(RunState.PAUSED, null, "MANAGED_DRIVER_EXITED"));
            else if (state == RunState.CREATED) cancel(active.id, new CancelReason("MANAGED_DRIVER_EXITED", "queued driver closed"));
        }
        finishExecutionTurn(active);
    }

    @Override
    public RunHandle resume(RunId runId, ResumeCommand command) {
        ensureOpen();
        requireReadableRun(runId);
        ActiveEngineRun active = activeRuns.get(runId);
        if (active == null) {
            StoredRun stored = requireRun(runId);
            if (stored.snapshot().state().terminal()) return handle(runId);
            throw new IllegalStateException(
                    "run is not attached to this process; recovery must restore its locked execution plan first");
        }
        synchronized (active.launchLock) {
            return resumption.resumeAttached(active, runId, command);
        }
    }

    @Override
    public boolean cancel(RunId runId, CancelReason reason) {
        ActiveEngineRun active = activeRuns.get(runId);
        // Cancelling a provider stage can synchronously call fail() back into this method.
        // The owner that requested cancellation must publish its reason exactly once.
        if (active != null && !active.cancelling.compareAndSet(false, true)) return false;
        try {
            return cancelRun(runId, reason, active);
        } finally {
            if (active != null) active.cancelling.set(false);
        }
    }

    @Override public RunHandle controlInteraction(RunId parentId, InteractionControlCommand command) {
        return resumption.controlInteraction(parentId, command);
    }

    private boolean cancelRun(RunId runId, CancelReason reason, ActiveEngineRun active) {
        if (active != null) {
            active.control.cancel(reason);
            reason = active.control.cancellationReason().orElse(reason);
        }
        StoredRun stored = runs.find(runId).orElse(null);
        if (stored == null || stored.snapshot().state().terminal()) return false;
        List<RunId> children = attachedChildren(runId);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", reason.code());
        payload.put("detail", reason.detail());
        payload.put("userInitiated", "USER_REQUEST".equals(reason.code()));
        if ("RUN_TIMEOUT".equals(reason.code())) deadline(runId)
                .ifPresent(value -> payload.put("deadline", value.toString()));
        TaskResult taskResult = taskHarness.persist(runId, stored.request(), CANCELLABLE,
                "RUN_CANCELLED: " + reason.code(), active == null ? null : active.sink::tryEmitNext);
        if (taskResult != null) payload.set("taskResult", json.valueToTree(taskResult));
        var cancellation = event(stored.request(), "core.run.cancelled", "framework.core", payload, null);
        var control = "USER_REQUEST".equals(reason.code()) ? runs.eventsAfter(runId, 0).stream()
                .filter(value -> "core.interaction.control_accepted".equals(value.type())
                        && "framework.core".equals(value.producer())
                        && "CANCEL".equals(value.payload().path("type").asText()))
                .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null) : null;
        java.util.Optional<RunEventEnvelope> event;
        if (control == null) {
            event = runs.append(runId, CANCELLABLE, RunState.CANCELLED, cancellation, null, reason.detail());
        } else {
            // terminal Run 禁止追加事件；applied 与取消必须同一事务落盘。
            var applied = event(stored.request(), "core.interaction.control_applied", "framework.core", control.payload(), null);
            var batch = runs.appendBatch(runId, CANCELLABLE, RunState.CANCELLED, List.of(applied, cancellation));
            if (active != null) batch.ifPresent(values -> active.sink.tryEmitNext(values.getFirst()));
            event = batch.map(List::getLast);
        }
        CancelReason persistedReason = reason;
        event.ifPresent(value -> {
            if (active != null) {
                active.sink.tryEmitNext(value);
                terminate(active, new RunOutcome(runId, RunState.CANCELLED, null, persistedReason.detail()), children);
            } else {
                cancelChildren(children, persistedReason);
                usage.close(runId);
                releaseAndLaunch(runId);
            }
        });
        return event.isPresent();
    }

    @Override
    public RunSnapshot get(RunId runId) {
        return requireReadableRun(runId).snapshot();
    }

    @Override
    public java.util.Optional<RunRequest> request(RunId runId) {
        return java.util.Optional.of(requireReadableRun(runId).request());
    }

    @Override
    public java.util.Optional<Instant> deadline(RunId runId) {
        StoredRun stored = requireReadableRun(runId);
        ActiveEngineRun active = activeRuns.get(runId);
        if (active != null && !(PersistedRunDeadline.workflowCoordinator(active.request)
                && (stored.snapshot().state() == RunState.PAUSED
                    || stored.snapshot().state() == RunState.WAITING_INPUT))) {
            return java.util.Optional.of(active.control.deadline());
        }
        return recovery.deadline(stored);
    }

    @Override
    public boolean expired(RunId runId) {
        return deadline(runId).map(value -> !clock.instant().isBefore(value)).orElse(false);
    }

    @Override
    public java.util.Optional<TaskResult> taskResult(RunId runId) {
        RunState state = requireReadableRun(runId).snapshot().state();
        if (!state.terminal() && state != RunState.PAUSED)
            return java.util.Optional.empty();
        return TaskResultEvaluator.latestOutcome(runs.eventsAfter(runId, 0), json);
    }

    @Override
    public java.util.Optional<com.javaclaw.framework.api.TaskEvidenceSnapshot> taskEvidence(RunId runId) {
        RunSnapshot before = requireReadableRun(runId).snapshot();
        if (!before.state().terminal()) return java.util.Optional.empty();
        var evidence = taskHarness.evidence(runId);
        RunSnapshot after = requireReadableRun(runId).snapshot();
        return before.version() == after.version() && before.lastSequence() == after.lastSequence()
                && before.state() == after.state() ? evidence : java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<com.javaclaw.framework.api.CapabilityMetadata> capabilityForReceipt(
            String tool, String operation) {
        return taskHarness.capabilityForReceipt(tool, operation);
    }

    @Override
    public java.util.Optional<com.javaclaw.framework.api.CapabilityMetadata> capabilityForTool(
            String tool) {
        return taskHarness.capabilityForTool(tool);
    }

    public int activeRunCount() {
        return activeRuns.size();
    }

    /** 直接工具调用也消耗引擎持有的 Turn 控制预算，后续模型调用不能重新取得额度。 */
    void recordDirectToolCall(RunId runId, String fingerprint, String invocationId) {
        ActiveEngineRun active = activeRuns.get(runId);
        if (active == null) throw new IllegalStateException("tool owner turn is not attached: " + runId);
        active.control.recordToolCall(fingerprint);
        usage.recordToolCall(runId, invocationId);
    }

    void launch(
            ActiveEngineRun active,
            ResumeCommand resume,
            ApprovedToolInvocation approvedInvocation,
            Set<RunState> startStates) {
        synchronized (active.launchLock) {
            if (active.executing.get()) {
                if (active.pendingLaunch != null) {
                    throw new IllegalStateException(
                            "run already has a queued reasoning turn: " + active.id);
                }
                // A waiting event is emitted before the previous callback unwinds. A fast
                // approval responder may therefore resume synchronously from that event; keep
                // the durable RUNNING transition and start it as soon as the old turn exits.
                active.pendingLaunch = new PendingLaunch(
                        resume, approvedInvocation, Set.copyOf(startStates));
                return;
            }
            active.executing.set(true);
        }
        try {
            executor.execute(() -> {
            try {
                if (active.detached.get() || active.terminated.get()) {
                    finishExecutionTurn(active);
                    return;
                }
                if (active.control.cancelled()) {
                    cancel(active.id, active.control.cancellationReason().orElseGet(() ->
                            new CancelReason("CANCELLED_DURING_EXECUTION", "")));
                    finishExecutionTurn(active);
                    return;
                }
                recovery.inheritEffects(active.id, active.request, active.control);
                if (startStates.contains(RunState.CREATED)) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    payload.put("executionPlanId", active.plan.descriptor().id());
                    var started = runs.append(active.id, Set.of(RunState.CREATED), RunState.RUNNING,
                            event(active.request, "core.run.started", "framework.core", payload, null),
                            null, null);
                    if (started.isEmpty()) {
                        finishExecutionTurn(active);
                        return;
                    }
                    active.sink.tryEmitNext(started.get());
                }
                taskHarness.ensure(active.id, active.request, active.control, active.sink::tryEmitNext);
                taskHarness.reviseUnreliableContract(active.id, active.request, resume,
                        active.control, active.sink::tryEmitNext);
                taskHarness.replayCheckpointEffects(active.id, active.request);
                taskHarness.reconcileCheckpoints(active.id, active.request,
                        active.control, active.sink::tryEmitNext);
                active.control.throwIfCancelled();
                active.ready.complete(null);
                if (active.request.attributes().getOrDefault("framework.managed",
                        JsonNodeFactory.instance.booleanNode(false)).asBoolean()) {
                    return;
                }
                TaskContractV3 contract = TaskResultEvaluator.latestContractV3(
                        runs.eventsAfter(active.id, 0), json).orElse(null);
                if (contract != null && !contract.reliable()) {
                    if (requiresContractInput(contract, active.request)) {
                        finishTurn(active, ReasoningResult.waitingForInput(
                                TaskContractDiagnostics.inputRequiredOutput(json, contract),
                                "TASK_NEEDS_INPUT"));
                        finishExecutionTurn(active);
                        return;
                    }
                    ObjectNode diagnostic = TaskContractDiagnostics.pausedOutput(json, contract, true);
                    var stopped = runs.append(active.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                            event(active.request, "core.task.stop", 3, "framework.core",
                                    JsonNodeFactory.instance.objectNode().put("reasonCode",
                                            TaskStopReason.UNRELIABLE_CONTRACT.name()), null), null, null);
                    stopped.ifPresent(active.sink::tryEmitNext);
                    finishTurn(active, new ReasoningResult(RunState.PAUSED, diagnostic, "TASK_UNVERIFIED"));
                    finishExecutionTurn(active);
                    return;
                }
                ReasoningRequest request = new ReasoningRequest(
                        active.id, active.plan, runEvents.resolvedTaskRequest(active), resume, active.control,
                        new ReasoningEventSink() {
                            @Override public void emit(String type, int version,
                                                       String producer, JsonNode payload) {
                                runEvents.appendReasoningEvent(active, type, version, producer, payload);
                            }

                            @Override public void toolStarted(JsonNode stepStarted,
                                                               JsonNode toolStarted) {
                                runEvents.appendToolStartedEvents(active, stepStarted, toolStarted);
                            }
                        },
                        approvedInvocation);
                reasoning.execute(request).whenCompleteAsync((result, failure) -> {
                    try {
                        if (!active.terminated.get() && !active.detached.get()) {
                            if (failure != null) fail(active, unwrap(failure));
                            else finishTurn(active, result);
                        }
                    } finally {
                        finishExecutionTurn(active);
                    }
                }, executor);
            } catch (Throwable failure) {
                try {
                    if (!active.terminated.get() && !active.detached.get()) {
                        fail(active, failure);
                    }
                } finally {
                    finishExecutionTurn(active);
                }
            }
            });
        } catch (RuntimeException submissionFailure) {
            synchronized (active.launchLock) {
                active.executing.set(false);
            }
            throw submissionFailure;
        }
    }

    private void finishExecutionTurn(ActiveEngineRun active) {
        PendingLaunch pending;
        synchronized (active.launchLock) {
            active.executing.set(false);
            pending = active.pendingLaunch;
            active.pendingLaunch = null;
        }
        if (pending != null && !active.terminated.get() && !active.detached.get()) {
            try {
                launch(active, pending.resume(), pending.approvedInvocation(),
                        pending.startStates());
            } catch (Throwable failure) {
                if (!active.terminated.get() && !active.detached.get()) fail(active, failure);
            }
        }
        cleanupIfReady(active);
    }

    private static boolean requiresContractInput(TaskContractV3 contract, RunRequest request) {
        return contract != null && !contract.reliable()
                && contract.intentStatus() == TaskContractV3.IntentStatus.NEEDS_HUMAN
                && Set.of("model", "model-repair").contains(contract.source())
                && !contract.unresolvedInputs().isEmpty()
                && !request.attributes().containsKey(TaskContractCompiler.ATTRIBUTE);
    }

    private void finishTurn(ActiveEngineRun active, ReasoningResult result) {
        if (active.detached.get()) return;
        if (active.control.cancelled()) {
            cancel(active.id, active.control.cancellationReason().orElseGet(() ->
                    new CancelReason("CANCELLED_DURING_EXECUTION", "")));
            return;
        }
        RunState next = result.nextState();
        // A specialized task must settle a non-recoverable stop rather than strand its parent.
        if (next == RunState.PAUSED && InteractionExecutionPolicy.isInteraction(active.request))
            next = RunState.FAILED;
        TaskResult taskResult = next == RunState.COMPLETED || next == RunState.PAUSED || next == RunState.FAILED
                ? taskHarness.persist(active.id, active.request, Set.of(RunState.RUNNING),
                        taskHarness.stopReason(active.id, result.reason()), active.sink::tryEmitNext) : null;
        if (next == RunState.COMPLETED && InteractionExecutionPolicy.isInteraction(active.request)
                && taskResult != null && taskResult.outcome() != TaskOutcome.VERIFIED_COMPLETE)
            next = RunState.FAILED;
        String eventType = switch (next) {
            case COMPLETED -> "core.run.completed";
            case WAITING_INPUT -> "core.run.waiting_input";
            case WAITING_APPROVAL -> "core.run.waiting_approval";
            case WAITING_CHILD -> "core.run.waiting_child";
            case WAITING_EVENT -> "core.run.waiting_event";
            case FAILED -> "core.run.failed";
            case PAUSED -> "core.run.paused";
            default -> throw new IllegalStateException("unexpected result state " + next);
        };
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        if (next == RunState.WAITING_APPROVAL) {
            ToolApprovalChallenge challenge = ToolApprovalChallenge.fromEventPayload(result.output());
            active.pendingApproval = challenge;
            active.pendingApprovedInvocation = null;
            payload.set("approval", challenge.toJson());
        } else if (result.output() != null) {
            active.pendingApprovedInvocation = null;
            payload.set("output", result.output());
        } else {
            active.pendingApprovedInvocation = null;
        }
        if (!result.reason().isBlank()) payload.put("reason", result.reason());
        if (next == RunState.COMPLETED) payload.set("turnResult", json.valueToTree(
                TurnHandoff.from(result.output(), new RunStepQuery(runs).steps(active.id),
                        usage.snapshot(active.id), usage.aggregateSnapshot(active.id))));
        if (taskResult != null) payload.set("taskResult", json.valueToTree(taskResult));
        List<RunId> children = next.terminal() ? attachedChildren(active.id) : List.of();
        var event = runs.append(active.id, Set.of(RunState.RUNNING), next,
                event(active.request, eventType, "framework.core", payload, null),
                result.output(), null);
        if (event.isEmpty()) return;
        active.sink.tryEmitNext(event.get());
        if (next == RunState.WAITING_CHILD || next == RunState.WAITING_EVENT)
            attachInteractionWait(active, next, result.output());
        if (next.terminal()) {
            terminate(active, new RunOutcome(active.id, next, result.output(), null), children);
        }
    }

    void attachInteractionWait(ActiveEngineRun active, RunState state, JsonNode wait) {
        interactionWaits.attachInteractionWait(active, state, wait);
    }

    void disposeInteractionWait(ActiveEngineRun active) {
        interactionWaits.disposeInteractionWait(active);
    }

    void fail(ActiveEngineRun active, Throwable failure) {
        if (active.detached.get()) return;
        if (failure instanceof RunCancelledException || active.control.cancelled()) {
            cancel(active.id, active.control.cancellationReason().orElseGet(() ->
                    new CancelReason("CANCELLED_DURING_EXECUTION", failure.getMessage())));
            return;
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        describeFailure(payload, failure);
        TaskResult taskResult = taskHarness.persist(active.id, active.request,
                Set.of(RunState.RUNNING, RunState.CREATED, RunState.WAITING_CHILD, RunState.WAITING_EVENT), "RUN_FAILED: " + failure,
                active.sink::tryEmitNext);
        if (taskResult != null) payload.set("taskResult", json.valueToTree(taskResult));
        List<RunId> children = attachedChildren(active.id);
        var event = runs.append(active.id, Set.of(RunState.RUNNING, RunState.CREATED, RunState.WAITING_CHILD, RunState.WAITING_EVENT), RunState.FAILED,
                event(active.request, "core.run.failed", "framework.core", payload, null),
                null, failure.toString());
        if (event.isPresent()) {
            active.sink.tryEmitNext(event.get());
            terminate(active, new RunOutcome(active.id, RunState.FAILED, null, failure.toString()), children);
        }
    }

    private static void describeFailure(ObjectNode payload, Throwable failure) {
        payload.put("errorType", failure.getClass().getName());
        payload.put("message", failure.getMessage() == null ? "" : failure.getMessage());
        BudgetExceededException budget = findBudgetFailure(failure);
        if (budget == null) return;
        payload.put("budgetKind", budget.kind().name());
        if (!budget.actual().isBlank()) payload.put("budgetActual", budget.actual());
        if (!budget.limit().isBlank()) payload.put("budgetLimit", budget.limit());
    }

    private static BudgetExceededException findBudgetFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof BudgetExceededException budget) return budget;
            current = current.getCause();
        }
        return null;
    }

    private List<RunId> attachedChildren(RunId parent) {
        return runs.nonTerminalRuns().stream().filter(child -> parent.equals(child.request().linkage().parentRunId())
                && !child.request().attributes().getOrDefault("framework.detached", JsonNodeFactory.instance.booleanNode(false)).asBoolean())
                .map(child -> child.snapshot().id()).toList();
    }
    private void cancelChildren(List<RunId> children, CancelReason reason) {
        children.forEach(child -> cancel(child, reason));
    }
    private void terminate(ActiveEngineRun active, RunOutcome outcome, List<RunId> children) {
        if (active.terminated.compareAndSet(false, true)) {
            disposeInteractionWait(active);
            cancelChildren(children, new CancelReason("PARENT_" + outcome.state(), active.id.value()));
            if (!active.ready.isDone()) active.ready.completeExceptionally(new IllegalStateException("turn ended before execution"));
            active.completion.complete(outcome);
            active.sink.tryEmitComplete();
            cleanupIfReady(active);
        }
    }

    /**
     * A terminal event may win while a model/tool callback is still unwinding. Keep the exact
     * extension generation leased until that execution stack has actually exited.
     */
    private void cleanupIfReady(ActiveEngineRun active) {
        if (active.executing.get() || (!active.terminated.get() && !active.detached.get())) return;
        if (!active.cleaned.compareAndSet(false, true)) return;
        activeRuns.remove(active.id, active);
        usage.close(active.id);
        try { active.plan.close(); }
        finally {
            active.physicallySettled.complete(null);
            if (active.terminated.get()) releaseAndLaunch(active.id);
        }
    }

    private void releaseAndLaunch(RunId completed) {
        runs.release(completed).ifPresent(next -> {
            ActiveEngineRun queued = activeRuns.get(next);
            if (!closed.get() && queued != null && runs.claim(next)) {
                try { launch(queued, null, null, Set.of(RunState.CREATED)); }
                catch (RuntimeException failure) { fail(queued, failure); }
            }
        });
    }

    RunHandle handle(RunId id) {
        requireReadableRun(id);
        ActiveEngineRun active = activeRuns.get(id);
        if (active != null) return new EngineRunHandle(this, active);
        StoredRun stored = requireRun(id);
        CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
        RunSnapshot snapshot = stored.snapshot();
        if (snapshot.state().terminal()) {
            completion.complete(new RunOutcome(id, snapshot.state(), snapshot.output(), snapshot.error()));
        }
        return new RunHandle() {
            @Override public RunId id() { return id; }
            @Override public Flux<RunEventEnvelope> events(long afterSequence) {
                return Flux.defer(() -> {
                    requireReadableRun(id);
                    return Flux.fromIterable(runs.eventsAfter(id, afterSequence))
                            .filter(event -> runs.readable(stored.request().scope()));
                });
            }
            @Override public CompletionStage<RunOutcome> completion() { return completion; }
        };
    }

    StoredRun requireReadableRun(RunId id) {
        StoredRun stored = requireRun(id);
        if (!runs.readable(stored.request().scope())) throw new NoSuchElementException("turn is not readable: " + id);
        return stored;
    }

    StoredRun requireRun(RunId id) {
        return runs.find(id).orElseThrow(() -> new NoSuchElementException("run not found: " + id));
    }

    private void attachRecovered(RunRecoveryCoordinator.RecoveredRun recovered) {
        StoredRun stored = recovered.stored();
        RunId id = stored.snapshot().id();
        ActiveEngineRun active = new ActiveEngineRun(id, stored.request(), recovered.plan(), recovered.control());
        active.pendingApproval = recovered.approval().pendingApproval();
        active.pendingApprovedInvocation = recovered.approval().pendingApprovedInvocation();
        if (activeRuns.putIfAbsent(id, active) != null) {
            throw new IllegalStateException("duplicate recovered run: " + id);
        }
        replayPersisted(active);
        recovery.pauseRecovered(stored, active.sink::tryEmitNext);
    }

    private void replayPersisted(ActiveEngineRun active) {
        runs.eventsAfter(active.id, 0).forEach(active.sink::tryEmitNext);
    }

    RunEventDraft event(
            RunRequest request, String type, String producer, JsonNode payload, String causationId) {
        return event(request, type, 1, producer, payload, causationId);
    }

    RunEventDraft event(
            RunRequest request, String type, int schemaVersion, String producer,
            JsonNode payload, String causationId) {
        return new RunEventDraft(type, schemaVersion, producer,
                request.linkage().correlationId(), causationId, payload);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("AgentEngine is closed");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (ActiveEngineRun active : List.copyOf(activeRuns.values())) detachForShutdown(active);
            waitTimers.close();
        }
    }

    private void detachForShutdown(ActiveEngineRun active) {
        if (!active.detached.compareAndSet(false, true)) return;
        disposeInteractionWait(active);
        active.control.cancel();
        StoredRun stored = runs.find(active.id).orElse(null);
        // 规划提问尚未执行任务；保留等待状态，让重启后的答案仍归属原任务。
        boolean waitingForContractInput = stored != null && stored.snapshot().state() == RunState.WAITING_INPUT
                && requiresContractInput(TaskResultEvaluator.latestContractV3(
                        runs.eventsAfter(active.id, 0), json).orElse(null), active.request);
        if (stored != null && !stored.snapshot().state().terminal()
                && stored.snapshot().state() != RunState.CREATED && !waitingForContractInput) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("reason", "KERNEL_SHUTDOWN");
            runs.append(active.id, Set.of(stored.snapshot().state()), RunState.PAUSED,
                    event(active.request, "core.run.paused", "framework.core", payload, null),
                    null, null).ifPresent(active.sink::tryEmitNext);
        }
        active.completion.completeExceptionally(new IllegalStateException(
                "run detached during kernel shutdown and remains resumable: " + active.id));
        active.ready.completeExceptionally(new IllegalStateException("turn detached during shutdown"));
        active.sink.tryEmitComplete();
        cleanupIfReady(active);
    }

    record PendingLaunch(
            ResumeCommand resume,
            ApprovedToolInvocation approvedInvocation,
            Set<RunState> startStates) { }
}
