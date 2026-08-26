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
import com.javaclaw.framework.api.ToolGroupAccess;
import com.javaclaw.framework.api.ToolNameAccess;
import com.javaclaw.framework.api.ToolAccessPolicy;
import com.javaclaw.framework.spi.ToolOutcomeCommitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(DefaultToolInvocationGateway.class);
    private static final String UNAVAILABLE_MODEL_RESULT =
            "[tool result unavailable: post-processing failed]";
    private static final long[] OUTCOME_RETRY_DELAYS_MILLIS = {50L, 100L};
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
        ToolAccessPolicy access = ToolAccessPolicy.from(request.runRequest());
        if (!access.allowsGroup(descriptor.group())) {
            return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "tool group is not allowed: " + descriptor.group()));
        }
        if (!access.allowsTool(descriptor.name())) {
            return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "tool is not allowed: " + descriptor.name()));
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
                ObjectNode budgetObservation = JsonNodeFactory.instance.objectNode();
                for (var processor : request.resultPostProcessors()) {
                    try {
                        modelOutput = Objects.requireNonNull(processor.process(
                                modelOutput, descriptor, request.context(), request.runRequest()),
                                "tool result post-processor output").deepCopy();
                    } catch (RuntimeException processingFailure) {
                        budgetObservation.put("postProcessingFailed", true);
                        budgetObservation.put("postProcessor",
                                processor.getClass().getName());
                        budgetObservation.put("postProcessingErrorType",
                                processingFailure.getClass().getName());
                        modelOutput = JsonNodeFactory.instance.textNode(UNAVAILABLE_MODEL_RESULT);
                        break;
                    }
                    try {
                        JsonNode observation = Objects.requireNonNull(
                                processor.budgetObservation(
                                        descriptor, request.context(), request.runRequest()),
                                "tool result budget observation");
                        if (observation.isObject()) {
                            budgetObservation.setAll((ObjectNode) observation);
                        }
                    } catch (RuntimeException observationFailure) {
                        budgetObservation.put("budgetObservationFailed", true);
                        budgetObservation.put("budgetObservationErrorType",
                                observationFailure.getClass().getName());
                    }
                }
                Duration duration = Duration.between(startedAt, clock.instant());
                return new ExecutedTool(modelOutput, duration, rawOutput, budgetObservation);
            });
        } catch (Throwable submissionFailure) {
            if (terminalEvent.compareAndSet(false, true)) {
                emitFailureSafely(request, descriptor.name(), submissionFailure);
            }
            return CompletableFuture.failedFuture(submissionFailure);
        }
        CompletableFuture<ToolInvocationResult> published = new CompletableFuture<>();
        execution.whenComplete((value, failure) -> {
            Throwable cause = failure == null ? null : CancellableTaskStages.unwrap(failure);
            if (cause == null) {
                try {
                    if (terminalEvent.compareAndSet(false, true)) {
                        emitSuccess(request, descriptor.name(), value);
                    }
                } catch (RuntimeException outcomeFailure) {
                    published.completeExceptionally(outcomeFailure);
                    return;
                }
                if (request.context().cancellation().cancelled()) {
                    published.completeExceptionally(
                            new com.javaclaw.framework.spi.RunCancelledException());
                } else {
                    published.complete(new ToolInvocationResult(value.output(), value.duration()));
                }
            } else if (cause instanceof ToolInputRequiredException input) {
                try {
                    if (terminalEvent.compareAndSet(false, true)) {
                        emitWaitingInput(
                                request, descriptor.name(), startedAt, clock.instant(), input);
                    }
                    published.completeExceptionally(input);
                } catch (RuntimeException outcomeFailure) {
                    published.completeExceptionally(outcomeFailure);
                }
            } else {
                if (terminalEvent.compareAndSet(false, true)) {
                    emitFailureSafely(request, descriptor.name(), cause);
                }
                published.completeExceptionally(cause);
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

    private static void emitSuccess(
            ToolInvocationRequest request, String toolName, ExecutedTool value) {
        ObjectNode resultBudget = JsonNodeFactory.instance.objectNode();
        resultBudget.put("tool", toolName);
        resultBudget.put("invocationId", request.context().invocationId());
        resultBudget.put("rawCharacters", renderedCharacters(value.rawOutput()));
        resultBudget.put("modelCharacters", renderedCharacters(value.output()));
        resultBudget.put("truncated", !value.rawOutput().equals(value.output()));
        resultBudget.setAll(value.budgetObservation());

        ObjectNode completed = JsonNodeFactory.instance.objectNode();
        completed.put("tool", toolName);
        completed.put("invocationId", request.context().invocationId());
        completed.put("durationMillis", value.duration().toMillis());
        completed.set("output", value.rawOutput());
        completed.put("modelViewChanged", !value.rawOutput().equals(value.output()));
        completed.put("rawOutputCharacters", renderedCharacters(value.rawOutput()));
        completed.put("modelOutputCharacters", renderedCharacters(value.output()));
        completed.set("resultBudget", resultBudget.deepCopy());
        emitRequiredOutcome(request, toolName, 2, completed);
        emitSafely(request, "core.tool.result.budget", 2, resultBudget);

        if (toolName.equals("skill_read")) {
            ObjectNode skillRead = JsonNodeFactory.instance.objectNode();
            skillRead.put("skillName", request.arguments().path("skillName")
                    .asText(request.arguments().path("skill_name").asText("")));
            skillRead.put("path", request.arguments().path("path").asText(""));
            skillRead.put("cursor", request.arguments().path("cursor").asInt(0));
            skillRead.put("query", request.arguments().path("query").asText(""));
            skillRead.put("line", request.arguments().path("line").asInt(0));
            skillRead.put("returnedCharacters", renderedCharacters(value.output()));
            emitSafely(request, "core.skill.read.completed", skillRead);
        }
    }

    private static void emitWaitingInput(
            ToolInvocationRequest request, String toolName, Instant startedAt, Instant completedAt,
            ToolInputRequiredException input) {
        ObjectNode completed = JsonNodeFactory.instance.objectNode();
        completed.put("tool", toolName);
        completed.put("invocationId", request.context().invocationId());
        completed.put("durationMillis", Duration.between(startedAt, completedAt).toMillis());
        completed.put("waitingInput", true);
        completed.set("output", input.context());
        completed.put("modelViewChanged", false);
        emitRequiredOutcome(request, toolName, 1, completed);
    }

    private static void emitRequiredOutcome(
            ToolInvocationRequest request, String toolName, int version, ObjectNode payload) {
        RuntimeException lastFailure = null;
        int attempts = OUTCOME_RETRY_DELAYS_MILLIS.length + 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                request.events().emit(
                        "core.tool.completed", version, "framework.core", payload);
                return;
            } catch (RuntimeException eventFailure) {
                lastFailure = eventFailure;
                if (attempt >= OUTCOME_RETRY_DELAYS_MILLIS.length) break;
                awaitOutcomeRetry(OUTCOME_RETRY_DELAYS_MILLIS[attempt]);
            }
        }
        String invocationId = request.context().invocationId();
        throw new ToolOutcomeCommitException(toolName, invocationId,
                "could not commit completed outcome for tool " + toolName
                        + " invocation " + invocationId + " after " + attempts + " attempts",
                lastFailure);
    }

    private static void awaitOutcomeRetry(long delayMillis) {
        boolean interrupted = false;
        long remainingNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delayMillis);
        long deadline = System.nanoTime() + remainingNanos;
        while (remainingNanos > 0) {
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remainingNanos);
                break;
            } catch (InterruptedException ignored) {
                // Cancellation must not skip committing a fact for an already-finished tool.
                interrupted = true;
                remainingNanos = deadline - System.nanoTime();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void emitFailureSafely(
            ToolInvocationRequest request, String toolName, Throwable failure) {
        try {
            emitFailure(request, toolName, failure);
        } catch (RuntimeException eventFailure) {
            log.warn("Could not publish failed event for tool {} invocation {}: {}",
                    toolName, request.context().invocationId(), eventFailure.toString());
            log.debug("Tool failure event publication failure", eventFailure);
        }
    }

    private static void emitSafely(
            ToolInvocationRequest request, String type, ObjectNode payload) {
        emitSafely(request, type, 1, payload);
    }

    private static void emitSafely(
            ToolInvocationRequest request, String type, int version, ObjectNode payload) {
        try {
            request.events().emit(type, version, "framework.core", payload);
        } catch (RuntimeException eventFailure) {
            log.warn("Could not publish {} for tool invocation {}: {}",
                    type, request.context().invocationId(), eventFailure.toString());
            log.debug("Tool event publication failure", eventFailure);
        }
    }

    private static int renderedCharacters(JsonNode value) {
        if (value == null) return 0;
        return value.isTextual() ? value.asText().length() : value.toString().length();
    }

    private record ExecutedTool(
            JsonNode output, Duration duration, JsonNode rawOutput,
            ObjectNode budgetObservation) { }
}
