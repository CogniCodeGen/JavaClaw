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
import com.javaclaw.framework.spi.ModelTaskGateway;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

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
    private static final Set<RunState> CANCELLABLE = Set.of(
            RunState.CREATED, RunState.RUNNING, RunState.WAITING_INPUT,
            RunState.WAITING_APPROVAL, RunState.WAITING_CHILD, RunState.WAITING_EVENT, RunState.PAUSED,
            RunState.RECOVERY_BLOCKED_MISSING_EXTENSION);

    private final AgentCompiler compiler;
    private final RunStore runs;
    private final ExecutionPlanStore plans;
    private final ReasoningGateway reasoning;
    private final Executor executor;
    private final ObjectMapper json;
    private final Clock clock;
    private final RunUsageLedger usage;
    private final TaskHarnessLifecycle taskHarness;
    private final RunRecoveryCoordinator recovery;
    private final com.javaclaw.framework.spi.InteractionEventSource interactionEvents;
    private final BiConsumer<com.javaclaw.framework.spi.ToolContext, RunHandle> interactionObserver;
    private final java.util.concurrent.ScheduledExecutorService waitTimers =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "interaction-wait-deadlines");
                thread.setDaemon(true); return thread;
            });
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
        runs.recoverClaims();
        recovery.recover(this::cancel, this::attachRecovered, activeRuns::remove);
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
        RunRequest effectiveRequest = plan.annotate(request);
        RunId openedAccount = null;
        boolean durableCreated = false;
        try {
            plans.save(plan.descriptor().id(), plan.descriptor().extensionGeneration(),
                    json.valueToTree(plan.descriptor()), plan.descriptor().checksum());
            RunId id = RunId.random();
            ActiveRun active = new ActiveRun(id, effectiveRequest, plan,
                    new RunControl(plan.descriptor().budget(), clock));
            recovery.inheritEffects(active.id, active.request, active.control);
            usage.open(id, plan.descriptor().budget(), effectiveRequest.scope(), RunUsageRecovery.budgetParent(effectiveRequest));
            openedAccount = id;
            ActiveRun collision = activeRuns.putIfAbsent(id, active);
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
                    ActiveRun current = activeRuns.get(openedAccount);
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
        synchronized (active.launchLock) {
            return resumeAttached(active, runId, command);
        }
    }

    /** Serialize generic resume with exact-challenge ANSWER admission on the same child. */
    private RunHandle resumeAttached(ActiveRun active, RunId runId, ResumeCommand command) {
        RunState current = get(runId).state();
        if (current.terminal()) return handle(runId);
        if (current != RunState.PAUSED && current != RunState.WAITING_INPUT && current != RunState.WAITING_APPROVAL
                && current != RunState.WAITING_CHILD && current != RunState.WAITING_EVENT)
            throw new IllegalStateException("run is not resumable: " + runId);
        if (PersistedRunDeadline.workflowCoordinator(active.request)
                && (current == RunState.PAUSED || current == RunState.WAITING_INPUT)) {
            active.control.restoreDeadline(PersistedRunDeadline.resolve(requireRun(runId),
                    active.plan.descriptor().budget(), runs.eventsAfter(runId, 0), clock.instant()));
        }
        if (active.control.expired()) {
            cancel(runId, active.control.cancellationReason().orElseGet(active.control::timeoutReason));
            return handle(runId);
        }
        RunHandle interaction = resumeInteractionWait(active, current, command);
        if (interaction != null) return interaction;
        if (!runs.claim(runId)) throw new IllegalStateException("another turn owns this thread");
        if (command.type().equals("input.continue")) {
            // Continuing a restored approval only reopens the current challenge. It is not a grant.
            if (active.pendingApproval != null) {
                if (current != RunState.PAUSED && current != RunState.WAITING_APPROVAL) {
                    throw new IllegalStateException("pending approval has no resumable approval state");
                }
                ObjectNode review = JsonNodeFactory.instance.objectNode();
                review.set("approval", active.pendingApproval.toJson());
                review.put("reason", "APPROVAL_REVIEW_REQUESTED");
                var reopened = runs.append(runId, Set.of(current), RunState.WAITING_APPROVAL,
                        event(active.request, "core.run.waiting_approval", "framework.core", review, null),
                        null, null);
                if (reopened.isEmpty()) throw new IllegalStateException("pending approval could not be reopened");
                active.sink.tryEmitNext(reopened.get());
                return new EngineRunHandle(active);
            }
            if (current == RunState.WAITING_APPROVAL) {
                throw new IllegalStateException("waiting approval has no pending challenge");
            }
            command = new ResumeCommand("input", command.payload());
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
        if (active.ready.isDone()) active.ready = new CompletableFuture<>();
        var event = runs.append(runId,
                Set.of(RunState.WAITING_INPUT, RunState.WAITING_APPROVAL, RunState.WAITING_CHILD,
                        RunState.WAITING_EVENT, RunState.PAUSED),
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

    /** 显式继续只重连宿主等待；未完成的原工具不进入新的推理或再次派发。 */
    private RunHandle resumeInteractionWait(ActiveRun active, RunState current, ResumeCommand command) {
        synchronized (active.launchLock) {
            List<RunEventEnvelope> events = runs.eventsAfter(active.id, 0);
            var pending = new RunStepQuery(runs).steps(active.id).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL && step.state() == AgentStep.State.RUNNING
                            && step.input() != null
                            && Set.of(InteractionExecutionPolicy.DELEGATE_TOOL, InteractionExecutionPolicy.WAIT_EVENT_TOOL)
                                .contains(step.input().path("tool").asText())).toList();
            if (pending.isEmpty()) {
                if (current == RunState.WAITING_CHILD || current == RunState.WAITING_EVENT)
                    throw new IllegalStateException("interaction wait has no unsettled reserved tool");
                return null;
            }
            RunEventEnvelope lastWait = events.stream().filter(event -> event.schemaVersion() == 1
                            && "framework.core".equals(event.producer())
                            && (Set.of("core.run.waiting_child", "core.run.waiting_event").contains(event.type())
                                || "core.tool.suspended".equals(event.type()))
                            && pending.size() == 1
                            && StepId.tool(active.id, interactionWaitOutput(event).path("invocationId").asText())
                                .equals(pending.getFirst().id())
                            && (!"core.tool.suspended".equals(event.type())
                                || (event.payload().path("tool").asText().equals(pending.getFirst().input().path("tool").asText())
                                    && event.payload().path("invocationId").asText()
                                        .equals(interactionWaitOutput(event).path("invocationId").asText()))))
                    .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
            JsonNode output = lastWait == null ? null : interactionWaitOutput(lastWait);
            if (output == null && pending.size() == 1
                    && InteractionExecutionPolicy.DELEGATE_TOOL.equals(pending.getFirst().input().path("tool").asText()))
                output = recoverCreatedInteractionChild(active, pending.getFirst());
            if (pending.size() != 1 || output == null || !output.isObject()
                    || !StepId.tool(active.id, output.path("invocationId").asText()).equals(pending.getFirst().id())) {
                throw new IllegalStateException("unsettled interaction tool has no recoverable host wait");
            }
            if (!"input.continue".equals(command.type())) throw new IllegalArgumentException(
                    "a suspended interaction requires explicit input.continue; use interaction controls for answers or changes");
            if (current != RunState.PAUSED && current != RunState.WAITING_CHILD && current != RunState.WAITING_EVENT)
                throw new IllegalStateException("interaction wait is not in a resumable state");
            JsonNode recoveredWait = output;
            RunEventEnvelope unfinishedControl = events.stream().filter(event -> event.schemaVersion() == 1
                    && "framework.core".equals(event.producer())
                    && "core.interaction.control_accepted".equals(event.type())
                    && event.payload().path("revision").asLong() == recoveredWait.path("revision").asLong()
                    && event.payload().path("childRunId").asText().equals(recoveredWait.path("childRunId").asText())
                    && !interactionControlApplied(events, event.payload().path("commandId").asText()))
                    .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
            if (unfinishedControl != null && "AMEND".equals(unfinishedControl.payload().path("type").asText()))
                return recoverInteractionAmend(active, current, output, unfinishedControl);
            if (unfinishedControl != null && "CANCEL".equals(unfinishedControl.payload().path("type").asText())) {
                cancel(active.id, new CancelReason("USER_REQUEST", "recovered interaction cancellation"));
                return handle(active.id);
            }
            if (!runs.claim(active.id)) throw new IllegalStateException("another turn owns this thread");
            RunState waiting = InteractionExecutionPolicy.DELEGATE_TOOL.equals(pending.getFirst().input().path("tool").asText())
                    ? RunState.WAITING_CHILD : RunState.WAITING_EVENT;
            output = output.deepCopy();
            if (waiting == RunState.WAITING_CHILD && output instanceof ObjectNode details) {
                RunId childId = new RunId(output.path("childRunId").asText());
                var challenge = InteractionAnswerChallenge.current(childId, get(childId).state(), runs.eventsAfter(childId, 0));
                details.put("needsAnswer", challenge.isPresent())
                        .put("childEventSequence", challenge.map(RunEventEnvelope::sequence).orElse(0L));
                details.remove("currentChallenge");
                challenge.ifPresent(event -> details.set("currentChallenge", event.payload().path("output")));
            }
            ObjectNode payload = JsonNodeFactory.instance.objectNode().put("reason", "EXPLICIT_WAIT_REATTACH");
            payload.set("output", output);
            var reopened = runs.append(active.id, Set.of(current), waiting,
                    event(active.request, waiting == RunState.WAITING_CHILD ? "core.run.waiting_child" : "core.run.waiting_event",
                            "framework.core", payload, null), output, null);
            if (reopened.isEmpty()) throw new IllegalStateException("interaction wait could not be reattached");
            active.sink.tryEmitNext(reopened.get());
            attachInteractionWait(active, waiting, output);
            if (waiting == RunState.WAITING_CHILD) {
                RunId childId = new RunId(output.path("childRunId").asText());
                if (unfinishedControl != null && "ANSWER".equals(unfinishedControl.payload().path("type").asText()))
                    applyInteractionAnswer(active, childId, unfinishedControl.payload());
                if (get(childId).state() == RunState.PAUSED
                        && InteractionAnswerChallenge.current(childId, RunState.PAUSED, runs.eventsAfter(childId, 0)).isEmpty())
                    resume(childId, new ResumeCommand("input.continue", JsonNodeFactory.instance.objectNode()));
            }
            return new EngineRunHandle(active);
        }
    }

    private static JsonNode interactionWaitOutput(RunEventEnvelope event) {
        return event.payload().path("core.tool.suspended".equals(event.type()) ? "wait" : "output");
    }

    /** child 创建与 suspended 之间的崩溃窗口只按宿主父步骤关联恢复，绝不重新派发 delegate。 */
    private JsonNode recoverCreatedInteractionChild(ActiveRun parent, AgentStep pending) {
        String invocation = pending.input().path("invocationId").asText();
        if (invocation.isBlank() || !StepId.tool(parent.id, invocation).equals(pending.id()))
            throw new IllegalStateException("reserved delegate invocation identity is unavailable");
        var candidates = runs.childRuns(parent.id).stream().filter(child ->
                parent.id.equals(child.request().linkage().parentRunId())
                        && InteractionExecutionPolicy.isInteraction(child.request())
                        && InteractionDelegateCoordinator.childScope(parent.request.scope()).equals(child.request().scope())
                        && pending.id().value().equals(child.request().attributes()
                            .getOrDefault("framework.parentStepId", JsonNodeFactory.instance.nullNode()).asText()))
                .toList();
        if (candidates.size() != 1)
            throw new IllegalStateException("reserved delegate has no unique recoverable child");
        StoredRun child = candidates.getFirst();
        InteractionRequestGuard.validate(runs, json, child.request());
        JsonNode task = child.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
        if (task == null || task.path("revision").asLong() != 1
                || !invocation.equals(task.path("taskId").asText())
                || !"interaction-executor".equals(child.request().source().id()))
            throw new SecurityException("created interaction child revision or source mismatch");
        return JsonNodeFactory.instance.objectNode().put("kind", "interaction.waiting_child")
                .put("childRunId", child.snapshot().id().value()).put("invocationId", invocation)
                .put("taskId", task.path("taskId").asText()).put("revision", 1).put("afterSequence", 0);
    }

    private static boolean interactionControlApplied(List<RunEventEnvelope> events, String commandId) {
        return events.stream().anyMatch(event -> event.schemaVersion() == 1
                && "framework.core".equals(event.producer())
                && "core.interaction.control_applied".equals(event.type())
                && commandId.equals(event.payload().path("commandId").asText()));
    }

    /** 子任务的持久化 resumed 是答案投递边界；父 applied 丢失时只补确认，不再次投递。 */
    private boolean applyInteractionAnswer(ActiveRun parent, RunId childId, JsonNode command) {
        parent.control.throwIfCancelled();
        ActiveRun childActive = activeRuns.get(childId);
        // A terminal child has no live lock after restart, but its durable delivered marker
        // still permits an exact ACK. An unattached nonterminal child never receives input.
        synchronized (childActive == null ? parent.launchLock : childActive.launchLock) {
            StoredRun child = requireReadableRun(childId);
            try { requireInteractionAnswerIdentity(parent, child, command); }
            catch (IllegalArgumentException | SecurityException invalid) {
                return rejectInteractionAnswer(parent, childId, command, "STALE_INTERACTION_ANSWER");
            }
            long sequence = command.path("childEventSequence").asLong(0);
            if (!command.path("childEventSequence").isIntegralNumber()
                    || !command.path("childEventSequence").canConvertToLong() || sequence < 1)
                return rejectInteractionAnswer(parent, childId, command, "INTERACTION_ANSWER_CHALLENGE_REQUIRED");
            String commandId = command.path("commandId").asText();
            var delivered = runs.eventsAfter(childId, 0).stream().filter(event -> event.schemaVersion() == 1
                    && event.runId().equals(childId.value()) && event.producer().equals("framework.core")
                    && event.type().equals("core.run.resumed")
                    && commandId.equals(event.payload().path("command").path("interactionCommandId").asText())).toList();
            if (!delivered.isEmpty()) {
                if (delivered.size() != 1 || !delivered.getFirst().payload().path("command")
                        .path("interactionChildEventSequence").isIntegralNumber()
                        || delivered.getFirst().payload().path("command").path("interactionChildEventSequence").asLong() != sequence
                        || delivered.getFirst().payload().path("command").path("interactionRevision").asLong()
                            != command.path("revision").asLong()
                        || !delivered.getFirst().payload().path("command").path("interactionTaskId").asText()
                            .equals(command.path("taskId").asText()))
                    return rejectInteractionAnswer(parent, childId, command, "INTERACTION_ANSWER_DELIVERY_MISMATCH");
                recordInteractionControlApplied(parent, command);
                return true;
            }
            if (child.snapshot().state().terminal()) {
                recordInteractionControlApplied(parent, ((ObjectNode) command).deepCopy()
                        .put("delivery", "CHILD_ALREADY_SETTLED"));
                return true;
            }
            if (childActive == null)
                return rejectInteractionAnswer(parent, childId, command, "INTERACTION_ANSWER_CHILD_UNAVAILABLE");
            if (InteractionAnswerChallenge.current(childId, child.snapshot().state(), runs.eventsAfter(childId, 0))
                    .map(RunEventEnvelope::sequence).orElse(0L) != sequence)
                return rejectInteractionAnswer(parent, childId, command, "STALE_INTERACTION_ANSWER_CHALLENGE");
            resume(childId, new ResumeCommand(child.snapshot().state() == RunState.PAUSED ? "input.continue" : "input",
                    JsonNodeFactory.instance.objectNode().put("text", command.path("text").asText())
                            .put("interactionCommandId", commandId).put("interactionChildEventSequence", sequence)
                            .put("interactionRevision", command.path("revision").asLong())
                            .put("interactionTaskId", command.path("taskId").asText())));
            recordInteractionControlApplied(parent, command);
            return true;
        }
    }

    /** The caller holds parent then child launch locks through validation and durable resume. */
    private void requireInteractionAnswerIdentity(ActiveRun parent, StoredRun child, JsonNode command) {
        JsonNode wait = parent.interactionWait;
        if (!currentInteractionAnswerChild(parent, child)
                || !command.path("childRunId").asText().equals(child.snapshot().id().value())
                || !command.path("revision").isIntegralNumber() || command.path("revision").asLong() < 1
                || command.path("revision").asLong() != wait.path("revision").asLong()
                || command.path("taskId").asText().isBlank()
                || !command.path("taskId").asText().equals(wait.path("taskId").asText()))
            throw new SecurityException("interaction answer child, task or revision mismatch");
    }

    /** UI recovery is derived independently of any missing or obsolete fields in the old command. */
    private boolean currentInteractionAnswerChild(ActiveRun parent, StoredRun child) {
        JsonNode wait = parent.interactionWait;
        JsonNode task = child.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
        return wait != null && parent.id.equals(child.request().linkage().parentRunId())
                && InteractionExecutionPolicy.isInteraction(child.request())
                && InteractionDelegateCoordinator.childScope(parent.request.scope()).equals(child.request().scope())
                && runs.readable(child.request().scope()) && task != null
                && wait.path("childRunId").asText().equals(child.snapshot().id().value())
                && wait.path("revision").asLong() > 0
                && wait.path("revision").asLong() == task.path("revision").asLong()
                && !wait.path("taskId").asText().isBlank()
                && wait.path("taskId").asText().equals(task.path("taskId").asText());
    }

    /** Settle an obsolete accepted command without applying it to the current question. */
    private boolean rejectInteractionAnswer(ActiveRun parent, RunId childId, JsonNode command, String errorCode) {
        StoredRun child = requireReadableRun(childId);
        var challenge = currentInteractionAnswerChild(parent, child)
                ? InteractionAnswerChallenge.current(childId, child.snapshot().state(), runs.eventsAfter(childId, 0))
                : java.util.Optional.<RunEventEnvelope>empty();
        ObjectNode rejected = ((ObjectNode) command).deepCopy().put("delivery", "REJECTED").put("errorCode", errorCode)
                .put("needsAnswer", challenge.isPresent())
                .put("currentChallengeSequence", challenge.map(RunEventEnvelope::sequence).orElse(0L));
        challenge.ifPresent(event -> rejected.set("currentChallenge", event.payload().path("output")));
        recordInteractionControlApplied(parent, rejected);
        return false;
    }

    private RunHandle recoverInteractionAmend(ActiveRun parent, RunState current, JsonNode wait,
            RunEventEnvelope accepted) {
        JsonNode data = accepted.payload();
        InteractionControlCommand command = new InteractionControlCommand(data.path("commandId").asText(),
                data.path("revision").asLong(), InteractionControlCommand.Type.AMEND, data.path("text").asText());
        RunId childId = new RunId(wait.path("childRunId").asText());
        StoredRun child = requireReadableRun(childId);
        if (!parent.id.equals(child.request().linkage().parentRunId())
                || !InteractionExecutionPolicy.isInteraction(child.request())
                || !childId.value().equals(data.path("childRunId").asText())
                || wait.path("revision").asLong() != command.expectedRevision())
            throw new SecurityException("amendment does not match the reserved interaction wait");
        parent.control.throwIfCancelled();
        if (!runs.claim(parent.id)) throw new IllegalStateException("another turn owns this thread");
        disposeInteractionWait(parent);
        ObjectNode payload = ((ObjectNode) data).deepCopy().put("reason", "EXPLICIT_AMENDMENT_RECOVERY");
        var resumed = runs.append(parent.id, Set.of(current), RunState.RUNNING,
                event(parent.request, "core.interaction.amending", "framework.core", payload, null), null, null);
        if (resumed.isEmpty()) throw new IllegalStateException("interaction amendment cannot be resumed");
        parent.sink.tryEmitNext(resumed.get());
        scheduleInteractionAmend(parent, child, wait, command);
        return new EngineRunHandle(parent);
    }

    private void scheduleInteractionAmend(ActiveRun parent, StoredRun child, JsonNode wait,
            InteractionControlCommand command) {
        parent.waitDeadline = waitTimers.schedule(() -> {
            if (!get(parent.id).state().terminal()) cancel(parent.id, parent.control.timeoutReason());
        }, Math.max(1, parent.control.remaining().toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        ActiveRun old = activeRuns.get(child.snapshot().id());
        cancel(child.snapshot().id(), new CancelReason("TASK_AMENDED", command.commandId()));
        CompletionStage<Void> settled = old == null ? CompletableFuture.completedFuture(null) : old.physicallySettled;
        settled.whenCompleteAsync((ignored, failure) -> {
            try { amendInteraction(parent, child, wait, command); }
            catch (Throwable failed) { fail(parent, failed); }
        }, executor);
    }

    @Override
    public boolean cancel(RunId runId, CancelReason reason) {
        ActiveRun active = activeRuns.get(runId);
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
        ensureOpen(); requireReadableRun(parentId);
        ActiveRun parent = activeRuns.get(parentId);
        if (parent == null) throw new IllegalStateException("interaction parent is not attached");
        synchronized (parent.launchLock) {
            RunEventEnvelope duplicate = runs.eventsAfter(parentId, 0).stream().filter(event ->
                    event.type().equals("core.interaction.control_accepted")
                            && event.producer().equals("framework.core")
                            && event.payload().path("commandId").asText().equals(command.commandId()))
                    .findFirst().orElse(null);
            if (duplicate != null) {
                if (duplicate.payload().path("revision").asLong() != command.expectedRevision()
                        || !duplicate.payload().path("type").asText().equals(command.type().name())
                        || !duplicate.payload().path("text").asText().equals(command.text())
                        || duplicate.payload().path("childEventSequence").asLong(0) != command.childEventSequence())
                    throw new IllegalArgumentException("interaction command id was already used for another command");
                if (runs.eventsAfter(parentId, 0).stream().anyMatch(event -> event.schemaVersion() == 1
                        && event.producer().equals("framework.core") && event.type().equals("core.interaction.control_applied")
                        && event.payload().path("commandId").asText().equals(command.commandId())
                        && event.payload().path("delivery").asText().equals("REJECTED")))
                    throw new IllegalArgumentException("STALE_INTERACTION_ANSWER_CHALLENGE");
                return handle(parentId);
            }
            JsonNode wait = parent.interactionWait;
            if (wait == null || get(parentId).state() != RunState.WAITING_CHILD)
                throw new IllegalStateException("parent is not waiting for an interaction task");
            if (wait.path("revision").asLong() != command.expectedRevision())
                throw new IllegalArgumentException("STALE_INTERACTION_REVISION");
            RunId childId = new RunId(wait.path("childRunId").asText());
            StoredRun child = requireRun(childId);
            if (!parentId.equals(child.request().linkage().parentRunId()))
                throw new SecurityException("interaction child owner mismatch");
            Object controlLock = parent.launchLock;
            if (command.type() == InteractionControlCommand.Type.ANSWER) {
                ActiveRun childActive = activeRuns.get(childId);
                if (childActive == null) throw new IllegalStateException("interaction answer child is not attached");
                controlLock = childActive.launchLock;
            }
            synchronized (controlLock) {
                if (command.type() == InteractionControlCommand.Type.ANSWER) child = requireReadableRun(childId);
                ObjectNode accepted = JsonNodeFactory.instance.objectNode()
                        .put("commandId", command.commandId()).put("revision", command.expectedRevision())
                        .put("childRunId", childId.value()).put("type", command.type().name())
                        .put("text", command.text()).put("taskId", wait.path("taskId").asText())
                        .put("childEventSequence", command.childEventSequence());
                if (command.type() == InteractionControlCommand.Type.ANSWER) {
                    requireInteractionAnswerIdentity(parent, child, accepted);
                    if (command.childEventSequence() < 1)
                        throw new IllegalArgumentException("INTERACTION_ANSWER_CHALLENGE_REQUIRED");
                    if (InteractionAnswerChallenge.current(childId, child.snapshot().state(), runs.eventsAfter(childId, 0))
                            .map(RunEventEnvelope::sequence).orElse(0L) != command.childEventSequence())
                        throw new IllegalArgumentException("STALE_INTERACTION_ANSWER_CHALLENGE");
                }
                var acceptedEvent = runs.append(parentId, Set.of(RunState.WAITING_CHILD), RunState.WAITING_CHILD,
                        event(parent.request, "core.interaction.control_accepted", "framework.core", accepted, null), null, null)
                        .orElseThrow(() -> new IllegalStateException("interaction command could not be accepted"));
                parent.sink.tryEmitNext(acceptedEvent);
                parent.control.throwIfCancelled();
                switch (command.type()) {
                    case ANSWER -> {
                        if (!applyInteractionAnswer(parent, childId, accepted))
                            throw new IllegalArgumentException("STALE_INTERACTION_ANSWER_CHALLENGE");
                    }
                    case CANCEL -> {
                        cancel(parentId, new CancelReason("USER_REQUEST", "interaction task cancelled by user"));
                    }
                    case AMEND -> {
                        var amending = runs.append(parentId, Set.of(RunState.WAITING_CHILD), RunState.RUNNING,
                                event(parent.request, "core.interaction.amending", "framework.core", accepted, null), null, null)
                                .orElseThrow(() -> new IllegalStateException("interaction amendment could not be activated"));
                        disposeInteractionWait(parent); // late completion of the old revision cannot win
                        parent.sink.tryEmitNext(amending);
                        scheduleInteractionAmend(parent, child, wait, command);
                    }
                }
                return handle(parentId);
            }
        }
    }

    private void amendInteraction(ActiveRun parent, StoredRun old, JsonNode previousWait,
            InteractionControlCommand command) throws Exception {
        parent.control.throwIfCancelled();
        InteractionTask previous = json.treeToValue(old.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE), InteractionTask.class);
        String goal = previous.goal() + "\n用户修订：" + command.text();
        String idempotency = "interaction-amend:" + parent.id.value() + ":" + command.commandId();
        StoredRun existing = runs.findByIdempotencyKey(old.request().scope(), idempotency).orElse(null);
        if (existing != null) {
            InteractionTask task = json.treeToValue(existing.request().attributes()
                    .get(InteractionExecutionPolicy.TASK_ATTRIBUTE), InteractionTask.class);
            if (!parent.id.equals(existing.request().linkage().parentRunId())
                    || !existing.request().scope().equals(old.request().scope())
                    || !existing.request().source().equals(old.request().source())
                    || !task.taskId().equals(previous.taskId()) || task.revision() != previous.revision() + 1
                    || !task.goal().equals(goal)) throw new SecurityException("replacement interaction identity mismatch");
            InteractionRequestGuard.validate(runs, json, existing.request());
            publishAmendedWait(parent, previousWait, command, task, handle(existing.snapshot().id()));
            if (get(existing.snapshot().id()).state() == RunState.PAUSED) resume(existing.snapshot().id(),
                    new ResumeCommand("input.continue", JsonNodeFactory.instance.objectNode()));
            return;
        }
        TaskContractV3 contract = frozenAmendment(parent, goal, previous.revision() + 1);
        if (contract == null) contract = taskHarness.amendInteraction(parent.id, parent.request, goal,
                previous.revision() + 1, parent.control, parent.sink::tryEmitNext);
        InteractionTask task = new InteractionTask(1, previous.taskId(), previous.revision() + 1, previous.mode(),
                goal, previous.necessaryData(), previous.constraints(), contract.criteria().stream().map(TaskCriterionV3::id).toList());
        var attributes = new java.util.LinkedHashMap<>(old.request().attributes());
        attributes.put(InteractionExecutionPolicy.TASK_ATTRIBUTE, json.valueToTree(task));
        attributes.put("framework.taskContract", json.valueToTree(contract));
        var remaining = java.time.Duration.between(clock.instant(), parent.control.deadline());
        RunBudget budget = usage.remainingBudget(parent.id).restrictWith(old.request().budget())
                .restrictWith(new RunBudget(remaining, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE,
                        RunBudget.UNBOUNDED.maxCost()));
        RunRequest replacement = new RunRequest(old.request().agent(), old.request().profile(), old.request().source(),
                old.request().scope(), List.of(InputBlock.text(json.valueToTree(task).toString())), old.request().linkage(),
                old.request().permissionCeiling(), budget, idempotency, attributes);
        RunHandle child = start(replacement);
        publishAmendedWait(parent, previousWait, command, task, child);
    }

    private TaskContractV3 frozenAmendment(ActiveRun parent, String goal, long revision) {
        var latest = TaskResultEvaluator.latestContractV3(runs.eventsAfter(parent.id, 0), json).orElse(null);
        if (latest == null || !latest.reliable() || !latest.applicable()
                || !latest.originalRequest().endsWith("交互任务修订：" + goal)) return null;
        var criteria = latest.criteria().stream().filter(criterion -> criterion.capabilityId().startsWith("browser.")
                || criterion.capabilityId().startsWith("desktop.")).toList();
        if (criteria.isEmpty() || criteria.stream().anyMatch(criterion -> !criterion.id().startsWith("ir" + revision + ".")))
            throw new IllegalStateException("persisted amendment contract revision mismatch");
        return new TaskContractV3(3, goal, criteria, true, true, "host.interaction", List.of(), List.of(),
                latest.desktopObservationPolicy(), latest.intentStatus());
    }

    private void publishAmendedWait(ActiveRun parent, JsonNode previousWait, InteractionControlCommand command,
            InteractionTask task, RunHandle child) {
        parent.control.throwIfCancelled();
        ObjectNode wait = ((ObjectNode) previousWait).deepCopy().put("childRunId", child.id().value())
                .put("revision", task.revision()).put("afterSequence", 0);
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("revision", task.revision())
                .put("childRunId", child.id().value()).put("commandId", command.commandId());
        payload.set("output", wait);
        ObjectNode applied = JsonNodeFactory.instance.objectNode()
                .put("commandId", command.commandId()).put("revision", task.revision())
                .put("childRunId", child.id().value()).put("type", command.type().name());
        var updated = runs.appendBatch(parent.id, Set.of(RunState.RUNNING), RunState.WAITING_CHILD, List.of(
                event(parent.request, "core.run.waiting_child", "framework.core", payload, null),
                event(parent.request, "core.interaction.control_applied", "framework.core", applied, null)));
        if (updated.isEmpty()) { cancel(child.id(), new CancelReason("PARENT_CANCELLED", parent.id.value())); return; }
        updated.get().forEach(parent.sink::tryEmitNext);
        attachInteractionWait(parent, RunState.WAITING_CHILD, wait);
    }

    private void recordInteractionControlApplied(ActiveRun parent, JsonNode command) {
        RunState state = get(parent.id).state();
        runs.append(parent.id, Set.of(state), state,
                event(parent.request, "core.interaction.control_applied", "framework.core", command, null), null, null)
                .ifPresent(parent.sink::tryEmitNext);
    }

    private boolean cancelRun(RunId runId, CancelReason reason, ActiveRun active) {
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
        ActiveRun active = activeRuns.get(runId);
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
        ActiveRun active = activeRuns.get(runId);
        if (active == null) throw new IllegalStateException("tool owner turn is not attached: " + runId);
        active.control.recordToolCall(fingerprint);
        usage.recordToolCall(runId, invocationId);
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
                        active.id, active.plan, resolvedTaskRequest(active), resume, active.control,
                        new ReasoningEventSink() {
                            @Override public void emit(String type, int version,
                                                       String producer, JsonNode payload) {
                                appendReasoningEvent(active, type, version, producer, payload);
                            }

                            @Override public void toolStarted(JsonNode stepStarted,
                                                               JsonNode toolStarted) {
                                appendToolStartedEvents(active, stepStarted, toolStarted);
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

    private RunRequest resolvedTaskRequest(ActiveRun active) {
        TaskContractV3 contract = TaskResultEvaluator.latestContractV3(
                runs.eventsAfter(active.id, 0), json).orElse(null);
        // Replace any caller-supplied value with the actual host-persisted contract.
        RunRequest request = active.request.withAttribute(TaskAcceptanceContext.ATTRIBUTE,
                contract == null ? JsonNodeFactory.instance.objectNode() : json.valueToTree(contract));
        if (contract == null || contract.originalRequest().isBlank()) return request;
        JsonNode resolved = JsonNodeFactory.instance.textNode(contract.originalRequest());
        request = request.withAttribute(TaskContractCompiler.RESOLVED_REQUEST_ATTRIBUTE, resolved);
        JsonNode explicit = active.request.attributes().get(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE);
        if (explicit != null && explicit.isTextual() && !explicit.asText().isBlank()) {
            // A later human clarification can restrict or cancel the formerly explicit goal.
            request = request.withAttribute(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE, resolved);
        }
        RunRequest resolvedRequest = request;
        return InteractionHumanAmendment.latest(runs, json, active.id)
                .map(revision -> InteractionHumanAmendment.projectHumanInput(resolvedRequest, revision))
                .orElse(resolvedRequest);
    }

    private static boolean requiresContractInput(TaskContractV3 contract, RunRequest request) {
        return contract != null && !contract.reliable()
                && contract.intentStatus() == TaskContractV3.IntentStatus.NEEDS_HUMAN
                && Set.of("model", "model-repair").contains(contract.source())
                && !contract.unresolvedInputs().isEmpty()
                && !request.attributes().containsKey(TaskContractCompiler.ATTRIBUTE);
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
        if ("core.tool.started".equals(type) && "framework.core".equals(producer))
            usage.recordToolCall(active.id, payload.path("invocationId").asText());
        JsonNode encoded = active.plan.encodeEvent(type, version, payload);
        var event = runs.append(active.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                event(active.request, type, version, producer, encoded, null), null, null);
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
            if (TaskResultEvaluator.isBusinessToolStart(type, producer, payload))
                taskHarness.reviseQuestionContract(active.id, active.request, active.sink::tryEmitNext);
            if (type.equals("core.tool.receipt") && producer.equals("framework.core")
                    && payload.path("tool").asText("").equals("desktop_session_observe")
                    && payload.path("status").asText("").equals("OBSERVED")) {
                taskHarness.reconcileCheckpoints(active.id, active.request,
                        active.control, active.sink::tryEmitNext);
            }
        });
    }

    /** One durable reservation and step start, committed before the host tool is invoked. */
    private void appendToolStartedEvents(ActiveRun active, JsonNode stepStarted,
                                         JsonNode toolStarted) {
        active.control.throwIfCancelled();
        usage.recordToolCall(active.id, toolStarted.path("invocationId").asText());
        JsonNode step = active.plan.encodeEvent("core.step.started", 1, stepStarted);
        JsonNode tool = active.plan.encodeEvent("core.tool.started", 1, toolStarted);
        var appended = runs.appendBatch(active.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                List.of(event(active.request, "core.step.started", 1, "framework.core", step,
                                stepStarted.path("causation").asText(null)),
                        event(active.request, "core.tool.started", 1, "framework.core", tool,
                                toolStarted.path("causation").asText(null))))
                .orElseThrow(() -> new IllegalStateException(
                        "tool start reservation could not be persisted: " + active.id));
        for (RunEventEnvelope value : appended) active.sink.tryEmitNext(value);
        String fingerprint = toolStarted.path("fingerprint").asText("");
        ApprovedToolInvocation approved = active.pendingApprovedInvocation;
        if (approved != null && approved.challenge().fingerprint().equals(fingerprint)) {
            active.pendingApprovedInvocation = null;
        }
        if (TaskResultEvaluator.isBusinessToolStart("core.tool.started", "framework.core", toolStarted))
            taskHarness.reviseQuestionContract(active.id, active.request, active.sink::tryEmitNext);
    }

    private void finishTurn(ActiveRun active, ReasoningResult result) {
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

    private void attachInteractionWait(ActiveRun active, RunState state, JsonNode supplied) {
        disposeInteractionWait(active);
        if (supplied == null || supplied.path("invocationId").asText().isBlank()) {
            fail(active, new IllegalArgumentException("interaction wait has no reserved invocation"));
            return;
        }
        JsonNode wait = supplied.deepCopy();
        active.interactionWait = wait;
        long remaining = Math.max(1, java.time.Duration.between(clock.instant(), active.control.deadline()).toMillis());
        active.waitDeadline = waitTimers.schedule(() -> {
            if (active.interactionWait == wait) cancel(active.id, active.control.timeoutReason());
        }, remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (state == RunState.WAITING_EVENT) {
            awaitInteractionEvent(active, wait);
            return;
        }
        RunId childId = new RunId(wait.path("childRunId").asText());
        StoredRun child = requireRun(childId);
        if (!active.id.equals(child.request().linkage().parentRunId())
                || !InteractionExecutionPolicy.isInteraction(child.request())) {
            cancel(active.id, new CancelReason("INVALID_INTERACTION_WAIT", "child ownership mismatch"));
            return;
        }
        RunHandle childHandle = handle(childId);
        interactionObserver.accept(new com.javaclaw.framework.spi.ToolContext(active.id, active.request.scope(),
                active.plan.descriptor().permissions(), active.control, active.control.deadline(), active.request), childHandle);
        var subscription = reactor.core.Disposables.swap();
        active.interactionSubscription = subscription;
        long deliveredCursor = runs.eventsAfter(active.id, 0).stream()
                .filter(event -> event.producer().equals("framework.core")
                        && Set.of("core.interaction.child_waiting_input", "core.interaction.child_waiting_approval")
                            .contains(event.type())
                        && childId.value().equals(event.payload().path("childRunId").asText())
                        && wait.path("revision").asLong() == event.payload().path("revision").asLong())
                .mapToLong(event -> event.payload().path("childEventSequence").asLong()).max()
                .orElse(wait.path("afterSequence").asLong(0));
        subscription.update(childHandle.events(deliveredCursor)
                .filter(childEvent -> childEvent.type().equals("core.run.waiting_input")
                        || childEvent.type().equals("core.run.waiting_approval"))
                // Child events can be emitted while holding the child's launch lock. Leave
                // that thread before taking the parent lock used by ANSWER/AMEND/CANCEL.
                .publishOn(reactor.core.scheduler.Schedulers.fromExecutor(executor))
                .subscribe(childEvent -> {
                    synchronized (active.launchLock) {
                        if (active.interactionWait != wait || active.terminated.get() || active.detached.get()
                                || active.control.cancelled() || get(active.id).state() != RunState.WAITING_CHILD
                                || childEvent.schemaVersion() != 1 || !childEvent.producer().equals("framework.core")
                                || !childEvent.runId().equals(childId.value())) return;
                        StoredRun currentChild = requireRun(childId);
                        JsonNode childTask = currentChild.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
                        if (!active.id.equals(currentChild.request().linkage().parentRunId()) || childTask == null
                                || !childTask.path("taskId").asText().equals(wait.path("taskId").asText())
                                || childTask.path("revision").asLong() != wait.path("revision").asLong()) return;
                        boolean input = childEvent.type().equals("core.run.waiting_input");
                        RunState childState = currentChild.snapshot().state();
                        if (input && childState != RunState.WAITING_INPUT
                                || !input && childState != RunState.WAITING_APPROVAL) return;
                        long latestChallenge = runs.eventsAfter(childId, 0).stream()
                                .filter(event -> event.schemaVersion() == 1 && event.producer().equals("framework.core")
                                        && event.runId().equals(childId.value()) && event.type().equals(childEvent.type()))
                                .mapToLong(RunEventEnvelope::sequence).max().orElse(0);
                        if (childEvent.sequence() != latestChallenge) return;
                        String forwardedType = input ? "core.interaction.child_waiting_input"
                                : "core.interaction.child_waiting_approval";
                        boolean forwarded = runs.eventsAfter(active.id, 0).stream().anyMatch(event ->
                                event.schemaVersion() == 1 && event.producer().equals("framework.core")
                                        && event.runId().equals(active.id.value()) && event.type().equals(forwardedType)
                                        && childId.value().equals(event.payload().path("childRunId").asText())
                                        && wait.path("revision").asLong() == event.payload().path("revision").asLong()
                                        && childEvent.sequence() == event.payload().path("childEventSequence").asLong());
                        if (forwarded) return;
                        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                                .put("childRunId", childId.value()).put("taskId", wait.path("taskId").asText())
                                .put("revision", wait.path("revision").asLong()).put("childEventSequence", childEvent.sequence());
                        payload.set("output", childEvent.payload().path("output"));
                        payload.set("approval", childEvent.payload().path("approval"));
                        runs.append(active.id, Set.of(RunState.WAITING_CHILD), RunState.WAITING_CHILD,
                                event(active.request, forwardedType, "framework.core", payload, null), null, null)
                                .ifPresent(active.sink::tryEmitNext);
                    }
                }, failure -> cancel(childId, new CancelReason("CHILD_EVENT_STREAM_FAILED", failure.toString()))));
        childHandle.completion().whenCompleteAsync((outcome, failure) -> {
            if (active.interactionWait != wait || active.control.cancelled()) return;
            try {
                StoredRun terminal = requireRun(childId);
                if (!terminal.snapshot().state().terminal()) return; // shutdown/recovery is never a business completion
                deliverInteractionWait(active, state, wait,
                        json.valueToTree(InteractionResultProjector.project(runs, json, terminal)));
            } catch (Throwable deliveryFailure) {
                synchronized (active.launchLock) {
                    if (active.interactionWait == wait && !active.terminated.get() && !active.detached.get()
                            && get(active.id).state() == state) fail(active, deliveryFailure);
                }
            }
        }, executor);
    }

    /** 有界等待到期交还模型；持续监听的订阅分片到期仍由宿主续订。 */
    private void awaitInteractionEvent(ActiveRun active, JsonNode wait) {
        synchronized (active.launchLock) {
            if (!interactionEventWaitActive(active, wait)) return;
            if (active.interactionEventAwait != null) return;
            var renewal = active.waitRenewal; active.waitRenewal = null;
            if (renewal != null) renewal.cancel(false);
            long started = System.nanoTime();
            try {
                InteractionEventWaitMode waitMode = InteractionEventWaitMode.forStoredWait(wait);
                long slice = interactionEventWaitSlice(active, wait, waitMode);
                ObjectNode lease = (ObjectNode) wait.deepCopy();
                lease.put("timeoutMillis", slice); // Never change the durable baseline or reserved invocation.
                if (waitMode == InteractionEventWaitMode.BOUNDED)
                    scheduleBoundedInteractionTimeout(active, wait, slice);
                if (slice == 0) return; // 已过期的有界等待不再订阅期限外的新事件。
                var awaited = interactionEvents.await(active.request, lease, active.control).toCompletableFuture();
                active.interactionEventAwait = awaited;
                awaited.whenCompleteAsync((output, failure) -> {
                    synchronized (active.launchLock) {
                        if (active.interactionEventAwait != awaited || !interactionEventWaitActive(active, wait)) return;
                        active.interactionEventAwait = null;
                        try {
                            if (failure != null) {
                                deliverInteractionWait(active, RunState.WAITING_EVENT, wait,
                                        JsonNodeFactory.instance.objectNode().put("event", "EVENT_SOURCE_FAILED")
                                                .put("errorCode", "EVENT_SOURCE_UNAVAILABLE"));
                            } else if (output != null && "WAIT_TIMEOUT".equals(output.path("event").asText())) {
                                validateInteractionTimeout(wait, output, slice);
                                if (waitMode == InteractionEventWaitMode.UNTIL_CHANGE) {
                                    // 提前返回的源超时不能造成忙循环，续订仍使用原基线与原调用。
                                    long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                                    long delay = Math.max(1, Math.max(1_000, slice) - elapsed);
                                    active.waitRenewal = waitTimers.schedule(() -> awaitInteractionEvent(active, wait),
                                            delay, java.util.concurrent.TimeUnit.MILLISECONDS);
                                }
                                // BOUNDED 的总时限由宿主计时器负责，源超时不能提前恢复或重置它。
                            } else deliverInteractionWait(active, RunState.WAITING_EVENT, wait, output);
                        } catch (Throwable deliveryFailure) { fail(active, deliveryFailure); }
                    }
                }, executor);
            } catch (Throwable deliveryFailure) { fail(active, deliveryFailure); }
        }
    }

    private long interactionEventWaitSlice(ActiveRun active, JsonNode wait, InteractionEventWaitMode waitMode) {
        long remaining = Math.max(1, java.time.Duration.between(clock.instant(), active.control.deadline()).toMillis());
        if (waitMode == InteractionEventWaitMode.BOUNDED && wait.has("waitDeadline")) {
            // 恢复同一次等待不能重置其总时限；旧上下文仍受原 Run 截止时间约束。
            Instant deadline = Instant.parse(wait.path("waitDeadline").asText());
            remaining = Math.min(remaining, Math.max(0, java.time.Duration.between(clock.instant(), deadline).toMillis()));
        }
        return Math.min(remaining, Math.min(30_000, Math.max(1, wait.path("timeoutMillis").asLong(30_000))));
    }

    private void scheduleBoundedInteractionTimeout(ActiveRun active, JsonNode wait, long slice) {
        ObjectNode timedOut = JsonNodeFactory.instance.objectNode().put("event", "WAIT_TIMEOUT")
                .put("sessionId", wait.path("sessionId").asText()).put("timeoutMillis", slice)
                .put("afterCapturedAtMillis", wait.path("afterCapturedAtMillis").asLong())
                .put("waitMode", InteractionEventWaitMode.BOUNDED.name())
                .put("inputAuthority", false).put("acceptanceEvidence", false)
                .put("message", "No desktop change arrived within the bounded wait. Observe fresh state and "
                        + "continue the task; this timeout is not evidence that its goal was achieved.");
        active.waitRenewal = waitTimers.schedule(() -> completeBoundedInteractionTimeout(active, wait, timedOut),
                slice, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void completeBoundedInteractionTimeout(ActiveRun active, JsonNode wait, JsonNode output) {
        synchronized (active.launchLock) {
            if (!interactionEventWaitActive(active, wait)) return;
            try {
                JsonNode result = output;
                var awaited = active.interactionEventAwait;
                if (awaited != null && awaited.isDone()) {
                    // 真实事件可能已到达，只是其异步回调仍在排队；不能把它覆盖为超时。
                    try {
                        JsonNode received = awaited.join(); // isDone 已确认，此处不阻塞计时器。
                        if (received != null && "WAIT_TIMEOUT".equals(received.path("event").asText()))
                            validateInteractionTimeout(wait, received, output.path("timeoutMillis").asLong());
                        else result = received;
                    } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failure) {
                        result = JsonNodeFactory.instance.objectNode().put("event", "EVENT_SOURCE_FAILED")
                                .put("errorCode", "EVENT_SOURCE_UNAVAILABLE");
                    }
                }
                active.waitRenewal = null;
                deliverInteractionWait(active, RunState.WAITING_EVENT, wait, result);
            } catch (Throwable deliveryFailure) {
                fail(active, deliveryFailure);
            }
        }
    }

    private static void validateInteractionTimeout(JsonNode wait, JsonNode output, long slice) {
        if (!output.path("sessionId").asText().equals(wait.path("sessionId").asText())
                || !output.path("timeoutMillis").isIntegralNumber()
                || output.path("timeoutMillis").longValue() != slice)
            throw new IllegalStateException("event source returned an invalid subscription timeout");
    }

    private boolean interactionEventWaitActive(ActiveRun active, JsonNode wait) {
        if (active.interactionWait != wait || active.terminated.get() || active.detached.get()) return false;
        if (active.control.cancelled() || !clock.instant().isBefore(active.control.deadline())) {
            cancel(active.id, active.control.cancellationReason().orElseGet(active.control::timeoutReason));
            return false;
        }
        if (!runs.readable(active.request.scope())) {
            cancel(active.id, new CancelReason("INTERACTION_WAIT_ACCESS_REVOKED", "event wait scope is no longer readable"));
            return false;
        }
        return runs.find(active.id).map(stored -> stored.snapshot().state() == RunState.WAITING_EVENT).orElse(false);
    }

    private void deliverInteractionWait(ActiveRun active, RunState state, JsonNode wait, JsonNode result) {
        synchronized (active.launchLock) {
            if (active.interactionWait != wait || active.terminated.get() || active.detached.get()) return;
            if (active.control.cancelled()) {
                cancel(active.id, active.control.cancellationReason().orElseGet(active.control::timeoutReason)); return;
            }
            String invocation = wait.path("invocationId").asText();
            String tool = state == RunState.WAITING_CHILD ? "interaction_delegate" : "interaction_wait_event";
            ObjectNode step = JsonNodeFactory.instance.objectNode()
                    .put("stepId", StepId.tool(active.id, invocation).value());
            step.set("output", JsonNodeFactory.instance.objectNode().put("status", "SUCCEEDED")
                    .put("errorCode", "").put("displayMessage", "")
                    .set("rawOutput", result));
            ((ObjectNode) step.path("output")).set("modelOutput", result);
            ObjectNode completed = JsonNodeFactory.instance.objectNode().put("tool", tool)
                    .put("invocationId", invocation).put("status", "SUCCEEDED").put("errorCode", "")
                    .put("displayMessage", "").put("durationMillis", 0);
            completed.set("output", result); completed.set("modelOutput", result);
            ObjectNode receipt = JsonNodeFactory.instance.objectNode().put("tool", tool)
                    .put("invocationId", invocation).put("operation", "execute").put("target", "")
                    .put("status", "OBSERVED").put("observedAt", clock.instant().toString())
                    .put("evidenceRef", "core.interaction.result:" + active.id.value() + ":" + invocation)
                    .put("reason", "host delivered the settled interaction result");
            receipt.putObject("metadata").put("delivery", "NOT_SENT");
            ObjectNode resumed = JsonNodeFactory.instance.objectNode()
                    .put("commandType", state == RunState.WAITING_CHILD ? "interaction.result" : "interaction.event");
            resumed.set("command", JsonNodeFactory.instance.objectNode().put("invocationId", invocation));
            ObjectNode delivered = JsonNodeFactory.instance.objectNode().put("invocationId", invocation)
                    .put("childRunId", wait.path("childRunId").asText())
                    .put("revision", wait.path("revision").asLong(1));
            var appended = runs.appendBatch(active.id, Set.of(state), RunState.RUNNING, List.of(
                    event(active.request, "core.step.completed", "framework.core", step, null),
                    event(active.request, "core.tool.completed", 2, "framework.core", completed, null),
                    event(active.request, "core.tool.receipt", "framework.core", receipt, null),
                    event(active.request, state == RunState.WAITING_CHILD ? "core.interaction.child_completed" : "core.interaction.event_received",
                            "framework.core", delivered, null),
                    event(active.request, "core.run.resumed", "framework.core", resumed, null)));
            if (appended.isEmpty()) return;
            active.control.restoreEffectReceipt(invocation, com.javaclaw.framework.spi.EffectReceiptV1.Status.OBSERVED, "NOT_SENT");
            disposeInteractionWait(active);
            appended.get().forEach(active.sink::tryEmitNext);
            launch(active, new ResumeCommand(resumed.path("commandType").asText(), resumed.path("command")), null,
                    Set.of(RunState.RUNNING));
        }
    }

    private void disposeInteractionWait(ActiveRun active) {
        synchronized (active.launchLock) {
            active.interactionWait = null;
            var subscription = active.interactionSubscription;
            active.interactionSubscription = null;
            if (subscription != null) subscription.dispose();
            var awaited = active.interactionEventAwait; active.interactionEventAwait = null;
            if (awaited != null) awaited.cancel(false);
            var renewal = active.waitRenewal; active.waitRenewal = null;
            if (renewal != null) renewal.cancel(false);
            var deadline = active.waitDeadline; active.waitDeadline = null;
            if (deadline != null) deadline.cancel(false);
        }
    }

    private void fail(ActiveRun active, Throwable failure) {
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
    private void terminate(ActiveRun active, RunOutcome outcome, List<RunId> children) {
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
    private void cleanupIfReady(ActiveRun active) {
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

    private void attachRecovered(RunRecoveryCoordinator.RecoveredRun recovered) {
        StoredRun stored = recovered.stored();
        RunId id = stored.snapshot().id();
        ActiveRun active = new ActiveRun(id, stored.request(), recovered.plan(), recovered.control());
        active.pendingApproval = recovered.approval().pendingApproval();
        active.pendingApprovedInvocation = recovered.approval().pendingApprovedInvocation();
        if (activeRuns.putIfAbsent(id, active) != null) {
            throw new IllegalStateException("duplicate recovered run: " + id);
        }
        replayPersisted(active);
        recovery.pauseRecovered(stored, active.sink::tryEmitNext);
    }

    private void replayPersisted(ActiveRun active) {
        runs.eventsAfter(active.id, 0).forEach(active.sink::tryEmitNext);
    }

    private RunEventDraft event(
            RunRequest request, String type, String producer, JsonNode payload, String causationId) {
        return event(request, type, 1, producer, payload, causationId);
    }

    private RunEventDraft event(
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

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("AgentEngine is closed");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (ActiveRun active : List.copyOf(activeRuns.values())) detachForShutdown(active);
            waitTimers.shutdownNow();
        }
    }

    private void detachForShutdown(ActiveRun active) {
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
        private final CompletableFuture<Void> physicallySettled = new CompletableFuture<>();
        private volatile CompletableFuture<Void> ready = new CompletableFuture<>();
        private final Object launchLock = new Object();
        private final AtomicBoolean executing = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean cancelling = new AtomicBoolean();
        private final AtomicBoolean detached = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicBoolean managedAttached = new AtomicBoolean();
        private volatile ToolApprovalChallenge pendingApproval;
        private volatile ApprovedToolInvocation pendingApprovedInvocation;
        private PendingLaunch pendingLaunch;
        private volatile JsonNode interactionWait;
        private volatile reactor.core.Disposable interactionSubscription;
        private volatile CompletableFuture<JsonNode> interactionEventAwait;
        private volatile java.util.concurrent.ScheduledFuture<?> waitRenewal;
        private volatile java.util.concurrent.ScheduledFuture<?> waitDeadline;

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
