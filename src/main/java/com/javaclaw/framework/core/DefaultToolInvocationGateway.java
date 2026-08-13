package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.spi.ToolApprovalPolicy;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.framework.spi.ToolPolicyDecision;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.api.ToolApprovalGrant;
import com.javaclaw.framework.api.ToolApprovalScope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Validation, permission/HITL, loop detection, timeout, normalization and events for every tool. */
public final class DefaultToolInvocationGateway implements ToolInvocationGateway {
    private final ToolApprovalPolicy approvalPolicy;
    private final CancellableTaskExecutor executor;
    private final Clock clock;
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();

    public DefaultToolInvocationGateway(
            ToolApprovalPolicy approvalPolicy,
            CancellableTaskExecutor executor,
            Clock clock) {
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<ToolInvocationResult> invoke(ToolInvocationRequest request) {
        var descriptor = request.tool().descriptor();
        if (!ToolGroupAccess.allows(request.runRequest(), descriptor.group())) {
            return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "tool group is not allowed: " + descriptor.group()));
        }
        List<com.javaclaw.framework.api.DefinitionValidationIssue> validation = schemas.validate(
                descriptor.inputSchema(), request.arguments(), "/arguments");
        if (!validation.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("invalid tool arguments: " + validation));
        }
        for (var policy : request.toolPolicies()) {
            ToolPolicyDecision decision = Objects.requireNonNull(policy.evaluate(
                    descriptor, request.toolPolicyConfiguration(), request.runRequest()),
                    "tool policy decision");
            if (decision == ToolPolicyDecision.DENY) {
                return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                        "tool denied by execution-plan policy: " + descriptor.name()));
            }
        }
        if (!request.effectivePermissions().containsAll(
                descriptor.requiredPermissions())) {
            return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "missing permission for tool " + descriptor.name()));
        }
        String fingerprint = ToolInvocationFingerprint.create(
                descriptor.name(), request.arguments());
        ToolApprovalDecision decision = approvalPolicy.evaluate(
                descriptor, request.arguments(), request.runRequest());
        if (decision == ToolApprovalDecision.DENY) {
            return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "tool denied by policy: " + descriptor.name()));
        }
        Optional<ToolApprovalGrant> approval = Optional.empty();
        if (decision == ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL) {
            approval = request.control().consumeToolApprovalGrant(
                    descriptor.name(), fingerprint);
            if (approval.isEmpty()) {
                return CompletableFuture.failedFuture(new ToolApprovalRequiredException(
                        descriptor.name(), request.arguments(), fingerprint,
                        approvalPolicy.approvalKind(
                                descriptor, request.arguments(), request.runRequest()),
                        descriptor.description()));
            }
            ToolApprovalGrant grant = approval.get();
            if (!grant.approved()) {
                return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                        "tool approval denied: " + descriptor.name()));
            }
        }

        request.control().recordToolCall(fingerprint);
        request.control().throwIfCancelled();
        ObjectNode started = JsonNodeFactory.instance.objectNode();
        started.put("tool", descriptor.name());
        started.put("invocationId", request.context().invocationId());
        started.put("fingerprint", fingerprint);
        started.set("arguments", request.arguments());
        request.events().emit("core.tool.started", 1, "framework.core", started);
        Instant startedAt = clock.instant();

        Duration remaining = Duration.between(clock.instant(), request.context().deadline());
        Duration cancellationRemaining = request.context().cancellation().remaining();
        if (cancellationRemaining.compareTo(remaining) < 0) remaining = cancellationRemaining;
        if (remaining.isNegative() || remaining.isZero()) remaining = Duration.ofNanos(1);
        ToolApprovalGrant scopedApproval = approval.orElse(null);
        java.util.concurrent.atomic.AtomicBoolean terminalEvent =
                new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<ExecutedTool> execution;
        try {
            execution = CancellableTaskStages.submit(
                    executor, "tool-" + descriptor.name(), remaining,
                    request.context().cancellation(), () -> {
                request.context().cancellation().throwIfCancelled();
                JsonNode rawOutput = scopedApproval == null
                        ? executeTool(request)
                        : ToolApprovalScope.call(scopedApproval, () -> executeTool(request));
                JsonNode modelOutput = rawOutput.deepCopy();
                for (var processor : request.resultPostProcessors()) {
                    modelOutput = Objects.requireNonNull(processor.process(
                            modelOutput, descriptor, request.context(), request.runRequest()),
                            "tool result post-processor output").deepCopy();
                }
                Duration duration = Duration.between(startedAt, clock.instant());
                return new ExecutedTool(modelOutput, duration, rawOutput);
            });
        } catch (Throwable submissionFailure) {
            try {
                if (terminalEvent.compareAndSet(false, true)) {
                    emitFailure(request, descriptor.name(), submissionFailure);
                }
            } catch (Throwable eventFailure) {
                submissionFailure.addSuppressed(eventFailure);
            }
            return CompletableFuture.failedFuture(submissionFailure);
        }
        CompletableFuture<ToolInvocationResult> published = new CompletableFuture<>();
        execution.whenComplete((value, failure) -> {
            Throwable cause = failure == null ? null : CancellableTaskStages.unwrap(failure);
            if (cause == null && request.context().cancellation().cancelled()) {
                cause = new com.javaclaw.framework.spi.RunCancelledException();
            }
            try {
                if (cause == null) {
                    if (terminalEvent.compareAndSet(false, true)) {
                        ObjectNode completed = JsonNodeFactory.instance.objectNode();
                        completed.put("tool", descriptor.name());
                        completed.put("invocationId", request.context().invocationId());
                        completed.put("durationMillis", value.duration().toMillis());
                        completed.set("output", value.rawOutput());
                        completed.put("modelViewChanged", !value.rawOutput().equals(value.output()));
                        request.events().emit("core.tool.completed", 1, "framework.core", completed);
                    }
                    published.complete(new ToolInvocationResult(value.output(), value.duration()));
                } else if (cause instanceof ToolInputRequiredException input) {
                    if (terminalEvent.compareAndSet(false, true)) {
                        ObjectNode completed = JsonNodeFactory.instance.objectNode();
                        completed.put("tool", descriptor.name());
                        completed.put("invocationId", request.context().invocationId());
                        completed.put("durationMillis",
                                Duration.between(startedAt, clock.instant()).toMillis());
                        completed.put("waitingInput", true);
                        completed.set("output", input.context());
                        completed.put("modelViewChanged", false);
                        request.events().emit(
                                "core.tool.completed", 1, "framework.core", completed);
                    }
                    published.completeExceptionally(input);
                } else {
                    if (terminalEvent.compareAndSet(false, true)) {
                        emitFailure(request, descriptor.name(), cause);
                    }
                    published.completeExceptionally(cause);
                }
            } catch (Throwable eventFailure) {
                if (cause != null) {
                    cause.addSuppressed(eventFailure);
                    published.completeExceptionally(cause);
                } else {
                    published.completeExceptionally(eventFailure);
                }
            }
        });
        return published;
    }

    private static JsonNode executeTool(ToolInvocationRequest request) throws Exception {
        return Objects.requireNonNull(
                request.tool().execute(request.arguments(), request.context()),
                "tool output").deepCopy();
    }

    private static void emitFailure(
            ToolInvocationRequest request, String toolName, Throwable failure) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("tool", toolName);
        payload.put("invocationId", request.context().invocationId());
        payload.put("errorType", failure.getClass().getName());
        payload.put("message", failure.getMessage() == null ? "" : failure.getMessage());
        request.events().emit("core.tool.failed", 1, "framework.core", payload);
    }

    private record ExecutedTool(JsonNode output, Duration duration, JsonNode rawOutput) { }
}
