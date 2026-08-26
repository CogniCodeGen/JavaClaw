package com.javaclaw.framework.core;

import com.javaclaw.framework.api.ToolGroupAccess;

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
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override
    public CompletionStage<ToolCallOutcome> invoke(ToolCallRequest request) {
        RunId runId = RunId.random();
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
        ExecutionPlan plan;
        try {
            plan = compiler.compile(owner);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        RunRequest effectiveOwner = plan.annotate(owner);
        RunControl control = new RunControl(plan.descriptor().budget(), clock);
        ToolContext context = new ToolContext(runId, request.scope(),
                plan.descriptor().permissions(), request.cancellation(),
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
            ReasoningEventSink sink = (type, version, producer, payload) ->
                    events.add(new ToolCallEvent(type, version, producer, payload));
            ToolExecutionContext execution = new ToolExecutionContext(
                    runId, UUID.randomUUID().toString(), request.cancellation(), control.deadline());
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
                    published.completeExceptionally(cause);
                } else if (closeFailure != null) {
                    published.completeExceptionally(closeFailure);
                } else {
                    try {
                        ToolInvocationResult completed = Objects.requireNonNull(
                                result, "tool invocation result");
                        published.complete(new ToolCallOutcome(
                                completed.output(), completed.duration(), events));
                    } catch (Throwable invalidResult) {
                        published.completeExceptionally(invalidResult);
                    }
                }
            });
            return published;
        } catch (RuntimeException failure) {
            RuntimeException closeFailure = closeResources(tools, plan);
            if (closeFailure != null) failure.addSuppressed(closeFailure);
            return CompletableFuture.failedFuture(failure);
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
