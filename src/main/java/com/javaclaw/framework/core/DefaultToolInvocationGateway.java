package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.spi.ToolApprovalPolicy;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.framework.spi.ToolPolicyDecision;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import com.javaclaw.framework.spi.ToolExecutionResultV1;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.api.ToolApprovalGrant;
import com.javaclaw.framework.api.ToolApprovalScope;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Validation, permission/HITL, loop detection, timeout, normalization and events for every tool. */
public final class DefaultToolInvocationGateway implements ToolInvocationGateway {
    private final ToolApprovalPolicy approvalPolicy;
    private final CancellableTaskExecutor executor;
    private final Clock clock;
    private final com.javaclaw.framework.spi.RunStore runStore;
    private final ObjectMapper json;
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();

    public DefaultToolInvocationGateway(
            ToolApprovalPolicy approvalPolicy,
            CancellableTaskExecutor executor,
            Clock clock) {
        this(approvalPolicy, executor, clock, null);
    }

    public DefaultToolInvocationGateway(ToolApprovalPolicy approvalPolicy,
            CancellableTaskExecutor executor, Clock clock, com.javaclaw.framework.spi.RunStore runStore) {
        this(approvalPolicy, executor, clock, runStore, new ObjectMapper());
    }

    public DefaultToolInvocationGateway(ToolApprovalPolicy approvalPolicy,
            CancellableTaskExecutor executor, Clock clock, com.javaclaw.framework.spi.RunStore runStore,
            ObjectMapper json) {
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runStore = runStore;
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CompletionStage<ToolInvocationResult> invoke(ToolInvocationRequest request) {
        return invokeInternal(request, false);
    }

    @Override
    public ToolInvocationResult invokeInline(ToolInvocationRequest request) {
        if (!(request.tool() instanceof com.javaclaw.framework.spi.FrameworkContextReadTool)) {
            throw new IllegalArgumentException("inline invocation is reserved for context reads");
        }
        return invokeInternal(request, true).toCompletableFuture().join();
    }

    private CompletionStage<ToolInvocationResult> invokeInternal(
            ToolInvocationRequest request, boolean inline) {
        var descriptor = request.tool().descriptor();
        // Trusted recall is answer context even on the normal asynchronous execution path.
        boolean trustedContextRead = (inline
                && com.javaclaw.framework.springai.TrustedFrameworkToolIdentity
                        .isContextRead(request.tool()))
                || (com.javaclaw.framework.builtin.memory.MemoryRecallExtension
                        .isTrustedRecallTool(request.tool())
                    && descriptor.idempotent()
                    && descriptor.effectPolicy() == ToolEffectPolicy.LEGACY
                    && descriptor.requiredPermissions().equals(
                            com.javaclaw.framework.api.PermissionSet.of("tool.read")));
        boolean trustedToolCatalog = com.javaclaw.framework.springai.TrustedFrameworkToolIdentity
                .isToolCatalog(request.tool());
        if (!ToolGroupAccess.allows(request.runRequest(), descriptor.group())) {
            return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "tool group is not allowed: " + descriptor.group()));
        }
        if (!trustedToolCatalog && !InteractionExecutionPolicy.allowsTool(request.runRequest(),
                runStore == null ? List.of() : runStore.eventsAfter(request.context().runId(), 0),
                descriptor.name(), descriptor.group())) {
            return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "tool is outside the active interaction backend: " + descriptor.name()));
        }
        boolean observationTargetSelection = com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry
                .isExactHostTool(request.tool())
                && (descriptor.name().equals("ask_user_clarification")
                    || descriptor.name().equals("web_tab_switch")
                    || CrossModeBusinessFence.BROWSER_OBSERVATION_WAITS.contains(descriptor.name())
                    || descriptor.name().equals("desktop_session_open")
                        && !request.arguments().path("control").asBoolean(false));
        if (InteractionExecutionPolicy.isInteraction(request.runRequest()) && !descriptor.idempotent()
                && !observationTargetSelection
                && !com.javaclaw.framework.builtin.InteractionTools.isTrustedTool(request.tool())) {
            if (runStore != null) {
                try {
                    var current = runStore.find(request.context().runId()).orElseThrow(() ->
                            new SecurityException("interaction business-fence owner is missing"));
                    CrossModeBusinessFence.assertSideEffectAllowed(runStore, json, current);
                } catch (RuntimeException businessUnverified) {
                    return CompletableFuture.failedFuture(businessUnverified);
                }
            }
            try { request.control().assertNoUnknownInteractionEffect(); }
            catch (RuntimeException unknownEffect) { return CompletableFuture.failedFuture(unknownEffect); }
        }
        if (InteractionExecutionPolicy.isInteraction(request.runRequest()) && runStore != null
                && java.util.Set.of("web_click", "web_dblclick", "web_fill", "web_select", "web_check",
                    "web_upload", "web_type", "web_press_key", "web_drag", "web_hover", "web_scroll",
                    "web_focus", "web_scroll_to_element", "web_mouse_move", "web_mouse_click_at",
                    "site_fill_password", "site_login_now", "web_eval_js",
                    "desktop_session_click", "desktop_session_type", "desktop_session_key", "desktop_session_scroll")
                    .contains(descriptor.name())) {
            var history = runStore.eventsAfter(request.context().runId(), 0);
            long selected = history.stream().filter(event -> event.type().equals(InteractionExecutionPolicy.MODE_SELECTED_EVENT)
                    && event.producer().equals("framework.core")).mapToLong(com.javaclaw.framework.api.RunEventEnvelope::sequence)
                    .max().orElse(0);
            boolean browser = descriptor.group().equals("web");
            boolean observed = selected == 0 || history.stream().anyMatch(event -> event.sequence() > selected
                    && event.type().equals("core.tool.receipt") && event.producer().equals("framework.core")
                    && event.payload().path("status").asText().equals("OBSERVED")
                    && (browser ? java.util.Set.of("web_snapshot", "web_get_text")
                                .contains(event.payload().path("tool").asText())
                            : event.payload().path("tool").asText().equals("desktop_session_observe")));
            if (!observed) return CompletableFuture.failedFuture(new ToolPermissionDeniedException(
                    "FRESH_OBSERVATION_REQUIRED: observe the selected backend before input"));
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
        List<com.javaclaw.framework.api.DefinitionValidationIssue> validation = schemas.validate(
                descriptor.inputSchema(), request.arguments(), "/arguments");
        if (!validation.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolArgumentValidationException(validation));
        }
        boolean limitedDesktopDiscovery = runStore != null && descriptor.name().equals("desktop_session_targets")
                && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool());
        if (limitedDesktopDiscovery) {
            try {
                DesktopFrameRecoveryLimit.assertDiscoveryAllowed(request.context().runId(),
                        runStore.eventsAfter(request.context().runId(), 0), request.context().invocationId());
            } catch (ToolPermissionDeniedException exhaustedRecovery) {
                return CompletableFuture.failedFuture(exhaustedRecovery);
            }
        }
        String fingerprint = ToolInvocationFingerprint.create(
                descriptor.name(), request.arguments());
        String effectKey = ToolEffectKey.create(descriptor.name(), request.arguments(), fingerprint);
        boolean trustedDesktopInput = descriptor.effectPolicy() == ToolEffectPolicy.OBSERVATION_GATED
                && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool())
                && java.util.Set.of("desktop_session_click", "desktop_session_type", "desktop_session_key",
                        "desktop_session_scroll").contains(descriptor.name());
        boolean limitedDesktopClick = runStore != null && trustedDesktopInput
                && InteractionExecutionPolicy.isInteraction(request.runRequest())
                && descriptor.name().equals("desktop_session_click");
        if (limitedDesktopClick) {
            try {
                DesktopActionProgressLimit.assertClickAllowed(request.context().runId(),
                        runStore.eventsAfter(request.context().runId(), 0));
            } catch (DesktopNoProgressException stalled) {
                return CompletableFuture.failedFuture(stalled);
            }
        }
        boolean trustedBrowserNavigation = descriptor.name().equals("web_navigate")
                && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool());
        JsonNode desktopInputArguments = trustedDesktopInput ? request.arguments() : null;
        String resourceKey = descriptor.effectPolicy() == ToolEffectPolicy.OBSERVATION_GATED
                || descriptor.effectPolicy() == ToolEffectPolicy.DISCOVERY_GATED
                ? request.tool().effectResourceKey(request.arguments()) : "";
        if (descriptor.effectPolicy() == ToolEffectPolicy.DISCOVERY_GATED
                && descriptor.name().equals("desktop_session_launch_application")
                && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool())
                && (resourceKey.isBlank() || resourceKey.equals("desktop.application:unknown")))
            resourceKey = request.control().desktopLaunchResourceKey(request.arguments().path("application").asText());
        if (descriptor.effectPolicy() == ToolEffectPolicy.OBSERVATION_GATED
                && resourceKey.isBlank()) resourceKey = effectKey;
        com.javaclaw.framework.spi.BrowserNavigationNoOpProvider.Prepared navigationNoOp = null;
        try {
            request.control().assertRepairRetryAllowed(fingerprint, effectKey,
                    descriptor.idempotent(), descriptor.effectPolicy(), resourceKey, desktopInputArguments);
        } catch (ToolPermissionDeniedException duplicateEffect) {
            if (duplicateEffect instanceof PendingEffectObservationRequiredException blocked) {
                try {
                    navigationNoOp = BrowserNavigationNoOpAdmission.prepare(runStore, json, request, blocked).orElse(null);
                } catch (RuntimeException unavailable) {
                    // A failed read/admission must preserve the original mutation fence.
                    if (unavailable instanceof com.javaclaw.framework.spi.RunCancelledException)
                        return CompletableFuture.failedFuture(unavailable);
                    return CompletableFuture.failedFuture(duplicateEffect);
                }
            }
            if (navigationNoOp == null) return CompletableFuture.failedFuture(duplicateEffect);
        }
        ToolApprovalDecision decision = approvalPolicy.evaluate(
                request.tool(), request.arguments(), request.runRequest());
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
                                request.tool(), request.arguments(), request.runRequest()),
                        descriptor.description(), trustedContextRead));
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
        started.put("effectKey", effectKey);
        started.put("effectPolicy", descriptor.effectPolicy().name());
        started.put("resourceKey", resourceKey);
        started.put("idempotent", descriptor.idempotent());
        started.put("trustedContextRead", trustedContextRead);
        started.put("trustedToolCatalog", trustedToolCatalog);
        started.put("trustedDesktopTool", com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry
                .isExactHostTool(request.tool()));
        started.put("trustedBrowserNavigation", trustedBrowserNavigation);
        if (navigationNoOp != null) started.put("readOnlyNavigationNoOp", true);
        started.set("arguments", request.arguments());
        StepId stepId = StepId.tool(request.context().runId(), request.context().invocationId());
        String invocationId = request.context().invocationId();
        String causation = request.context().causationStepId();
        if (causation == null && invocationId.startsWith("model/") && invocationId.indexOf('/', 6) > 6)
            causation = invocationId.substring(6, invocationId.indexOf('/', 6));
        String finalCausation = causation;
        String finalResourceKey = resourceKey;
        Runnable persistStart = () -> request.events().toolStarted(
                StepEvents.startedPayload(stepId, AgentStep.Kind.TOOL, started, finalCausation), started);
        try {
            if (navigationNoOp != null) {
                // Approval/permissions and all interaction fences still apply. Only dispatch is replaced.
                request.control().reserveReadOnlyBrowserNavigation(invocationId, fingerprint, effectKey, persistStart);
            } else request.control().reserveEffect(invocationId, fingerprint, effectKey,
                    descriptor.idempotent(), descriptor.effectPolicy(), finalResourceKey, desktopInputArguments,
                    () -> {
                        if (limitedDesktopDiscovery || limitedDesktopClick) {
                            // Serialize the last check with durable reservation across distinct direct-call controls.
                            runStore.withRunAcceptanceLock(request.context().runId(), () -> {
                                var history = runStore.eventsAfter(request.context().runId(), 0);
                                if (limitedDesktopDiscovery)
                                    DesktopFrameRecoveryLimit.assertDiscoveryAllowed(request.context().runId(),
                                            history, invocationId);
                                if (limitedDesktopClick)
                                    DesktopActionProgressLimit.assertClickAllowed(request.context().runId(), history);
                                persistStart.run();
                                return null;
                            });
                        } else persistStart.run();
                    });
        } catch (RuntimeException reservationFailure) {
            // A durable start is mandatory before executeTool or any native dispatch.
            return CompletableFuture.failedFuture(reservationFailure);
        }
        if (trustedBrowserNavigation)
            request.control().restoreTrustedBrowserNavigationStart(invocationId);
        Instant startedAt = clock.instant();
        if (trustedDesktopInput)
            request.control().restoreDesktopInputStart(invocationId, request.arguments(), startedAt);

        Duration remaining = Duration.between(clock.instant(), request.context().deadline());
        Duration cancellationRemaining = request.context().cancellation().remaining();
        if (cancellationRemaining.compareTo(remaining) < 0) remaining = cancellationRemaining;
        if (remaining.isNegative() || remaining.isZero()) remaining = Duration.ofNanos(1);
        ToolApprovalGrant scopedApproval = approval.orElse(null);
        // The gateway, rather than a caller-supplied context or tool name, identifies
        // internal reads for model-view result processors.
        ToolExecutionContext resultContext = new ToolExecutionContext(
                request.context().runId(), request.context().invocationId(),
                request.context().cancellation(), request.context().deadline(),
                request.context().causationStepId(), inline);
        // 只豁免宿主准确实现的桌面观察；副作用工具和仅自称只读的插件仍等待物理终止。
        boolean boundedReadOnly = descriptor.name().equals("desktop_session_observe")
                && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool());
        AtomicBoolean terminalEvent = new AtomicBoolean();
        AtomicReference<EffectReceiptV1> establishedEffect = new AtomicReference<>();
        var preparedNavigationNoOp = navigationNoOp;
        CompletableFuture<ExecutedTool> execution;
        java.util.concurrent.Callable<ExecutedTool> action = () -> {
            request.context().cancellation().throwIfCancelled();
            // A prepared read cannot fall through into callback/page.navigate, including on failure.
            ExecutedRaw raw;
            if (preparedNavigationNoOp != null) {
                var read = preparedNavigationNoOp.execute(request.context(), clock);
                raw = new ExecutedRaw(read.result(), read.receipt(), null);
            } else raw = scopedApproval == null ? executeTool(request, clock)
                    : ToolApprovalScope.call(scopedApproval, () -> executeTool(request, clock));
            JsonNode rawOutput = raw.result().data();
            establishedEffect.set(raw.receipt());
            JsonNode modelOutput = interactionModeModelView(request, raw.result(), rawOutput);
            for (var processor : request.resultPostProcessors()) {
                modelOutput = Objects.requireNonNull(processor.process(
                        modelOutput, descriptor, resultContext, request.runRequest()),
                        "tool result post-processor output").deepCopy();
            }
            Duration duration = Duration.between(startedAt, clock.instant());
            return new ExecutedTool(modelOutput, duration, raw.result(), raw.receipt(), raw.input());
        };
        try {
            if (inline) {
                execution = new CompletableFuture<>();
                // The primary model carrier may already hold the only IO permit. Run the
                // read without acquiring another permit, but bound how long that carrier
                // waits even if an external source ignores interruption.
                FutureTask<ExecutedTool> read = new FutureTask<>(action);
                Thread.ofVirtual().name("javaclaw-inline-context-read").start(read);
                try {
                    execution.complete(read.get(remaining.toNanos(), TimeUnit.NANOSECONDS));
                } catch (TimeoutException timeout) {
                    read.cancel(true);
                    execution.completeExceptionally(inlineTimeout(descriptor.name(), timeout));
                } catch (InterruptedException interrupted) {
                    read.cancel(true);
                    Thread.currentThread().interrupt();
                    execution.completeExceptionally(new com.javaclaw.framework.spi.RunCancelledException());
                } catch (ExecutionException failure) {
                    execution.completeExceptionally(failure.getCause());
                }
            } else {
                execution = boundedReadOnly
                        ? CancellableTaskStages.submitReadOnly(executor, "tool-" + descriptor.name(), remaining,
                                request.context().cancellation(), action)
                        : CancellableTaskStages.submit(executor, "tool-" + descriptor.name(), remaining,
                                request.context().cancellation(), action);
            }
        } catch (Throwable submissionFailure) {
            try {
                if (terminalEvent.compareAndSet(false, true)) {
                    emitFailure(request, descriptor.name(), submissionFailure);
                    publishReceipt(request, fingerprint, failureReceipt(request,
                            EffectReceiptV1.Status.FAILED, "tool task could not be submitted", clock.instant()));
                }
            } catch (Throwable eventFailure) {
                submissionFailure.addSuppressed(eventFailure);
            }
            return CompletableFuture.failedFuture(submissionFailure);
        }
        CompletableFuture<ToolInvocationResult> published = new CompletableFuture<>();
        execution.whenComplete((value, failure) -> {
            Runnable publication = () -> {
                Throwable cause = failure == null ? null : CancellableTaskStages.unwrap(failure);
                cause = trustedClarificationInput(request, cause);
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
                            completed.put("status", value.raw().status().name());
                            completed.put("errorCode", value.raw().errorCode());
                            completed.put("displayMessage", value.raw().displayMessage());
                            completed.set("output", value.raw().data());
                            completed.set("modelOutput", value.output());
                            completed.put("modelViewChanged", !value.raw().data().equals(value.output()));
                            ObjectNode stepOutput = JsonNodeFactory.instance.objectNode();
                            stepOutput.set("rawOutput", value.raw().data());
                            stepOutput.set("modelOutput", value.output());
                            stepOutput.put("status", value.raw().status().name());
                            stepOutput.put("errorCode", value.raw().errorCode());
                            stepOutput.put("displayMessage", value.raw().displayMessage());
                            stepOutput.put("durationMillis", value.duration().toMillis());
                            if (value.input() != null) {
                                completed.put("waitingInput", true);
                                completed.set("inputContext", value.input().context());
                                stepOutput.put("waitingInput", true);
                                stepOutput.set("inputContext", value.input().context());
                            }
                            StepEvents.completed(request.events(), stepId, stepOutput, null);
                            if (boundedReadOnly && published.isDone()) return;
                            request.events().emit("core.tool.completed", 2, "framework.core", completed);
                            if (boundedReadOnly && published.isDone()) return;
                            publishReceipt(request, fingerprint, value.receipt(), value.raw().data());
                        }
                        if (value.input() != null) {
                            // The real normal result and receipt are durable before yielding the model carrier.
                            // Do not route this through the exceptional UNKNOWN-receipt branch below.
                            published.completeExceptionally(new ToolInputRequiredException(
                                    value.input().context(), value.input().reason()));
                        } else {
                            published.complete(new ToolInvocationResult(
                                    value.output(), value.duration(), value.raw().status(),
                                    value.raw().errorCode(), value.raw().displayMessage()));
                        }
                    } else if (cause instanceof InteractionWaitRequiredException
                            || cause instanceof InteractionEventWaitRequiredException) {
                        if (terminalEvent.compareAndSet(false, true)) {
                            JsonNode context = cause instanceof InteractionWaitRequiredException child
                                    ? child.context() : ((InteractionEventWaitRequiredException) cause).context();
                            request.events().emit("core.tool.suspended", 1, "framework.core",
                                    JsonNodeFactory.instance.objectNode().put("tool", descriptor.name())
                                            .put("invocationId", request.context().invocationId()).set("wait", context));
                        }
                        // Keep the durable step unsettled. Only the engine may fill its terminal result.
                        published.completeExceptionally(cause);
                    } else if (cause instanceof ToolInputRequiredException input) {
                        if (terminalEvent.compareAndSet(false, true)) {
                            ObjectNode completed = JsonNodeFactory.instance.objectNode();
                            completed.put("tool", descriptor.name());
                            completed.put("invocationId", request.context().invocationId());
                            completed.put("durationMillis",
                                    Duration.between(startedAt, clock.instant()).toMillis());
                            completed.put("waitingInput", true);
                            completed.put("status", ToolExecutionStatus.PENDING.name());
                            completed.set("output", input.context());
                            completed.put("modelViewChanged", false);
                            ObjectNode stepOutput = JsonNodeFactory.instance.objectNode();
                            stepOutput.set("rawOutput", input.context());
                            stepOutput.set("modelOutput", input.context());
                            stepOutput.put("waitingInput", true);
                            stepOutput.put("status", ToolExecutionStatus.PENDING.name());
                            StepEvents.completed(request.events(), stepId, stepOutput, null);
                            request.events().emit(
                                    "core.tool.completed", 2, "framework.core", completed);
                            boolean hostQuestion = descriptor.name().equals("ask_user_clarification")
                                    && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool());
                            publishReceipt(request, fingerprint, new EffectReceiptV1(
                                    request.context().invocationId(), descriptor.name(), "execute", "",
                                    hostQuestion ? EffectReceiptV1.Status.OBSERVED : EffectReceiptV1.Status.UNKNOWN, clock.instant(),
                                    "core.tool.completed:" + request.context().runId().value()
                                            + ":" + request.context().invocationId(),
                                    "tool awaits input", "", hostQuestion ? java.util.Map.of("delivery", "NOT_SENT") : java.util.Map.of()));
                        }
                        published.completeExceptionally(input);
                    } else {
                        if (terminalEvent.compareAndSet(false, true)) {
                            emitFailure(request, descriptor.name(), cause);
                            if (boundedReadOnly && published.isDone()) return;
                            EffectReceiptV1 receipt = establishedEffect.get();
                            publishReceipt(request, fingerprint, receipt == null
                                    ? failureReceipt(request, preparedNavigationNoOp == null
                                                    ? EffectReceiptV1.Status.UNKNOWN : EffectReceiptV1.Status.FAILED,
                                            preparedNavigationNoOp == null ? "tool failed before an effect could be established"
                                                    : "read-only navigation stopped without dispatch", clock.instant())
                                    : new EffectReceiptV1(receipt.invocationId(), receipt.tool(),
                                            receipt.operation(), receipt.target(), receipt.status(),
                                            receipt.observedAt(),
                                            "core.tool.started:" + request.context().runId().value()
                                                    + ":" + request.context().invocationId(),
                                            receipt.reason() + "; model result processing failed",
                                            receipt.subject(), receipt.metadata()));
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
            };
            if (boundedReadOnly) {
                // 审计/事件接收器也可能阻塞；不得让只读逻辑失败再次绑住 published。
                CompletableFuture<Void> publicationFinished = new CompletableFuture<>();
                publicationFinished.orTimeout(1, TimeUnit.SECONDS).whenComplete((ignored, publicationFailure) -> {
                    if (publicationFailure == null || published.isDone()) return;
                    terminalEvent.set(true);
                    Throwable primary = failure == null ? new TimeoutException("桌面观察收尾审计超时")
                            : CancellableTaskStages.unwrap(failure);
                    if (primary != publicationFailure) primary.addSuppressed(publicationFailure);
                    published.completeExceptionally(primary);
                });
                Thread.ofVirtual().name("javaclaw-readonly-tool-publication").start(() -> {
                    try { publication.run(); }
                    finally { publicationFinished.complete(null); }
                });
            } else publication.run();
        });
        return published;
    }

    /** Only an exact host question may turn its callback wrapper into the existing input signal. */
    private static Throwable trustedClarificationInput(ToolInvocationRequest request, Throwable failure) {
        if (failure == null || request.context().cancellation().cancelled()
                || !request.tool().descriptor().name().equals("ask_user_clarification")
                || !com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool()))
            return failure;
        Throwable current = failure;
        for (int depth = 0; depth < 8; depth++) {
            if (current instanceof ToolInputRequiredException input) {
                String reason = request.arguments().path("reason").asText("").trim();
                String question = request.arguments().path("question").asText("").trim();
                if (reason.isEmpty() && question.isEmpty()) return failure;
                ObjectNode expected = JsonNodeFactory.instance.objectNode().put("kind", "clarify_request");
                expected.putObject("payload").put("reason", reason).put("question", question);
                String expectedReason = reason.isBlank() ? "Agent 请求用户澄清" : reason;
                return expected.equals(input.context()) && expectedReason.equals(input.getMessage())
                        ? input : failure;
            }
            if (!(current instanceof java.util.concurrent.CompletionException
                    || current instanceof java.util.concurrent.ExecutionException
                    || current instanceof org.springframework.ai.tool.execution.ToolExecutionException)
                    || current.getCause() == null) return failure;
            current = current.getCause();
        }
        return failure;
    }

    /** Model-view deduplication only; the complete result and mode event remain durable. */
    private JsonNode interactionModeModelView(ToolInvocationRequest request,
            ToolExecutionResultV1 result, JsonNode raw) {
        if (runStore == null || result.status() != com.javaclaw.framework.api.ToolExecutionStatus.SUCCEEDED
                || !InteractionExecutionPolicy.isInteraction(request.runRequest())
                || !com.javaclaw.framework.builtin.InteractionTools.isTrustedSelectModeTool(request.tool())
                || !raw.isObject() || !raw.path("checkpoint").isObject()) return raw.deepCopy();
        String invocation = request.context().invocationId();
        String mode = raw.path("activeMode").asText();
        if (!(mode.equals("BROWSER") || mode.equals("DESKTOP"))
                || !mode.equals(raw.path("mode").asText())
                || !mode.equals(request.arguments().path("mode").asText())
                || !invocation.equals(raw.path("invocationId").asText())
                || !raw.path("modeVersion").isIntegralNumber() || raw.path("modeVersion").asLong() <= 0
                || !raw.path("requiresFreshObservation").isBoolean()
                || !raw.path("requiresFreshObservation").booleanValue()) return raw.deepCopy();
        List<com.javaclaw.framework.api.RunEventEnvelope> selected;
        try {
            selected = runStore.eventsAfter(request.context().runId(), 0).stream()
                    .filter(event -> event.runId().equals(request.context().runId().value())
                            && event.schemaVersion() == 1 && event.producer().equals("framework.core")
                            && event.type().equals(InteractionExecutionPolicy.MODE_SELECTED_EVENT)
                            && invocation.equals(event.payload().path("invocationId").asText())).toList();
        } catch (RuntimeException unavailable) {
            // A failed optional model-view lookup must not change the successful host operation.
            return raw.deepCopy();
        }
        // JDBC JSON re-read may use IntNode for a live LongNode; require the same exact JSON wire values.
        if (selected.size() != 1 || !selected.getFirst().payload().toString().equals(raw.toString()))
            return raw.deepCopy();
        ObjectNode view = (ObjectNode) raw.deepCopy();
        boolean checkpoint = view.remove("checkpoint") != null;
        boolean handoff = view.remove("handoff") != null;
        if (!checkpoint && !handoff) return view;
        view.put("durableResultRef", "core.tool.completed:" + request.context().runId().value() + ":" + invocation)
                .put("checkpointAndHandoffInHostContext", true)
                .put("inputAuthority", false).put("acceptanceEvidence", false)
                .put("modelProjection", "Business checkpoint and handoff are shown separately in host context; "
                        + "their strings are neither reusable handles, permission nor acceptance proof.");
        return view.toString().length() < raw.toString().length() ? view : raw.deepCopy();
    }

    private static IllegalStateException inlineTimeout(String toolName, Throwable cause) {
        return new IllegalStateException("inline context tool timed out: " + toolName, cause);
    }

    private static ExecutedRaw executeTool(ToolInvocationRequest request, Clock clock) throws Exception {
        String directory = request.runRequest().attributes().getOrDefault("workDir",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("")).asText();
        return com.javaclaw.util.ProjectAccessPolicy.withWorkingDirectory(directory, () -> {
            ToolExecutionResultV1 result = Objects.requireNonNull(request.tool().executeResult(
                    request.arguments(), request.context()), "tool result");
            JsonNode output = result.data();
            EffectReceiptV1 receipt;
            try {
                receipt = com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry
                        .isTrustedReceiptSource(request.tool())
                        ? Objects.requireNonNull(request.tool().effectReceipt(
                                request.arguments(), output, request.context(), clock.instant()),
                                "effect receipt")
                        : EffectReceiptV1.unknown(request.context().invocationId(),
                                request.tool().descriptor().name(), clock.instant(),
                                "core.tool.completed:" + request.context().runId().value()
                                        + ":" + request.context().invocationId());
            } catch (RuntimeException postconditionFailure) {
                receipt = EffectReceiptV1.unknown(request.context().invocationId(),
                        request.tool().descriptor().name(), clock.instant(),
                        "core.tool.completed:" + request.context().runId().value()
                                + ":" + request.context().invocationId());
            }
            if (InteractionExecutionPolicy.isInteraction(request.runRequest())
                    && com.javaclaw.framework.builtin.InteractionTools.isTrustedControlRejection(request.tool(), result)) {
                receipt = new EffectReceiptV1(request.context().invocationId(), request.tool().descriptor().name(),
                        "execute", "", EffectReceiptV1.Status.FAILED, clock.instant(),
                        "core.tool.completed:" + request.context().runId().value() + ":" + request.context().invocationId(),
                        "host control request rejected before state change or subscription", "",
                        java.util.Map.of("delivery", "NOT_SENT", "effect", "NONE", "dispatchAttempted", "false"));
            }
            if (InteractionExecutionPolicy.isInteraction(request.runRequest())
                    && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactBrowserBusinessInputTool(request.tool())
                    && receipt.status() == EffectReceiptV1.Status.ACCEPTED) {
                var metadata = new java.util.LinkedHashMap<>(receipt.metadata());
                metadata.put("delivery", "SENT");
                metadata.put("effect", "UNKNOWN");
                receipt = new EffectReceiptV1(receipt.invocationId(), receipt.tool(), receipt.operation(),
                        receipt.target(), receipt.status(), receipt.observedAt(), receipt.evidenceRef(),
                        receipt.reason(), receipt.subject(), metadata);
            }
            var input = InteractionExecutionPolicy.isInteraction(request.runRequest())
                    && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isTrustedUserInputSource(request.tool())
                    ? request.tool().userInputCheckpoint() : null;
            return new ExecutedRaw(result, receipt, input);
        });
    }

    private static EffectReceiptV1 failureReceipt(ToolInvocationRequest request,
            EffectReceiptV1.Status status, String reason, Instant observedAt) {
        return new EffectReceiptV1(request.context().invocationId(),
                request.tool().descriptor().name(), "execute", "", status, observedAt,
                "core.tool.failed:" + request.context().runId().value() + ":"
                        + request.context().invocationId(), reason, "",
                status == EffectReceiptV1.Status.FAILED
                        ? java.util.Map.of("delivery", "NOT_SENT")
                        : java.util.Map.of("delivery", "MAYBE_SENT"));
    }

    private static void publishReceipt(ToolInvocationRequest request, String fingerprint,
            EffectReceiptV1 receipt) {
        publishReceipt(request, fingerprint, receipt, null);
    }

    private static void publishReceipt(ToolInvocationRequest request, String fingerprint,
            EffectReceiptV1 receipt, JsonNode rawOutput) {
        ObjectNode payload = receipt.toJson();
        payload.put("fingerprint", fingerprint);
        request.events().emit("core.tool.receipt", 1, "framework.core", payload);
        request.control().restoreEffectReceipt(receipt.invocationId(), receipt.status(),
                receipt.metadata().getOrDefault("delivery", ""));
        request.control().restoreTrustedBrowserNavigationReceipt(receipt.invocationId(), receipt.tool(),
                receipt.operation(), receipt.status(), receipt.metadata().getOrDefault("delivery", ""),
                receipt.target());
        if (com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool())) {
            request.control().restoreDesktopInputReceiptTime(receipt.invocationId(), receipt.observedAt());
            request.control().restoreDesktopInputReceipt(receipt.invocationId(), request.arguments(), receipt);
            if (rawOutput != null) DesktopObservationBaseline.fromTrustedResult(request.context().runId(),
                    request.context().invocationId(), request.arguments(), rawOutput, receipt)
                    .ifPresent(frame -> {
                        request.control().restoreDesktopObservation(frame);
                        ObjectNode refreshed = JsonNodeFactory.instance.objectNode()
                                .put("sessionId", frame.sessionId()).put("targetId", frame.targetId())
                                .put("observationId", frame.observationId())
                                .put("observationInvocationId", frame.invocationId())
                                .put("windowGeneration", frame.windowGeneration())
                                .put("capturedAtMillis", frame.capturedAtMillis())
                                .put("inputBaseline", "OBSERVED_AFTER_ATTEMPT")
                                .put("effect", "UNKNOWN");
                        request.events().emit("core.desktop.observation_refreshed", 1, "framework.core", refreshed);
                    });
        }
        if (receipt.tool().equals("desktop_session_launch_application")
                && receipt.operation().equals("launch_application")
                && com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool())) {
            String identity = DesktopApplicationIdentityBindings.normalize(
                    receipt.metadata().getOrDefault("applicationId", ""));
            if (!identity.isBlank()) request.control().restoreEffectResource(receipt.invocationId(),
                    "desktop.application:" + identity);
        }
    }

    private static void emitFailure(
            ToolInvocationRequest request, String toolName, Throwable failure) {
        StepEvents.failed(request.events(),
                StepId.tool(request.context().runId(), request.context().invocationId()), failure);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("tool", toolName);
        payload.put("invocationId", request.context().invocationId());
        payload.put("errorType", failure.getClass().getName());
        payload.put("message", failure.getMessage() == null ? "" : failure.getMessage());
        payload.put("status", failure instanceof TimeoutException
                ? ToolExecutionStatus.TIMED_OUT.name()
                : ToolExecutionStatus.FAILED.name());
        request.events().emit("core.tool.failed", 2, "framework.core", payload);
    }

    private record ExecutedRaw(ToolExecutionResultV1 result, EffectReceiptV1 receipt,
            com.javaclaw.framework.spi.ToolUserInputCheckpoint input) { }
    private record ExecutedTool(JsonNode output, Duration duration, ToolExecutionResultV1 raw,
            EffectReceiptV1 receipt, com.javaclaw.framework.spi.ToolUserInputCheckpoint input) { }
}
