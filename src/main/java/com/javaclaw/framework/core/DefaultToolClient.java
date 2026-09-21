package com.javaclaw.framework.core;

import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.ToolCallEvent;
import com.javaclaw.framework.api.ToolCallOutcome;
import com.javaclaw.framework.api.ToolCallRequest;
import com.javaclaw.framework.api.ToolClient;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolApprovalResolver;
import com.javaclaw.framework.api.ToolApprovalGrant;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.ManagedTurn;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.spi.RunStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Direct-tool facade used by Workflow TOOL nodes; no product code executes a callback itself. */
public final class DefaultToolClient implements ToolClient {
    private final ToolInvocationGateway gateway;
    private final AgentCompiler compiler;
    private final Clock clock;
    private final ToolApprovalResolver approvals;
    private final RunStore runs;
    private final AgentClient agents;

    public DefaultToolClient(
            ToolInvocationGateway gateway,
            AgentCompiler compiler,
            Clock clock) {
        this(gateway, compiler, clock, (challenge, request) ->
                CompletableFuture.failedFuture(new ToolApprovalRequiredException(
                        challenge.tool(), challenge.arguments(), challenge.fingerprint(),
                        challenge.kind(), challenge.description())));
    }

    public DefaultToolClient(
            ToolInvocationGateway gateway,
            AgentCompiler compiler,
            Clock clock,
            ToolApprovalResolver approvals) {
        this(gateway, compiler, clock, approvals, null, null);
    }

