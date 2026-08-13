package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.DefaultConversationHandle;
import com.javaclaw.api.conversation.TerminalCallbackGuard;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.BudgetExceededException;
import reactor.core.Disposable;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Temporary product-port adapter from the durable framework event stream to the existing
 * JavaFX-neutral conversation callbacks. It owns no reasoning state: the run, cancellation,
 * approval pause/resume and terminal arbitration remain in the one {@link AgentClient}.
 */
public final class AgentConversationRunner implements AutoCloseable {
    private static final String LEGACY_BUDGET_EXCEPTION =
            "com.javaclaw.framework.core.BudgetExceededException";
    private final AgentClient agents;
    private final Executor callbacksExecutor;
    private final ConcurrentHashMap<RunId, Active> runs = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentConversationRunner(AgentClient agents, Executor callbacksExecutor) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.callbacksExecutor = Objects.requireNonNull(callbacksExecutor, "callbacksExecutor");
    }

    public ConversationHandle start(
            RunRequest request,
            ToolCallOrigin origin,
            ConversationCallbacks callbacks) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(callbacks, "callbacks");
        ToolCallOrigin effectiveOrigin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        TerminalCallbackGuard guarded = new TerminalCallbackGuard(callbacks);
        if (closed.get()) {
            guarded.onTerminal(ConversationOutcome.failed(
                    new IllegalStateException("conversation adapter is closed")));
            return new DefaultConversationHandle(guarded, ignored -> false);
        }

        final RunHandle handle;
        try {
            handle = agents.start(request);
        } catch (Throwable failure) {
            guarded.onTerminal(ConversationOutcome.failed(failure));
            return new DefaultConversationHandle(guarded, ignored -> false);
        }

        Active current = new Active(handle, request.scope().sessionId(), guarded);
        runs.put(handle.id(), current);
        try {
            current.events = handle.events(0).subscribe(
                    event -> onEvent(current, effectiveOrigin, guarded, event),
                    failure -> guarded.onTerminal(ConversationOutcome.failed(failure)));
            handle.completion().whenCompleteAsync((outcome, failure) -> {
                runs.remove(handle.id(), current);
                current.disposeEvents();
                if (failure != null) {
                    guarded.onTerminal(ConversationOutcome.failed(unwrap(failure)));
                    return;
                }
                if (outcome.state() == RunState.COMPLETED) {
                    guarded.onTerminal(ConversationOutcome.completed());
                } else if (outcome.state() == RunState.CANCELLED) {
                    guarded.onTerminal(ConversationOutcome.cancelled(CancellationReason.USER_REQUEST));
                } else {
                    guarded.onTerminal(ConversationOutcome.failed(
                            current.failureOrFallback(outcome.error())));
                }
            }, callbacksExecutor);
            // Covers close racing between the initial closed check and publication in runs.
            if (closed.get()) {
                agents.cancel(handle.id(), new CancelReason(
                        CancellationReason.SHUTDOWN.name(), "adapter closed during start"));
            }
        } catch (Throwable failure) {
            runs.remove(handle.id(), current);
            current.disposeEvents();
            agents.cancel(handle.id(), new CancelReason(
                    "ADAPTER_START_FAILED", "failed to attach conversation adapter"));
            guarded.onTerminal(ConversationOutcome.failed(failure));
        }
        return new DefaultConversationHandle(guarded, reason ->
                agents.cancel(handle.id(), cancelReason(reason)));
    }

    /** Compatibility operation for product shutdown paths; normal callers cancel their handle. */
    public boolean cancel(CancellationReason reason) {
        boolean accepted = false;
        for (Active current : runs.values()) {
            accepted |= agents.cancel(current.handle.id(), cancelReason(reason));
        }
        return accepted;
    }

    public boolean cancelSession(String sessionId, CancellationReason reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        boolean accepted = false;
        for (Active current : runs.values()) {
            if (sessionId.equals(current.sessionId)) {
                accepted |= agents.cancel(current.handle.id(), cancelReason(reason));
            }
        }
        return accepted;
    }

    public boolean isRunning() {
        return !runs.isEmpty();
    }

    private static CancelReason cancelReason(CancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        return new CancelReason(reason.name(), "conversation adapter cancellation");
    }

    private void onEvent(
            Active current,
            ToolCallOrigin origin,
            ConversationCallbacks callbacks,
            RunEventEnvelope event) {
        JsonNode payload = event.payload();
        switch (event.type()) {
            case "core.model.started" -> callbacks.onEvent(
                    new ConversationEvent.Hint("模型正在推理…"));
            case "core.tool.started" -> callbacks.onEvent(new ConversationEvent.Hint(
                    "正在执行工具：" + payload.path("tool").asText("unknown")));
            case "core.tool.completed" -> {
                if (!payload.path("waitingInput").asBoolean(false)) {
                    callbacks.onEvent(new ConversationEvent.ToolResult(
                            payload.path("tool").asText("unknown"), render(payload.get("output"))));
                }
            }
            case "core.tool.failed" -> callbacks.onEvent(new ConversationEvent.Hint(
                    "工具执行失败：" + payload.path("message").asText("unknown error")));
            case "core.run.waiting_input" -> waitingForInput(current, callbacks, payload);
            case "core.run.waiting_approval" -> requestApproval(current, origin, callbacks, payload);
            case "core.run.completed" -> {
                String reply = payload.path("output").path("text").asText("");
                if (reply.isBlank()) reply = payload.path("output").path("value").asText("");
                if (!reply.isBlank()) callbacks.onEvent(new ConversationEvent.Reply(reply));
            }
            case "core.run.failed" -> current.captureFailure(payload);
            default -> {
                if (event.type().endsWith(".assessment")) {
                    callbacks.onEvent(new ConversationEvent.Custom(event.type(), payload));
                } else if (!event.type().startsWith("core.run.")) {
                    callbacks.onEvent(new ConversationEvent.Custom(event.type(), payload));
                }
            }
        }
    }

    private void waitingForInput(
            Active current, ConversationCallbacks callbacks, JsonNode payload) {
        JsonNode output = payload.path("output");
        if ("clarify_request".equals(output.path("kind").asText())) {
            callbacks.onEvent(new ConversationEvent.Custom(
                    "clarify_request", output.path("payload")));
            agents.cancel(current.handle.id(), new CancelReason(
                    "CLARIFICATION_REQUESTED", "conversation will continue in the next user turn"));
            return;
        }
        callbacks.onEvent(new ConversationEvent.Hint(
                payload.path("reason").asText("Agent 正在等待输入")));
    }

    private void requestApproval(
            Active current,
            ToolCallOrigin origin,
            ConversationCallbacks callbacks,
            JsonNode payload) {
        String tool;
        try {
            tool = FrameworkToolApprovalCoordinator.decode(payload).tool();
        } catch (RuntimeException malformed) {
            tool = "unknown";
        }
        callbacks.onEvent(new ConversationEvent.Hint("工具等待授权：" + tool));
        callbacksExecutor.execute(() -> {
            if (!runs.containsKey(current.handle.id()) || current.callbacks.isTerminal()) return;
            FrameworkToolApprovalCoordinator.resolve(
                    agents, current.handle, origin, payload);
        });
    }

    private static String render(JsonNode value) {
        if (value == null || value.isNull()) return "";
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Throwable decodeFailure(JsonNode payload) {
        String errorType = payload.path("errorType").asText("");
        String message = payload.path("message").asText("");
        String kindName = payload.path("budgetKind").asText("");
        if (errorType.equals(BudgetExceededException.class.getName())
                || errorType.equals(LEGACY_BUDGET_EXCEPTION) || !kindName.isBlank()) {
            BudgetExceededException.Kind kind;
            try {
                kind = BudgetExceededException.Kind.valueOf(kindName);
            } catch (IllegalArgumentException ignored) {
                kind = BudgetExceededException.Kind.UNKNOWN;
            }
            return new BudgetExceededException(kind, message,
                    payload.path("budgetActual").asText(""),
                    payload.path("budgetLimit").asText(""));
        }
        if (!message.isBlank()) return new IllegalStateException(message);
        if (!errorType.isBlank()) return new IllegalStateException(errorType);
        return new IllegalStateException("Agent run failed");
    }

    private static Throwable fallbackFailure(String persistedError) {
        if (persistedError == null || persistedError.isBlank()) {
            return new IllegalStateException("Agent run failed");
        }
        String budgetType = BudgetExceededException.class.getName();
        String matchedType = persistedError.contains(budgetType)
                ? budgetType : LEGACY_BUDGET_EXCEPTION;
        if (persistedError.contains(matchedType)) {
            int messageStart = persistedError.indexOf(':', persistedError.indexOf(matchedType));
            String message = messageStart < 0
                    ? "execution budget exceeded"
                    : persistedError.substring(messageStart + 1).strip();
            return new BudgetExceededException(message);
        }
        return new IllegalStateException(persistedError);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancel(CancellationReason.SHUTDOWN);
        }
    }

    private static final class Active {
        private final RunHandle handle;
        private final String sessionId;
        private final TerminalCallbackGuard callbacks;
        private volatile Disposable events;
        private volatile Throwable terminalFailure;

        private Active(RunHandle handle, String sessionId, TerminalCallbackGuard callbacks) {
            this.handle = handle;
            this.sessionId = sessionId;
            this.callbacks = callbacks;
        }

        private void disposeEvents() {
            Disposable current = events;
            if (current != null) current.dispose();
        }

        private void captureFailure(JsonNode payload) {
            terminalFailure = decodeFailure(payload);
        }

        private Throwable failureOrFallback(String persistedError) {
            Throwable captured = terminalFailure;
            return captured == null ? fallbackFailure(persistedError) : captured;
        }
    }
}
