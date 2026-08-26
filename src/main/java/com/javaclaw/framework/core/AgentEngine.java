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
import com.javaclaw.framework.spi.RunResourceRegistry;
import com.javaclaw.framework.spi.StoredRun;
import com.javaclaw.framework.spi.ToolOutcomeCommitException;
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
    private final RunEventRelay events;
    private final CommittedRunEventWriter committedEvents;
    private final ToolOutcomeCommitter toolOutcomes;
    private final RunResourceRegistry resources;
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
            RunUsageLedger usage,
            RunEventRelay events) {
        this(compiler, runs, plans, reasoning, executor, json, clock, usage, events,
                new DefaultRunResourceRegistry());
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
            RunEventRelay events,
            RunResourceRegistry resources) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.reasoning = Objects.requireNonNull(reasoning, "reasoning");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.events = Objects.requireNonNull(events, "events");
        this.committedEvents = new CommittedRunEventWriter(runs, events);
        this.toolOutcomes = new ToolOutcomeCommitter(runs, committedEvents, events);
        this.resources = Objects.requireNonNull(resources, "resources");
        recoverPersistedRuns();
    }

    @Override
    public RunHandle start(RunRequest request) {
        ensureOpen();
        if (request.idempotencyKey() != null) {
            var existing = runs.findByIdempotencyKey(
                    request.scope().workspaceId(), request.idempotencyKey());
            if (existing.isPresent()) return handle(existing.get().snapshot().id());
        }

        ExecutionPlan plan = compiler.compile(request);
        RunRequest effectiveRequest = plan.annotate(request);
        RunId openedAccount = null;
        ActiveRun openedRun = null;
        boolean durableCreated = false;
        try {
            plans.save(plan.descriptor().id(), plan.descriptor().extensionGeneration(),
                    json.valueToTree(plan.descriptor()), plan.descriptor().checksum());
            RunId id = RunId.random();
            ActiveRun active = new ActiveRun(id, effectiveRequest, plan,
                    new RunControl(plan.descriptor().budget(), clock));
            openedRun = active;
            usage.open(id, plan.descriptor().budget(), effectiveRequest.scope());
            openedAccount = id;
            ActiveRun collision = activeRuns.putIfAbsent(id, active);
            if (collision != null) throw new IllegalStateException("run id collision: " + id);
            events.attach(id, active.sink);

            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("executionPlanId", plan.descriptor().id());
            payload.put("source", request.source().kind());
            CreateRunResult created = runs.create(id, effectiveRequest, plan.descriptor().id(),
                    event(effectiveRequest, "core.run.created", "framework.core", payload, null));
            if (!created.created()) {
                activeRuns.remove(id, active);
                events.detach(id, active.sink);
                usage.close(id);
                resources.release(id);
                plan.close();
                return handle(created.run().snapshot().id());
            }
            durableCreated = true;
            replayPersisted(active);
            launch(active, null, null, Set.of(RunState.CREATED));
            return new EngineRunHandle(active);
        } catch (RuntimeException failure) {
            if (openedAccount != null) {
                if (durableCreated) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    describeFailure(payload, failure);
                    committedEvents.transition(
                            openedAccount, Set.of(RunState.CREATED), RunState.FAILED,
                            event(effectiveRequest, "core.run.failed", "framework.core", payload, null),
                            null, failure.toString());
                }
                if (openedRun != null) {
                    activeRuns.remove(openedAccount, openedRun);
                    events.detach(openedAccount, openedRun.sink);
                }
                usage.close(openedAccount);
                resources.release(openedAccount);
            }
            plan.close();
            throw failure;
        }
    }

    @Override
    public RunHandle resume(RunId runId, ResumeCommand command) {
        ensureOpen();
        ActiveRun active = activeRuns.get(runId);
        if (active == null) {
            StoredRun stored = requireRun(runId);
            if (stored.snapshot().state().terminal()) return handle(runId);
            throw new IllegalStateException(
                    "run is not attached to this process; recovery must restore its locked execution plan first");
        }
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
        var event = committedEvents.transition(runId,
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
        launch(active, command, approvedInvocation, Set.of(RunState.RUNNING));
        return new EngineRunHandle(active);
    }

    @Override
    public boolean cancel(RunId runId, CancelReason reason) {
        ActiveRun active = activeRuns.get(runId);
        if (active != null) active.control.cancel();
        StoredRun stored = runs.find(runId).orElse(null);
        if (stored == null || stored.snapshot().state().terminal()) return false;
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", reason.code());
        payload.put("detail", reason.detail());
        var event = committedEvents.transition(runId, CANCELLABLE, RunState.CANCELLED,
                event(stored.request(), "core.run.cancelled", "framework.core", payload, null),
                null, reason.detail());
        event.ifPresent(value -> {
            if (active != null) {
                terminate(active, new RunOutcome(runId, RunState.CANCELLED, null, reason.detail()));
            }
        });
        return event.isPresent();
    }

    @Override
    public RunSnapshot get(RunId runId) {
        return requireRun(runId).snapshot();
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
                if (startStates.contains(RunState.CREATED)) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    payload.put("executionPlanId", active.plan.descriptor().id());
                    var started = committedEvents.transition(
                            active.id, Set.of(RunState.CREATED), RunState.RUNNING,
                            event(active.request, "core.run.started", "framework.core", payload, null),
                            null, null);
                    if (started.isEmpty()) {
                        finishExecutionTurn(active);
                        return;
                    }
                }
                active.control.throwIfCancelled();
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
        completeTerminalIfReady(active);
        cleanupIfReady(active);
    }

    private void appendReasoningEvent(
            ActiveRun active, String type, int version, String producer, JsonNode payload) {
        JsonNode encoded = active.plan.encodeEvent(type, version, payload);
        String invocationId = type.equals("core.tool.completed")
                ? requiredInvocationId(payload) : null;
        var draft = event(active.request, type, version, producer, encoded, invocationId);
        // Usage describes an already-issued provider response. It remains a valid fact after a
        // concurrent cancellation has moved the Run to a terminal state. The same is true for a
        // tool outcome whose external side effect has already returned.
        var event = type.equals("core.tool.completed")
                ? toolOutcomes.append(
                        active.id, draft, invocationId,
                        active.committedToolOutcomes, active.uncertainToolOutcomeCommits)
                : type.equals("core.model.usage")
                    ? committedEvents.append(active.id, draft)
                    : appendRunningEvent(active, draft);
        if (event.isEmpty()) {
            throw new IllegalStateException(
                    "could not commit reasoning event " + type + " for " + active.id);
        }
        event.ifPresent(value -> {
            if (type.equals("core.tool.started")) {
                String fingerprint = payload.path("fingerprint").asText("");
                ApprovedToolInvocation approved = active.pendingApprovedInvocation;
                if (approved != null
                        && approved.challenge().fingerprint().equals(fingerprint)) {
                    active.pendingApprovedInvocation = null;
                }
            }
        });
    }

    private static String requiredInvocationId(JsonNode payload) {
        String invocationId = payload.path("invocationId").asText("").strip();
        if (invocationId.isEmpty()) {
            throw new IllegalArgumentException("tool completion requires invocationId");
        }
        return invocationId;
    }

    private java.util.Optional<RunEventEnvelope> appendRunningEvent(
            ActiveRun active, RunEventDraft draft) {
        active.control.throwIfCancelled();
        return committedEvents.transition(
                active.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                draft, null, null);
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
        var event = committedEvents.transition(active.id, Set.of(RunState.RUNNING), next,
                event(active.request, eventType, "framework.core", payload, null),
                result.output(), null);
        if (event.isEmpty()) return;
        if (next.terminal()) {
            terminate(active, new RunOutcome(active.id, next, result.output(), null));
        }
    }

    private void fail(ActiveRun active, Throwable failure) {
        if (active.detached.get()) return;
        ToolOutcomeCommitException toolOutcome = findToolOutcomeCommitFailure(failure);
        if (toolOutcome == null
                && (failure instanceof RunCancelledException || active.control.cancelled())) {
            cancel(active.id, new CancelReason("CANCELLED_DURING_EXECUTION", failure.getMessage()));
            return;
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        describeFailure(payload, failure);
        var event = committedEvents.transition(
                active.id, Set.of(RunState.RUNNING, RunState.CREATED), RunState.FAILED,
                event(active.request, "core.run.failed", "framework.core", payload, null),
                null, failure.toString());
        if (event.isPresent()) {
            terminate(active, new RunOutcome(active.id, RunState.FAILED, null, failure.toString()));
        }
    }

    private static void describeFailure(ObjectNode payload, Throwable failure) {
        payload.put("errorType", failure.getClass().getName());
        payload.put("message", failure.getMessage() == null ? "" : failure.getMessage());
        ToolOutcomeCommitException toolOutcome = findToolOutcomeCommitFailure(failure);
        if (toolOutcome != null) {
            payload.put("code", "TOOL_OUTCOME_COMMIT_FAILED");
            payload.put("tool", toolOutcome.tool());
            payload.put("invocationId", toolOutcome.invocationId());
            payload.put("sideEffectMayHaveOccurred", toolOutcome.sideEffectMayHaveOccurred());
        }
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

    private static ToolOutcomeCommitException findToolOutcomeCommitFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ToolOutcomeCommitException outcome) return outcome;
            current = current.getCause();
        }
        return null;
    }

    private void terminate(ActiveRun active, RunOutcome outcome) {
        if (active.terminalOutcome.compareAndSet(null, outcome)) {
            active.terminated.set(true);
            completeTerminalIfReady(active);
        }
    }

    private void completeTerminalIfReady(ActiveRun active) {
        if (!active.terminated.get() || active.executing.get()) return;
        if (!active.terminalDelivered.compareAndSet(false, true)) return;
        RunOutcome outcome = Objects.requireNonNull(
                active.terminalOutcome.get(), "terminal outcome");
        active.completion.complete(outcome);
        events.complete(active.id, active.sink);
        cleanupIfReady(active);
    }

    /**
     * A terminal event may win while a model/tool callback is still unwinding. Keep the exact
     * extension generation leased until that execution stack has actually exited.
     */
    private void cleanupIfReady(ActiveRun active) {
        if (active.executing.get()
                || (!active.terminalDelivered.get() && !active.detached.get())) return;
        if (!active.cleaned.compareAndSet(false, true)) return;
        activeRuns.remove(active.id, active);
        events.detach(active.id, active.sink);
        usage.close(active.id);
        resources.release(active.id);
        active.plan.close();
    }

    private RunHandle handle(RunId id) {
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
                return Flux.fromIterable(runs.eventsAfter(id, afterSequence));
            }
            @Override public CompletionStage<RunOutcome> completion() { return completion; }
        };
    }

    private StoredRun requireRun(RunId id) {
        return runs.find(id).orElseThrow(() -> new NoSuchElementException("run not found: " + id));
    }

    private void recoverPersistedRuns() {
        for (StoredRun stored : runs.nonTerminalRuns()) {
            RunId id = stored.snapshot().id();
            ToolOutcomeCommitter.UncertainToolOutcome uncertain;
            try {
                uncertain = toolOutcomes.findUnclosed(id);
            } catch (RuntimeException failure) {
                markRecoveryBlocked(stored, "RECOVERY_EVENT_REPLAY_FAILED", failure.getMessage());
                continue;
            }
            if (uncertain != null) {
                failUncertainToolOutcome(stored, uncertain);
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
            ActiveRun recovered = null;
            try {
                usage.open(id, descriptor.budget(), stored.request().scope());
                ActiveRun active = new ActiveRun(id, stored.request(), plan,
                        new RunControl(descriptor.budget(), clock,
                                stored.snapshot().createdAt().plus(descriptor.budget().timeout())));
                recovered = active;
                restoreBudgetState(active);
                if (activeRuns.putIfAbsent(id, active) != null) {
                    throw new IllegalStateException("duplicate recovered run: " + id);
                }
                events.attach(id, active.sink);
                replayPersisted(active);
                RunState state = stored.snapshot().state();
                if (state == RunState.CREATED || state == RunState.RUNNING
                        || state == RunState.RECOVERY_BLOCKED_MISSING_EXTENSION) {
                    ObjectNode payload = JsonNodeFactory.instance.objectNode();
                    payload.put("reason", "PROCESS_RESTART_REQUIRES_RESUME");
                    committedEvents.transition(id, Set.of(state), RunState.PAUSED,
                            event(stored.request(), "core.run.recovered_paused",
                                    "framework.core", payload, null), null, null);
                }
            } catch (RuntimeException failure) {
                if (recovered != null) {
                    activeRuns.remove(id, recovered);
                    events.detach(id, recovered.sink);
                }
                usage.close(id);
                resources.release(id);
                plan.close();
                markRecoveryBlocked(stored, "RECOVERY_ATTACH_FAILED", failure.getMessage());
            }
        }
    }

    private void failUncertainToolOutcome(
            StoredRun stored, ToolOutcomeCommitter.UncertainToolOutcome uncertain) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", "UNCERTAIN_TOOL_OUTCOME");
        payload.put("errorType", ToolOutcomeCommitException.class.getName());
        payload.put("message", "tool outcome was not durably closed before restart");
        payload.put("tool", uncertain.tool());
        payload.put("invocationId", uncertain.invocationId());
        payload.put("sideEffectMayHaveOccurred", true);
        try {
            var failed = committedEvents.transition(
                    stored.snapshot().id(), Set.of(stored.snapshot().state()), RunState.FAILED,
                    event(stored.request(), "core.run.failed", "framework.core",
                            payload, uncertain.invocationId()),
                    null, "UNCERTAIN_TOOL_OUTCOME: " + uncertain.tool()
                            + " invocation " + uncertain.invocationId());
            if (failed.isEmpty()) {
                throw new IllegalStateException(
                        "could not fail Run with an uncertain tool outcome: "
                                + stored.snapshot().id());
            }
        } catch (RuntimeException failure) {
            markRecoveryBlocked(
                    stored, "UNCERTAIN_TOOL_OUTCOME_COMMIT_FAILED", failure.getMessage());
        }
    }

    private void markRecoveryBlocked(StoredRun stored, String code, String detail) {
        RunState current = runs.find(stored.snapshot().id())
                .map(value -> value.snapshot().state()).orElse(stored.snapshot().state());
        if (current.terminal()) return;
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("code", code);
        payload.put("detail", detail == null ? "" : detail);
        committedEvents.transition(stored.snapshot().id(), Set.of(current),
                RunState.RECOVERY_BLOCKED_MISSING_EXTENSION,
                event(stored.request(), "core.run.recovery_blocked",
                        "framework.core", payload, null), null, detail);
    }

    private void replayPersisted(ActiveRun active) {
        runs.eventsAfter(active.id, 0).forEach(active.sink::tryEmitNext);
    }

    private void restoreBudgetState(ActiveRun active) {
        List<RunEventEnvelope> events = runs.eventsAfter(active.id, 0);
        DurableUsageHistory.Snapshot usageHistory = DurableUsageHistory.from(events);
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
        usage.restore(active.id, usageHistory.usage(), usageHistory.cost(),
                usageHistory.modelCallIds());
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
        return event(request, type, 1, producer, payload, causationId);
    }

    private RunEventDraft event(
            RunRequest request, String type, int schemaVersion,
            String producer, JsonNode payload, String causationId) {
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
        if (stored != null && !stored.snapshot().state().terminal()) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("reason", "KERNEL_SHUTDOWN");
            committedEvents.transition(
                    active.id, Set.of(stored.snapshot().state()), RunState.PAUSED,
                    event(active.request, "core.run.paused", "framework.core", payload, null),
                    null, null);
        }
        active.completion.completeExceptionally(new IllegalStateException(
                "run detached during kernel shutdown and remains resumable: " + active.id));
        events.complete(active.id, active.sink);
        cleanupIfReady(active);
    }

    private final class EngineRunHandle implements RunHandle {
        private final ActiveRun active;

        private EngineRunHandle(ActiveRun active) { this.active = active; }

        @Override public RunId id() { return active.id; }

        @Override
        public Flux<RunEventEnvelope> events(long afterSequence) {
            Flux<RunEventEnvelope> durable = Flux.fromIterable(runs.eventsAfter(active.id, afterSequence));
            Flux<RunEventEnvelope> live = active.sink.asFlux()
                    .filter(event -> event.sequence() > afterSequence);
            return Flux.concat(durable, live)
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
        private final Object launchLock = new Object();
        private final AtomicBoolean executing = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean terminalDelivered = new AtomicBoolean();
        private final AtomicBoolean detached = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final Set<String> committedToolOutcomes = ConcurrentHashMap.newKeySet();
        private final Set<String> uncertainToolOutcomeCommits = ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicReference<RunOutcome> terminalOutcome =
                new java.util.concurrent.atomic.AtomicReference<>();
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