    public DefaultToolClient(ToolInvocationGateway gateway, AgentCompiler compiler, Clock clock,
                             ToolApprovalResolver approvals, RunStore runs, AgentClient agents) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.runs = runs;
        this.agents = agents;
    }

    @Override
    public CompletionStage<ToolCallOutcome> invoke(ToolCallRequest request) {
        var allowedGroups = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        request.allowedToolGroups().forEach(allowedGroups::add);
        RunRequest owner = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat"))
                .source(request.source())
                .scope(request.scope())
                .input(InputBlock.text("direct tool invocation: " + request.toolName()))
                .linkage(RunLinkage.root(request.correlationId()))
                .permissionCeiling(request.permissionCeiling())
                .budget(request.budget())
                .attributes(java.util.Map.of(ToolGroupAccess.ATTRIBUTE, allowedGroups))
                .build();
        ManagedTurn createdTurn = null;
        final ManagedTurn ownedTurn;
        final RunId runId;
        try {
            request.cancellation().throwIfCancelled();
            createdTurn = request.ownerRunId() == null && agents != null ? agents.beginTurn(owner) : null;
            ownedTurn = createdTurn;
            if (ownedTurn != null) awaitReady(ownedTurn);
            runId = ownedTurn != null ? ownedTurn.id()
                    : request.ownerRunId() != null ? request.ownerRunId() : RunId.random();
            if ((request.ownerRunId() != null || ownedTurn != null) && runs != null) {
                var existing = runs.find(runId).orElseThrow(() -> new IllegalArgumentException("tool owner turn not found"));
                if (!existing.request().scope().equals(request.scope())) {
                    throw new SecurityException("tool owner scope does not match the request");
                }
                if (existing.snapshot().state() != RunState.RUNNING) throw new IllegalStateException("tool owner turn is not running");
                RunRequest parent = existing.request();
                var restrictedGroups = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
                var parentGroups = parent.attributes().get(ToolGroupAccess.ATTRIBUTE);
                if (request.allowedToolGroups().contains("*") && parentGroups != null && parentGroups.isArray()) {
                    parentGroups.forEach(restrictedGroups::add);
                } else {
                    request.allowedToolGroups().stream().filter(group -> ToolGroupAccess.allows(parent, group))
                            .forEach(restrictedGroups::add);
                }
                var attributes = new java.util.LinkedHashMap<>(parent.attributes());
                attributes.put(ToolGroupAccess.ATTRIBUTE, restrictedGroups);
                owner = new RunRequest(parent.agent(), parent.profile(), owner.source(), owner.scope(),
                        owner.inputs(), owner.linkage(), owner.permissionCeiling().intersect(parent.permissionCeiling()),
                        owner.budget().restrictWith(parent.budget()), owner.idempotencyKey(), attributes);
            }
        } catch (RuntimeException failure) {
            if (createdTurn != null) createdTurn.fail(failure);
            return CompletableFuture.failedFuture(failure);
        }
        final String invocationId = request.invocationId() == null
                ? UUID.randomUUID().toString() : request.invocationId();
        if (runs != null) {
            var persisted = new RunStepQuery(runs).step(runId, StepId.tool(runId, invocationId));
            if (persisted.isPresent()) {
                var step = persisted.get();
                if (!step.input().path("tool").asText().equals(request.toolName())
                        || !step.input().path("arguments").equals(request.arguments())) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("tool invocation identity was reused with different arguments"));
                }
                if (step.state() != AgentStep.State.COMPLETED) {
                    return CompletableFuture.failedFuture(new ToolRecoveryRequiredException(step.id().value()));
                }
                List<ToolCallEvent> replay = runs.eventsAfter(runId, 0).stream()
                        .filter(event -> invocationId.equals(event.payload().path("invocationId").asText())
                                || step.id().value().equals(event.payload().path("stepId").asText()))
                        .map(event -> new ToolCallEvent(event.type(), event.schemaVersion(), event.producer(), event.payload()))
                        .toList();
                return CompletableFuture.completedFuture(new ToolCallOutcome(step.output().path("modelOutput"),
                        java.time.Duration.ofMillis(step.output().path("durationMillis").asLong()), replay));
            }
        }
        ExecutionPlan plan;
        try {
            plan = compiler.compile(owner);
        } catch (RuntimeException failure) {
            if (ownedTurn != null) ownedTurn.fail(failure);
            return CompletableFuture.failedFuture(failure);
        }
        RunRequest effectiveOwner = plan.annotate(owner);
        com.javaclaw.framework.spi.CancellationToken cancellation = () -> request.cancellation().cancelled()
                || (ownedTurn != null && ownedTurn.cancelled())
                || (runs != null && runs.find(runId).map(value -> value.snapshot().state() != RunState.RUNNING).orElse(true));
        RunControl control = new RunControl(plan.descriptor().budget(), clock);
        ToolContext context = new ToolContext(runId, request.scope(),
                plan.descriptor().permissions(), cancellation,
                control.deadline(), effectiveOwner);
        List<FrameworkTool> tools = new ArrayList<>();
        try {
            plan.toolFactories().forEach(factory -> tools.add(factory.create(context)));
            plan.toolProviderFactories().forEach(provider ->
                    tools.addAll(provider.create(context)));
            FrameworkTool selected = tools.stream()
                    .filter(tool -> tool.descriptor().name().equals(request.toolName()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "tool not found: " + request.toolName()));
            if (!ToolGroupAccess.allows(effectiveOwner, selected.descriptor().group())) {
                throw new ToolPermissionDeniedException(
                        "tool group is not allowed: " + selected.descriptor().group());
            }
            List<ToolCallEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            ReasoningEventSink durable = runs == null ? null : StepEvents.durableSink(runs, runId);
            ReasoningEventSink sink = (type, version, producer, payload) -> {
                if (durable != null) durable.emit(type, version, producer, payload);
                events.add(new ToolCallEvent(type, version, producer, payload));
            };
            ToolExecutionContext execution = new ToolExecutionContext(
                    runId, invocationId, cancellation, control.deadline(), request.causationStepId());
            ToolInvocationRequest invocation = new ToolInvocationRequest(
                    selected, request.arguments(), execution, effectiveOwner,
                    plan.descriptor().permissions(), plan.descriptor().toolPolicy(),
                    plan.toolPolicies(), plan.toolResultPostProcessors(),
                    control, sink);
            CompletionStage<ToolInvocationResult> invoked = invokeWithApproval(
                    invocation, effectiveOwner, control);
            CompletableFuture<ToolCallOutcome> published = new CompletableFuture<>();
            invoked.whenComplete((result, failure) -> {
                Throwable cause = failure == null ? null : unwrap(failure);
                RuntimeException closeFailure = closeResources(tools, plan);
                if (cause != null) {
                    if (closeFailure != null) cause.addSuppressed(closeFailure);
                    if (ownedTurn != null) ownedTurn.fail(cause);
                    published.completeExceptionally(cause);
                } else if (closeFailure != null) {
                    if (ownedTurn != null) ownedTurn.fail(closeFailure);
                    published.completeExceptionally(closeFailure);
                } else {
                    try {
                        ToolInvocationResult completed = Objects.requireNonNull(
                                result, "tool invocation result");
                        if (ownedTurn != null) ownedTurn.complete(completed.output());
                        published.complete(new ToolCallOutcome(
                                completed.output(), completed.duration(), events));
                    } catch (Throwable invalidResult) {
                        published.completeExceptionally(invalidResult);
                    }
                }
            });
            return published;
        } catch (RuntimeException failure) {
            if (ownedTurn != null) ownedTurn.fail(failure);
            RuntimeException closeFailure = closeResources(tools, plan);
            if (closeFailure != null) failure.addSuppressed(closeFailure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static void awaitReady(ManagedTurn turn) {
        try { turn.ready().toCompletableFuture().get(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new com.javaclaw.framework.spi.RunCancelledException();
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("tool turn did not become ready", cause);
        }
    }

    private CompletionStage<ToolInvocationResult> invokeWithApproval(
            ToolInvocationRequest invocation,
            RunRequest owner,
            RunControl control) {
        CompletableFuture<ToolInvocationResult> result = new CompletableFuture<>();
        gateway.invoke(invocation).whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
                return;
            }
            Throwable cause = unwrap(failure);
            if (!(cause instanceof ToolApprovalRequiredException required)) {
                result.completeExceptionally(cause);
                return;
            }
            approvals.resolve(required.challenge(), owner).whenComplete((grant, resolveFailure) -> {
                if (resolveFailure != null) {
                    result.completeExceptionally(unwrap(resolveFailure));
                    return;
                }
                if (grant == null || !grant.approved()
                        || !grant.tool().equals(required.toolName())
                        || !grant.fingerprint().equals(required.fingerprint())) {
                    result.completeExceptionally(new ToolPermissionDeniedException(
                            "tool approval denied: " + required.toolName()));
                    return;
                }
                control.approveToolCall(grant);
                // A resolver gets one challenge and the invocation gets exactly one retry.
                gateway.invoke(invocation).whenComplete((retried, retryFailure) -> {
                    if (retryFailure == null) result.complete(retried);
                    else result.completeExceptionally(unwrap(retryFailure));
                });
            });
        });
        return result;
    }

    private static RuntimeException closeTools(List<FrameworkTool> tools) {
        RuntimeException first = null;
        for (int index = tools.size() - 1; index >= 0; index--) {
            try {
                tools.get(index).close();
            } catch (Exception failure) {
                if (first == null) first = new IllegalStateException("cannot close tool", failure);
                else first.addSuppressed(failure);
            }
        }
        return first;
    }

    private static RuntimeException closeResources(
            List<FrameworkTool> tools, ExecutionPlan plan) {
        RuntimeException first = closeTools(tools);
        try {
            plan.close();
        } catch (RuntimeException failure) {
            if (first == null) first = failure;
            else first.addSuppressed(failure);
        }
        return first;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }
}
