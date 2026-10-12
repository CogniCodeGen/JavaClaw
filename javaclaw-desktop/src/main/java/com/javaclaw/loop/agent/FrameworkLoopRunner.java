package com.javaclaw.loop.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.application.agent.FrameworkToolApprovalCoordinator;
import com.javaclaw.application.agent.ConversationReplyProjection;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.loop.LoopConstants;
import com.javaclaw.loop.LoopIterationRunner;
import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.loop.model.LoopReport;
import com.javaclaw.runtime.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loop workflow adapter backed solely by the process-wide {@link AgentClient}. Each iteration is a
 * durable child Run using the {@code loop} profile; this class owns no model, tool loop, memory or
 * checkpoint state.
 */
public final class FrameworkLoopRunner implements LoopIterationRunner, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FrameworkLoopRunner.class);

    private final AgentClient agents;
    private final WorkspaceContext workspace;
    private final String sessionId;
    private final String loopId;
    private final String systemPrompt;
    private final String workDir;
    private final Duration iterationTimeout;
    private final ToolCallOrigin origin;
    private final RunId ownerRunId;
    private final com.javaclaw.framework.api.ManagedTurn coordinator;
    private final AtomicInteger iteration = new AtomicInteger();
    private final AtomicReference<RunHandle> active = new AtomicReference<>();
    private volatile RunId lastRunId;
    private volatile boolean closed;
    private volatile boolean suspended;

    public FrameworkLoopRunner(
            AgentClient agents,
            WorkspaceContext workspace,
            String sessionId,
            String loopId,
            String systemPrompt,
            String workDir,
            long iterationTimeoutSeconds) {
        this(agents, workspace, sessionId, loopId, systemPrompt, workDir,
                iterationTimeoutSeconds, null);
    }

    public FrameworkLoopRunner(AgentClient agents, WorkspaceContext workspace, String sessionId,
            String loopId, String systemPrompt, String workDir, long iterationTimeoutSeconds,
            RunId parentRunId) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.sessionId = "loop:" + loopId + ":worker";
        this.loopId = Objects.requireNonNull(loopId, "loopId");
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.workDir = workDir;
        this.iterationTimeout = Duration.ofSeconds(Math.max(1, iterationTimeoutSeconds));
        this.origin = ToolCallOrigin.managedTask(loopId, workDir);
        this.coordinator = parentRunId == null && agents.supportsManagedTurns()
                ? agents.beginTurn(RunRequest.builder()
                        .agent(AgentDefinitionRef.latest("system.default"))
                        .profile(RunProfileRef.latest("loop"))
                        .source(InvocationSource.loop(loopId))
                        .scope(new RunScope(workspace.workspaceId(), "local-user", "loop:" + loopId))
                        .input(InputBlock.text(this.systemPrompt))
                        .permissionCeiling(PermissionSet.UNRESTRICTED)
                        .idempotencyKey("loop:" + loopId + ":coordinator")
                        .attributes(coordinatorAttributes(loopId))
                        .build()) : null;
        this.ownerRunId = parentRunId != null ? parentRunId
                : coordinator == null ? null : coordinator.id();
        if (coordinator != null) {
            try {
                coordinator.ready().toCompletableFuture().join();
                if (coordinator.completion().toCompletableFuture().isDone()) {
                    throw new IllegalStateException("循环协调轮次已经结束");
                }
            } catch (RuntimeException | Error failure) {
                coordinator.close();
                throw failure;
            }
        }
    }

    @Override
    public IterationResult runOnce(String prompt, ConversationCallbacks callbacks) {
        if (closed) return IterationResult.failed();
        int number = iteration.incrementAndGet();
        RunHandle handle;
        try {
            handle = com.javaclaw.application.agent.ChildTurnContinuation.start(agents, request(prompt, number));
        } catch (com.javaclaw.framework.api.TurnPausedException paused) {
            suspended = true;
            throw paused;
        }
        lastRunId = handle.id();
        if (!active.compareAndSet(null, handle)) {
            agents.cancel(handle.id(), new CancelReason("LOOP_CONCURRENT_ITERATION", loopId));
            return IterationResult.failed();
        }

        Capture capture = new Capture(handle.id().value());
        Disposable events = handle.events(0).subscribe(
                event -> onEvent(handle, callbacks, capture, event),
                failure -> capture.failure.compareAndSet(null, failure));
        try {
            RunOutcome outcome = com.javaclaw.application.agent.ChildTurnContinuation.await(
                    agents, handle, iterationTimeout.plusSeconds(5), () -> { });
            if (outcome.state() != RunState.COMPLETED) {
                log.warn("循环第 {} 轮 Run {} 未成功：{} {}", number, handle.id(),
                        outcome.state(), outcome.error());
                return IterationResult.failed(capture.inputTokens, capture.outputTokens);
            }
            JsonNode output = outcome.output();
            String reply = output == null ? "" : output.path("text").asText("");
            if (reply.isBlank() && output != null) reply = output.path("value").asText("");
            if (output != null) {
                capture.inputTokens = output.path("usage").path("inputTokens")
                        .asLong(capture.inputTokens);
                capture.outputTokens = output.path("usage").path("outputTokens")
                        .asLong(capture.outputTokens);
            }
            String visible = reply;
            capture.reply.canonical(visible, callbacks);
            callbacks.onEvent(new ConversationEvent.Usage(
                    capture.inputTokens, capture.outputTokens));
            return IterationResult.ok(reply, capture.inputTokens, capture.outputTokens,
                    List.copyOf(capture.toolCalls), capture.report.get(),
                    agents.taskResult(handle.id()).orElse(null),
                    modelDecision(output, capture.modelDecision.get()));
        } catch (com.javaclaw.framework.api.TurnPausedException paused) {
            suspended = true;
            throw paused;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            agents.cancel(handle.id(), new CancelReason("LOOP_INTERRUPTED", loopId));
            return IterationResult.failed(capture.inputTokens, capture.outputTokens);
        } catch (Exception failure) {
            agents.cancel(handle.id(), new CancelReason("LOOP_ITERATION_FAILED", failure.toString()));
            log.warn("循环第 {} 轮 Run {} 失败", number, handle.id(), failure);
            return IterationResult.failed(capture.inputTokens, capture.outputTokens);
        } finally {
            capture.reply.close();
            events.dispose();
            active.compareAndSet(handle, null);
        }
    }

    private RunRequest request(String prompt, int number) {
        ObjectNode invocation = JsonNodeFactory.instance.objectNode();
        invocation.put("framework.systemPrompt", systemPrompt);
        if (workDir != null && !workDir.isBlank()) invocation.put("workDir", workDir);
        invocation.put("loopId", loopId);
        invocation.put("iteration", number);
        Map<String, JsonNode> attributes = new java.util.LinkedHashMap<>();
        invocation.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), entry.getValue()));
        RunBudget budget = new RunBudget(iterationTimeout, Long.MAX_VALUE, Long.MAX_VALUE,
                Integer.MAX_VALUE, new BigDecimal("1E+100"));
        return RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("loop"))
                .source(InvocationSource.loop(loopId))
                .scope(new RunScope(workspace.workspaceId(), "local-user", sessionId))
                .input(InputBlock.text(prompt == null ? "" : prompt))
                .linkage(new RunLinkage(ownerRunId, loopId, loopId))
                .permissionCeiling(PermissionSet.UNRESTRICTED)
                .budget(budget)
                .idempotencyKey(loopId + ":iteration:" + number)
                .attributes(attributes)
                .build();
    }

    private static Map<String, JsonNode> coordinatorAttributes(String loopId) {
        Map<String, JsonNode> attributes = new java.util.LinkedHashMap<>();
        attributes.put("framework.managedTaskId", JsonNodeFactory.instance.textNode(loopId));
        return attributes;
    }

    private void onEvent(
            RunHandle handle,
            ConversationCallbacks callbacks,
            Capture capture,
            RunEventEnvelope event) {
        if (closed || active.get() != handle) return;
        JsonNode payload = event.payload();
        if (capture.reply.onEvent(event, callbacks)) return;
        switch (event.type()) {
            case "core.model.started" -> callbacks.onEvent(
                    new ConversationEvent.Hint("循环执行体正在推理…"));
            case "core.model.completed" -> {
                capture.inputTokens = payload.path("inputTokens").asLong(capture.inputTokens);
                capture.outputTokens = payload.path("outputTokens").asLong(capture.outputTokens);
            }
            case "core.harness.decision_submitted" -> {
                if (event.schemaVersion() == 1 && "framework.springai".equals(event.producer())) {
                    try {
                        capture.modelDecision.set(ModelDecisionV1.fromJson(payload.path("value")).decision());
                    } catch (IllegalArgumentException invalid) {
                        log.warn("循环第 {} 轮忽略无效 Harness 决策事件", iteration.get(), invalid);
                    }
                }
            }
            case "core.tool.started" -> {
                String tool = payload.path("tool").asText("unknown");
                JsonNode arguments = payload.path("arguments");
                if (LoopConstants.REPORT_TOOL_NAME.equals(tool)) {
                    String invocationId = payload.path("invocationId").asText("");
                    if (!invocationId.isBlank()) {
                        capture.pendingReports.put(invocationId, arguments.deepCopy());
                    }
                } else {
                    String invocationId = payload.path("invocationId").asText("");
                    if (!invocationId.isBlank()) {
                        capture.pendingToolCalls.put(invocationId,
                                tool + "#" + Integer.toHexString(arguments.toString().hashCode()));
                    }
                    callbacks.onEvent(new ConversationEvent.ToolStarted(tool,
                            invocationId, render(arguments)));
                }
            }
            case "core.tool.completed" -> {
                String tool = payload.path("tool").asText("unknown");
                ToolExecutionStatus status = executionStatus(payload);
                if (LoopConstants.REPORT_TOOL_NAME.equals(tool)) {
                    JsonNode arguments = capture.pendingReports.remove(
                            payload.path("invocationId").asText(""));
                    if (status == ToolExecutionStatus.SUCCEEDED) {
                        capture.report.set(parseReport(arguments));
                    }
                } else {
                    String invocationId = payload.path("invocationId").asText("");
                    String fingerprint = capture.pendingToolCalls.remove(invocationId);
                    if (status == ToolExecutionStatus.SUCCEEDED && fingerprint != null) {
                        capture.toolCalls.add(fingerprint);
                    }
                    callbacks.onEvent(new ConversationEvent.ToolResult(
                            tool, render(payload.get("output")),
                            invocationId, payload.path("output"), status));
                }
            }
            case "core.tool.failed" -> {
                String invocationId = payload.path("invocationId").asText("");
                capture.pendingToolCalls.remove(invocationId);
                capture.pendingReports.remove(invocationId);
                callbacks.onEvent(new ConversationEvent.ToolFailed(
                        payload.path("tool").asText("unknown"), invocationId,
                        payload.path("message").asText("unknown error")));
            }
            case "core.run.waiting_approval" -> requestApproval(handle, payload);
            case "core.run.waiting_input" -> agents.cancel(handle.id(),
                    new CancelReason("LOOP_UNEXPECTED_INPUT", "loop iterations cannot await input"));
            default -> {
                if (!event.type().startsWith("core.run.")) {
                    callbacks.onEvent(new ConversationEvent.Custom(event.type(), payload));
                }
            }
        }
    }

    private void requestApproval(RunHandle handle, JsonNode payload) {
        CompletableFuture.runAsync(() -> FrameworkToolApprovalCoordinator.resolve(
                agents, handle, origin, payload));
    }

    private static LoopReport parseReport(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) return null;
        return new LoopReport(
                arguments.path("summary").asText(""),
                arguments.path("remaining").asText(""),
                arguments.has("nextDelaySeconds")
                        ? arguments.path("nextDelaySeconds").asLong(0)
                        : arguments.path("next_delay_seconds").asLong(0),
                arguments.path("reason").asText(""));
    }

    private static ModelDecisionV1.Decision modelDecision(
            JsonNode output, ModelDecisionV1.Decision submitted) {
        if (output == null || !output.path("modelDecision").isTextual()) return submitted;
        try {
            ModelDecisionV1.Decision completed = ModelDecisionV1.Decision.valueOf(
                    output.path("modelDecision").textValue());
            // The persisted event and terminal Run output must describe the same decision.
            return submitted == null || submitted == completed ? completed : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static ToolExecutionStatus executionStatus(JsonNode payload) {
        try {
            return ToolExecutionStatus.valueOf(payload.path("status").asText("UNKNOWN"));
        } catch (IllegalArgumentException invalid) {
            return ToolExecutionStatus.UNKNOWN;
        }
    }

    private static String render(JsonNode node) {
        if (node == null || node.isNull()) return "";
        return node.isTextual() ? node.asText() : node.toString();
    }

    public void shutdown() {
        closed = true;
        RunHandle handle = active.get();
        if (handle != null) {
            agents.cancel(handle.id(), new CancelReason("LOOP_CANCELLED", loopId));
        }
        if (coordinator != null && !suspended && !coordinator.completion().toCompletableFuture().isDone()) {
            agents.cancel(coordinator.id(), new CancelReason("LOOP_CANCELLED", loopId));
        }
    }

    public RunId ownerRunId() { return ownerRunId; }

    public void finish(com.javaclaw.api.conversation.ConversationOutcome outcome) {
        if (coordinator == null) return;
        switch (outcome) {
            case com.javaclaw.api.conversation.ConversationOutcome.WaitingInput waiting ->
                    coordinator.waitingInput(JsonNodeFactory.instance.objectNode(), waiting.reason());
            case com.javaclaw.api.conversation.ConversationOutcome.Completed ignored ->
                    coordinator.complete(JsonNodeFactory.instance.objectNode().put("text", "循环已完成"));
            case com.javaclaw.api.conversation.ConversationOutcome.Failed failure -> {
                if (failure.error() instanceof com.javaclaw.framework.api.TurnPausedException) {
                    suspended = true;
                    coordinator.pause(failure.error().getMessage());
                } else coordinator.fail(failure.error());
            }
            case com.javaclaw.api.conversation.ConversationOutcome.Cancelled ignored ->
                    agents.cancel(coordinator.id(), new CancelReason("LOOP_CANCELLED", loopId));
        }
    }

    /** Owner used by post-iteration ModelTaskGateway critic calls. */
    public RunId lastRunId() {
        return lastRunId;
    }

    public boolean cancelled() {
        return closed;
    }

    @Override
    public void close() {
        shutdown();
        if (coordinator != null) coordinator.close();
    }

    private static final class Capture {
        private final ConversationReplyProjection reply;
        private Capture(String segmentId) { reply = new ConversationReplyProjection(segmentId); }
        private final List<String> toolCalls = java.util.Collections.synchronizedList(new ArrayList<>());
        private final java.util.Map<String, JsonNode> pendingReports =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, String> pendingToolCalls =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicReference<LoopReport> report = new AtomicReference<>();
        private final AtomicReference<ModelDecisionV1.Decision> modelDecision = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile long inputTokens;
        private volatile long outputTokens;
    }
}
