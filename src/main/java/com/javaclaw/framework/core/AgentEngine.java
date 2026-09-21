package com.javaclaw.framework.core;

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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Clock;
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

/**
 * The single Agent run state machine. Product features submit RunRequest through AgentClient;
 * they never own model loops, subscriptions, checkpoints or cancellation state.
 */
public final class AgentEngine implements AgentClient, AutoCloseable {
    private static final Set<RunState> CANCELLABLE = Set.of(
            RunState.CREATED, RunState.RUNNING, RunState.WAITING_INPUT,
            RunState.WAITING_APPROVAL, RunState.PAUSED,
            RunState.RECOVERY_BLOCKED_MISSING_EXTENSION);

    private final AgentCompiler compiler;
    private final RunStore runs;
    private final ExecutionPlanStore plans;
    private final ReasoningGateway reasoning;
    private final Executor executor;
    private final ObjectMapper json;
    private final Clock clock;
    private final RunUsageLedger usage;
    private final ConcurrentHashMap<RunId, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentEngine(
            AgentCompiler compiler,
            RunStore runs,
            ExecutionPlanStore plans,
            ReasoningGateway reasoning,
            Executor executor,
            ObjectMapper json,
            Clock clock,
            RunUsageLedger usage) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.reasoning = Objects.requireNonNull(reasoning, "reasoning");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.usage = Objects.requireNonNull(usage, "usage");
        runs.recoverClaims();
        recoverPersistedRuns();
    }

    @Override
    public RunHandle start(RunRequest request) {
        ensureOpen();
        if (request.idempotencyKey() != null) {
            var existing = runs.findByIdempotencyKey(
                    request.scope(), request.idempotencyKey());
            if (existing.isPresent()) return handle(existing.get().snapshot().id());
        }

        new RunUsageRecovery(runs, plans, json, usage).restore(request.linkage().parentRunId());
        request = runs.prepare(request);
        ExecutionPlan plan = compiler.compile(request);
        RunRequest effectiveRequest = plan.annotate(request);
        RunId openedAccount = null;
        boolean durableCreated = false;
        try {
            plans.save(plan.descriptor().id(), plan.descriptor().extensionGeneration(),
                    json.valueToTree(plan.descriptor()), plan.descriptor().checksum());
            RunId id = RunId.random();
            ActiveRun active = new ActiveRun(id, effectiveRequest, plan,
                    new RunControl(plan.descriptor().budget(), clock));
            usage.open(id, plan.descriptor().budget(), effectiveRequest.scope(), RunUsageRecovery.budgetParent(effectiveRequest));
            openedAccount = id;
            ActiveRun collision = activeRuns.putIfAbsent(id, active);
            if (collision != null) throw new IllegalStateException("run id collision: " + id);

            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("executionPlanId", plan.descriptor().id());
            payload.put("source", request.source().kind());
            CreateRunResult created = runs.create(id, effectiveRequest, plan.descriptor().id(),
                    event(effectiveRequest, "core.run.created", "framework.core", payload, null));
            if (!created.created()) {
                activeRuns.remove(id, active);
                usage.close(id);
                plan.close();
                return handle(created.run().snapshot().id());
            }
            durableCreated = true;
            replayPersisted(active);
            if (runs.claim(id)) launch(active, null, null, Set.of(RunState.CREATED));
            return new EngineRunHandle(active);
        } catch (RuntimeException failure) {
            if (openedAccount != null) {
                if (durableCreated) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    describeFailure(payload, failure);
                    runs.append(openedAccount, Set.of(RunState.CREATED), RunState.FAILED,
                            event(effectiveRequest, "core.run.failed", "framework.core", payload, null),
                            null, failure.toString());
                }
                activeRuns.remove(openedAccount);
                usage.close(openedAccount);
                if (durableCreated) releaseAndLaunch(openedAccount);
            }
            plan.close();
            throw failure;
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
        ActiveRun active = activeRuns.get(handle.id());
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
                (type, payload) -> { if (active != null) appendReasoningEvent(active, type, 1, "framework.orchestration", payload); },
                result -> { if (active != null) finishTurn(active, result); },
                failure -> { if (active != null) fail(active, failure); },
                () -> active != null ? active.control.cancelled() : get(handle.id()).state() == RunState.CANCELLED,
                () -> { if (active != null) finishManagedExecution(active); });
    }

    private void finishManagedExecution(ActiveRun active) {
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
        ActiveRun active = activeRuns.get(runId);
        if (active == null) {
            StoredRun stored = requireRun(runId);
            if (stored.snapshot().state().terminal()) return handle(runId);
            throw new IllegalStateException(
                    "run is not attached to this process; recovery must restore its locked execution plan first");
        }
        RunState current = get(runId).state();
        if (current.terminal()) return handle(runId);
        if (current != RunState.PAUSED && current != RunState.WAITING_INPUT && current != RunState.WAITING_APPROVAL)
            throw new IllegalStateException("run is not resumable: " + runId);
        if (!runs.claim(runId)) throw new IllegalStateException("another turn owns this thread");
        ApprovedToolInvocation approvedInvocation = active.pendingApprovedInvocation;
        if (command.type().equals("tool.approval")) {
            String fingerprint = command.payload().path("fingerprint").asText("");
            ToolApprovalChallenge challenge = active.pendingApproval;
            if (fingerprint.isBlank()) {
                throw new IllegalArgumentException("tool approval resume requires a fingerprint");
            }
            boolean matchesApprovedContinuation = approvedInvocation != null
                    && approvedInvocation.challenge().fingerprint().equals(fingerprint);
            if ((challenge == null || !challenge.fingerprint().equals(fingerprint))
                    && !matchesApprovedContinuation) {
                throw new IllegalArgumentException(
                        "approved tool fingerprint does not match pending challenge");
            }
            if (!command.payload().path("approved").asBoolean(false)) {
                cancel(runId, new CancelReason(
                        "TOOL_APPROVAL_DENIED", "tool approval was explicitly denied"));
                return new EngineRunHandle(active);
            }
            if (!matchesApprovedContinuation) {
                ToolApprovalGrant grant = new ToolApprovalGrant(
                        challenge.tool(), fingerprint, true,
                        command.payload().path("humanApproved").asBoolean(false));
                approvedInvocation = new ApprovedToolInvocation(challenge, grant);
            }
        } else if (active.pendingApproval != null) {
            throw new IllegalArgumentException(
                    "a run waiting for tool approval requires a tool.approval command");
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("commandType", command.type());
        payload.set("command", command.payload());
        if (active.ready.isDone()) active.ready = new CompletableFuture<>();
        var event = runs.append(runId,
                Set.of(RunState.WAITING_INPUT, RunState.WAITING_APPROVAL, RunState.PAUSED),
                RunState.RUNNING,
                event(active.request, "core.run.resumed", "framework.core", payload, null),
                null, null);
        if (event.isEmpty()) {
            throw new IllegalStateException("run is not resumable: " + runId);
        }
        if (approvedInvocation != null) {
            active.pendingApprovedInvocation = approvedInvocation;
            active.control.approveToolCall(approvedInvocation.grant());
        }
        active.pendingApproval = null;
        active.sink.tryEmitNext(event.get());
        launch(active, command, approvedInvocation, Set.of(RunState.RUNNING));
        return new EngineRunHandle(active);
    }

    @Override
    public boolean cancel(RunId runId, CancelReason reason) {
        ActiveRun active = activeRuns.get(runId);
        if (active != null) active.control.cancel();
        StoredRun stored = runs.find(runId).orElse(null);
        if (stored == null || stored.snapshot().state().terminal()) return false;
        List<RunId> children = attachedChildren(runId);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", reason.code());
        payload.put("detail", reason.detail());
        var event = runs.append(runId, CANCELLABLE, RunState.CANCELLED,
                event(stored.request(), "core.run.cancelled", "framework.core", payload, null),
                null, reason.detail());
        event.ifPresent(value -> {
            if (active != null) {
                active.sink.tryEmitNext(value);
                terminate(active, new RunOutcome(runId, RunState.CANCELLED, null, reason.detail()), children);
            } else { cancelChildren(children, reason); releaseAndLaunch(runId); }
        });
        return event.isPresent();
    }

    @Override
    public RunSnapshot get(RunId runId) {
        return requireReadableRun(runId).snapshot();
    }

    public int activeRunCount() {
        return activeRuns.size();
    }

    private void launch(
            ActiveRun active,
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
                active.control.throwIfCancelled();
                active.ready.complete(null);
                if (active.request.attributes().getOrDefault("framework.managed",
                        JsonNodeFactory.instance.booleanNode(false)).asBoolean()) {
                    return;
                }
                ReasoningRequest request = new ReasoningRequest(
                        active.id, active.plan, active.request, resume, active.control,
                        (type, version, producer, payload) ->
                                appendReasoningEvent(active, type, version, producer, payload),
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

    private void finishExecutionTurn(ActiveRun active) {
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

    private void appendReasoningEvent(
            ActiveRun active, String type, int version, String producer, JsonNode payload) {
        if (StepEvents.isSettlement(type)) {
            var settled = runs.settleStep(active.id, event(active.request, type, producer, payload,
                    payload.path("causation").asText(null)));
            if (settled.isEmpty()) throw new IllegalStateException("step cannot be settled: " + active.id);
            active.sink.tryEmitNext(settled.get());
            return;
        }
        active.control.throwIfCancelled();
        JsonNode encoded = active.plan.encodeEvent(type, version, payload);
        var event = runs.append(active.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                event(active.request, type, producer, encoded, null), null, null);
        event.ifPresent(value -> {
            if (type.equals("core.tool.started")) {
                String fingerprint = payload.path("fingerprint").asText("");
                ApprovedToolInvocation approved = active.pendingApprovedInvocation;
                if (approved != null
                        && approved.challenge().fingerprint().equals(fingerprint)) {
                    active.pendingApprovedInvocation = null;
                }
            }
            active.sink.tryEmitNext(value);
        });
    }

    private void finishTurn(ActiveRun active, ReasoningResult result) {
        if (active.detached.get()) return;
        if (active.control.cancelled()) {
            cancel(active.id, new CancelReason("CANCELLED_DURING_EXECUTION", ""));
            return;
        }
        RunState next = result.nextState();
        String eventType = switch (next) {
            case COMPLETED -> "core.run.completed";
            case WAITING_INPUT -> "core.run.waiting_input";
            case WAITING_APPROVAL -> "core.run.waiting_approval";
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
        List<RunId> children = next.terminal() ? attachedChildren(active.id) : List.of();
        var event = runs.append(active.id, Set.of(RunState.RUNNING), next,
                event(active.request, eventType, "framework.core", payload, null),
                result.output(), null);
        if (event.isEmpty()) return;
        active.sink.tryEmitNext(event.get());
        if (next.terminal()) {
            terminate(active, new RunOutcome(active.id, next, result.output(), null), children);
        }
    }

    private void fail(ActiveRun active, Throwable failure) {
        if (active.detached.get()) return;
        if (failure instanceof RunCancelledException || active.control.cancelled()) {
            cancel(active.id, new CancelReason("CANCELLED_DURING_EXECUTION", failure.getMessage()));
            return;
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        describeFailure(payload, failure);
        List<RunId> children = attachedChildren(active.id);
        var event = runs.append(active.id, Set.of(RunState.RUNNING, RunState.CREATED), RunState.FAILED,
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
    private void terminate(ActiveRun active, RunOutcome outcome, List<RunId> children) {
        if (active.terminated.compareAndSet(false, true)) {
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
    private void cleanupIfReady(ActiveRun active) {
        if (active.executing.get() || (!active.terminated.get() && !active.detached.get())) return;
        if (!active.cleaned.compareAndSet(false, true)) return;
        activeRuns.remove(active.id, active);
        usage.close(active.id);
        try { active.plan.close(); }
        finally { if (active.terminated.get()) releaseAndLaunch(active.id); }
    }

    private void releaseAndLaunch(RunId completed) {
        runs.release(completed).ifPresent(next -> {
            ActiveRun queued = activeRuns.get(next);
            if (!closed.get() && queued != null && runs.claim(next)) {
                try { launch(queued, null, null, Set.of(RunState.CREATED)); }
                catch (RuntimeException failure) { fail(queued, failure); }
            }
        });
    }

    private RunHandle handle(RunId id) {
        requireReadableRun(id);
        ActiveRun active = activeRuns.get(id);
        if (active != null) return new EngineRunHandle(active);
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

    private StoredRun requireReadableRun(RunId id) {
        StoredRun stored = requireRun(id);
        if (!runs.readable(stored.request().scope())) throw new NoSuchElementException("turn is not readable: " + id);
        return stored;
    }

    private StoredRun requireRun(RunId id) {
        return runs.find(id).orElseThrow(() -> new NoSuchElementException("run not found: " + id));
    }

    private void recoverPersistedRuns() {
        RunUsageRecovery usageRecovery = new RunUsageRecovery(runs, plans, json, usage);
        for (StoredRun stored : runs.nonTerminalRuns()) {
            if (!runs.readable(stored.request().scope())) continue;
            RunId id = stored.snapshot().id();
            try { usageRecovery.restore(id); }
            catch (RuntimeException failure) {
                markRecoveryBlocked(stored, "USAGE_LINEAGE_RECOVERY_FAILED", failure.getMessage());
                continue;
            }
            ExecutionPlanDescriptor descriptor;
            try {
                JsonNode persisted = plans.find(stored.snapshot().executionPlanId())
                        .orElseThrow(() -> new IllegalStateException("persisted execution plan is missing"));
                descriptor = json.treeToValue(persisted, ExecutionPlanDescriptor.class);
            } catch (Exception failure) {
                markRecoveryBlocked(stored, "MISSING_EXECUTION_PLAN", failure.getMessage());
                continue;
            }
            ExecutionPlan plan = compiler.restore(descriptor).orElse(null);
            if (plan == null) {
                String locks = descriptor.extensionLocks().stream()
                        .map(lock -> lock.extensionId() + ":" + lock.version() + "@"
                                + lock.artifactSha256().substring(0, 12))
                        .collect(java.util.stream.Collectors.joining(","));
                markRecoveryBlocked(stored, "MISSING_LOCKED_EXTENSION", locks);
                continue;
            }
            try {
                ActiveRun active = new ActiveRun(id, stored.request(), plan,
                        new RunControl(descriptor.budget(), clock,
                                stored.snapshot().createdAt().plus(descriptor.budget().timeout())));
                restoreBudgetState(active);
                if (activeRuns.putIfAbsent(id, active) != null) {
                    throw new IllegalStateException("duplicate recovered run: " + id);
                }
                replayPersisted(active);
                RunState state = stored.snapshot().state();
                if ((state == RunState.CREATED && runs.claim(id)) || state == RunState.RUNNING
                        || state == RunState.RECOVERY_BLOCKED_MISSING_EXTENSION) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    payload.put("reason", "PROCESS_RESTART_REQUIRES_RESUME");
                    runs.append(id, Set.of(state), RunState.PAUSED,
                            event(stored.request(), "core.run.recovered_paused",
                                    "framework.core", payload, null), null, null)
                            .ifPresent(active.sink::tryEmitNext);
                }
            } catch (RuntimeException failure) {
                activeRuns.remove(id);
                usage.close(id);
                plan.close();
                markRecoveryBlocked(stored, "RECOVERY_ATTACH_FAILED", failure.getMessage());
            }
        }
    }

    private void markRecoveryBlocked(StoredRun stored, String code, String detail) {
        RunState current = runs.find(stored.snapshot().id())
                .map(value -> value.snapshot().state()).orElse(stored.snapshot().state());
        if (current.terminal()) return;
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", code);
        payload.put("detail", detail == null ? "" : detail);
        runs.append(stored.snapshot().id(), Set.of(current),
                RunState.RECOVERY_BLOCKED_MISSING_EXTENSION,
                event(stored.request(), "core.run.recovery_blocked",
                        "framework.core", payload, null), null, detail);
    }

    private void replayPersisted(ActiveRun active) {
        runs.eventsAfter(active.id, 0).forEach(active.sink::tryEmitNext);
    }

    private void restoreBudgetState(ActiveRun active) {
        List<RunEventEnvelope> events = runs.eventsAfter(active.id, 0);
        ToolApprovalChallenge pendingApproval = null;
        ApprovedToolInvocation pendingApprovedInvocation = null;
        for (RunEventEnvelope event : events) {
            if (event.type().equals("core.tool.started")) {
                String fingerprint = event.payload().path("fingerprint").asText("");
                if (fingerprint.isBlank()) fingerprint = "recovered-tool-event:" + event.sequence();
                active.control.restoreToolCall(fingerprint);
                if (pendingApprovedInvocation != null
                        && pendingApprovedInvocation.challenge().fingerprint().equals(fingerprint)) {
                    pendingApprovedInvocation = null;
                }
            }
            if (event.type().equals("core.run.waiting_approval")) {
                try {
                    pendingApproval = ToolApprovalChallenge.fromEventPayload(event.payload());
                    pendingApprovedInvocation = null;
                } catch (IllegalArgumentException ignored) {
                    // Keep malformed legacy events replayable, but never synthesize an authorization.
                    pendingApproval = null;
                    pendingApprovedInvocation = null;
                }
            } else if (event.type().equals("core.run.resumed") && pendingApproval != null) {
                JsonNode command = event.payload().path("command");
                String commandType = event.payload().path("commandType").asText("");
                String fingerprint = command.path("fingerprint").asText("");
                if (commandType.equals("tool.approval")
                        && command.path("approved").asBoolean(false)
                        && pendingApproval.fingerprint().equals(fingerprint)) {
                    ToolApprovalGrant grant = new ToolApprovalGrant(
                            pendingApproval.tool(), fingerprint, true,
                            command.path("humanApproved").asBoolean(false));
                    pendingApprovedInvocation = new ApprovedToolInvocation(
                            pendingApproval, grant);
                } else {
                    pendingApprovedInvocation = null;
                }
                pendingApproval = null;
            } else if (clearsApprovalState(event)) {
                pendingApproval = null;
                pendingApprovedInvocation = null;
            }
        }
        active.pendingApproval = pendingApproval;
        active.pendingApprovedInvocation = pendingApprovedInvocation;
    }

    private static boolean clearsApprovalState(RunEventEnvelope event) {
        return switch (event.type()) {
            case "core.run.waiting_input", "core.run.completed", "core.run.failed",
                    "core.run.cancelled", "core.run.recovery_blocked" -> true;
            case "core.run.paused" -> !event.payload().path("reason")
                    .asText("").equals("KERNEL_SHUTDOWN");
            default -> false;
        };
    }

    private RunEventDraft event(
            RunRequest request, String type, String producer, JsonNode payload, String causationId) {
        return new RunEventDraft(type, 1, producer,
                request.linkage().correlationId(), causationId, payload);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("AgentEngine is closed");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (ActiveRun active : List.copyOf(activeRuns.values())) detachForShutdown(active);
        }
    }

    private void detachForShutdown(ActiveRun active) {
        if (!active.detached.compareAndSet(false, true)) return;
        active.control.cancel();
        StoredRun stored = runs.find(active.id).orElse(null);
        if (stored != null && !stored.snapshot().state().terminal() && stored.snapshot().state() != RunState.CREATED) {
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

    private final class EngineRunHandle implements RunHandle {
        private final ActiveRun active;

        private EngineRunHandle(ActiveRun active) { this.active = active; }

        @Override public RunId id() { return active.id; }

        @Override
        public Flux<RunEventEnvelope> events(long afterSequence) {
            Flux<RunEventEnvelope> durable = Flux.defer(() -> {
                requireReadableRun(active.id);
                return Flux.fromIterable(runs.eventsAfter(active.id, afterSequence));
            });
            Flux<RunEventEnvelope> live = active.sink.asFlux()
                    .filter(event -> event.sequence() > afterSequence);
            return Flux.concat(durable, live).filter(event -> runs.readable(active.request.scope()))
                    .distinct(RunEventEnvelope::sequence);
        }

        @Override public CompletionStage<RunOutcome> completion() { return active.completion; }
    }

    private static final class ActiveRun {
        private final RunId id;
        private final RunRequest request;
        private final ExecutionPlan plan;
        private final RunControl control;
        private final Sinks.Many<RunEventEnvelope> sink = Sinks.many().replay().all();
        private final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
        private volatile CompletableFuture<Void> ready = new CompletableFuture<>();
        private final Object launchLock = new Object();
        private final AtomicBoolean executing = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean detached = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicBoolean managedAttached = new AtomicBoolean();
        private volatile ToolApprovalChallenge pendingApproval;
        private volatile ApprovedToolInvocation pendingApprovedInvocation;
        private PendingLaunch pendingLaunch;

        private ActiveRun(RunId id, RunRequest request, ExecutionPlan plan, RunControl control) {
            this.id = id;
            this.request = request;
            this.plan = plan;
            this.control = control;
        }
    }

    private record PendingLaunch(
            ResumeCommand resume,
            ApprovedToolInvocation approvedInvocation,
            Set<RunState> startStates) { }
}
