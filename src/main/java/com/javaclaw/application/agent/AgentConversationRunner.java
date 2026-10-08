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
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.api.TaskResultJson;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.api.TurnPausedException;
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
        long afterSequence = 0;
        try {
            JsonNode interactionCommand = request.attributes().get(com.javaclaw.framework.api.InteractionControlCommand.ATTRIBUTE);
            if (interactionCommand != null) {
                var parent = agents.activeTurn(request.scope()).orElseThrow(() ->
                        new IllegalStateException("没有正在等待的交互子任务"));
                var command = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(interactionCommand,
                        com.javaclaw.framework.api.InteractionControlCommand.class);
                agents.controlInteraction(parent.id(), command);
                guarded.onTerminal(ConversationOutcome.completed(TaskResult.notApplicable()));
                return new DefaultConversationHandle(guarded, ignored -> false);
            }
            boolean resumeSchedule = request.source().kind().equals("schedule")
                    && request.attributes().getOrDefault("framework.resumeSafeSchedule",
                    com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean();
            var waiting = interactive(request) || resumeSchedule
                    ? agents.activeTurn(request.scope()).orElse(null) : null;
            var intent = waiting != null && !resumeSchedule
                    ? InteractiveTurnRouting.intent(waiting.state(), request,
                            agents.request(waiting.id()).orElse(null)) : null;
            if (!resumeSchedule && waiting != null
                    && (waiting.state() == RunState.PAUSED || waiting.state() == RunState.WAITING_INPUT
                        || waiting.state() == RunState.WAITING_APPROVAL)
                    && intent == InteractiveTurnRouting.Intent.NEW_TASK) {
                // Keep the old journal and its unknown effects; a new goal owns a new contract.
                boolean expired = agents.expired(waiting.id());
                if (!agents.cancel(waiting.id(), new CancelReason(
                        expired ? "RUN_TIMEOUT" : "TASK_SUPERSEDED",
                        expired ? "run deadline exceeded" : "replaced by a new human request"))
                        && !agents.get(waiting.id()).state().terminal()) {
                    throw new IllegalStateException("previous turn could not be settled");
                }
                waiting = null;
            }
            if (waiting != null && (waiting.state() == RunState.WAITING_INPUT
                    || waiting.state() == RunState.PAUSED
                    || (!resumeSchedule && waiting.state() == RunState.WAITING_APPROVAL))) {
                afterSequence = waiting.lastSequence();
                var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                var inputs = payload.putArray("inputs");
                request.inputs().stream().filter(input -> !resumeSchedule && !input.type().equals("core.message"))
                        .forEach(input -> {
                            inputs.addObject().put("type", input.type()).set("data", input.data());
                            if (input.type().equals("core.text")) payload.put("text", input.data().path("text").asText());
                        });
                handle = agents.resume(waiting.id(), new com.javaclaw.framework.api.ResumeCommand(
                        resumeSchedule ? "schedule.continue"
                                : intent == InteractiveTurnRouting.Intent.CONTINUE ? "input.continue" : "input",
                        payload));
            } else {
                handle = agents.start(request);
            }
        } catch (Throwable failure) {
            guarded.onTerminal(ConversationOutcome.failed(failure));
            return new DefaultConversationHandle(guarded, ignored -> false);
        }

        Active current = new Active(handle, request.scope().sessionId(), guarded, interactive(request));
        runs.put(handle.id(), current);
        try {
            current.events = handle.events(afterSequence).subscribe(
                    event -> onEvent(current, effectiveOrigin, guarded, event),
                    failure -> guarded.onTerminal(ConversationOutcome.failed(failure)));
            if (guarded.isTerminal()) current.disposeEvents();
            handle.completion().whenCompleteAsync((outcome, failure) -> {
                runs.remove(handle.id(), current);
                current.disposeEvents();
                if (current.shutdownDetached.get()) return;
                if (failure != null) {
                    guarded.onTerminal(ConversationOutcome.failed(unwrap(failure)));
                    return;
                }
                if (outcome.state() == RunState.COMPLETED) {
                    guarded.onTerminal(ConversationOutcome.completed(current.taskResultOrUnknown(
                            durableTaskResult(handle.id()))));
                } else if (outcome.state() == RunState.CANCELLED) {
                    guarded.onTerminal(ConversationOutcome.cancelled(current.cancellationReason(),
                            current.taskResultOrUnknown(durableTaskResult(handle.id())),
                            current.cancellationDetail(outcome.error())));
                } else {
                    guarded.onTerminal(ConversationOutcome.failed(
                            current.failureOrFallback(outcome.error())));
                }
            }, callbacksExecutor);
            // Covers close racing between the initial closed check and publication in runs.
            if (closed.get()) {
                closeActive(current);
            }
        } catch (Throwable failure) {
            runs.remove(handle.id(), current);
            current.disposeEvents();
            agents.cancel(handle.id(), new CancelReason(
                    "ADAPTER_START_FAILED", "failed to attach conversation adapter"));
            guarded.onTerminal(ConversationOutcome.failed(failure));
        }
        return new DefaultConversationHandle(guarded, reason -> cancelActive(current, reason));
    }

    /** Interactive shutdown releases this adapter; the owning kernel checkpoints durable execution. */
    public boolean cancel(CancellationReason reason) {
        boolean accepted = false;
        for (Active current : runs.values()) {
            accepted |= cancelActive(current, reason);
        }
        return accepted;
    }

    public boolean cancelSession(String sessionId, CancellationReason reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        boolean accepted = false;
        for (Active current : runs.values()) {
            if (sessionId.equals(current.sessionId)) {
                accepted |= cancelActive(current, reason);
            }
        }
        return accepted;
    }

    public boolean isRunning() {
        return !runs.isEmpty();
    }

    private boolean cancelActive(Active current, CancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == CancellationReason.SHUTDOWN && current.interactive) {
            // Never terminal-cancel durable human work before AgentEngine.close can pause it.
            // This includes a parent waiting on a child question, approval or event subscription.
            boolean detached = current.shutdownDetached.compareAndSet(false, true);
            runs.remove(current.handle.id(), current);
            current.disposeEvents();
            return detached;
        }
        return agents.cancel(current.handle.id(), cancelReason(reason));
    }

    private static CancelReason cancelReason(CancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        return new CancelReason(reason.name(), "conversation adapter cancellation");
    }

    private TaskResult durableTaskResult(RunId id) {
        try {
            return agents.taskResult(id).orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void onEvent(
            Active current,
            ToolCallOrigin origin,
            ConversationCallbacks callbacks,
            RunEventEnvelope event) {
        if (current.shutdownDetached.get()) return;
        JsonNode payload = event.payload();
        if (current.reply.onEvent(event, callbacks)) return;
        switch (event.type()) {
            case "core.model.started" -> callbacks.onEvent(
                    new ConversationEvent.Hint("模型正在推理…"));
            case "core.model.usage" -> current.reportPrimaryUsage(callbacks, payload);
            case "core.model_task.usage" -> current.reportAuxiliaryUsage(callbacks, payload);
            case "core.model.completed" -> current.reportUnseenPrimaryUsage(callbacks, payload);
            case "core.tool.started" -> callbacks.onEvent(new ConversationEvent.ToolStarted(
                    payload.path("tool").asText("unknown"),
                    payload.path("invocationId").asText(""),
                    render(payload.get("arguments"))));
            case "core.tool.completed" -> {
                if (!payload.path("waitingInput").asBoolean(false)) {
                    callbacks.onEvent(new ConversationEvent.ToolResult(
                            payload.path("tool").asText("unknown"), render(payload.get("output")),
                            payload.path("invocationId").asText(""), payload.get("output"),
                            executionStatus(payload)));
                }
            }
            case "core.tool.failed" -> callbacks.onEvent(new ConversationEvent.ToolFailed(
                    payload.path("tool").asText("unknown"),
                    payload.path("invocationId").asText(""),
                    payload.path("message").asText("unknown error")));
            case "core.run.waiting_input" -> waitingForInput(current, callbacks, payload);
            case "core.run.waiting_child" -> {
                callbacks.onEvent(new ConversationEvent.Hint("交互子 agent 正在执行任务…"));
                callbacks.onEvent(new ConversationEvent.Custom(event.type(), payload.path("output")));
                if (payload.path("output").path("needsAnswer").asBoolean(false))
                    emitInteractionQuestion(callbacks, payload.path("output").path("currentChallenge"));
            }
            case "core.run.waiting_event" -> callbacks.onEvent(new ConversationEvent.Hint("等待界面变化…"));
            case "core.interaction.child_waiting_input" -> {
                callbacks.onEvent(new ConversationEvent.Custom(event.type(), payload));
                String question = payload.path("output").path("text").asText("");
                if (!question.isBlank()) callbacks.onEvent(new ConversationEvent.Hint(question));
                if ("clarify_request".equals(payload.path("output").path("kind").asText()))
                    callbacks.onEvent(new ConversationEvent.Custom("clarify_request", payload.path("output").path("payload")));
            }
            case "core.interaction.control_applied" -> {
                callbacks.onEvent(new ConversationEvent.Custom(event.type(), payload));
                if (payload.path("delivery").asText().equals("REJECTED")
                        && payload.path("needsAnswer").asBoolean(false)) {
                    emitInteractionQuestion(callbacks, payload.path("currentChallenge"));
                }
            }
            case "core.interaction.child_waiting_approval", "core.interaction.control_accepted",
                    "core.interaction.amending", "core.interaction.child_completed", "core.run.resumed" ->
                    callbacks.onEvent(new ConversationEvent.Custom(event.type(), payload));
            case "core.run.paused" -> {
                runs.remove(current.handle.id(), current);
                current.disposeEvents();
                callbacks.onTerminal(ConversationOutcome.failed(pausedFailure(payload)));
            }
            case "core.run.waiting_approval" -> requestApproval(current, origin, callbacks, payload);
            case "core.run.completed" -> {
                current.captureTaskResult(payload.path("taskResult"));
                String reply = payload.path("output").path("text").asText("");
                if (reply.isBlank()) reply = payload.path("output").path("value").asText("");
                current.reply.canonical(reply, callbacks);
            }
            case "core.task.outcome" -> current.captureTaskResult(payload);
            case "core.run.cancelled" -> {
                current.captureTaskResult(payload.path("taskResult"));
                current.captureCancellation(payload);
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

    private static void emitInteractionQuestion(ConversationCallbacks callbacks, JsonNode challenge) {
        String question = challenge.path("text").asText("");
        if (!question.isBlank()) callbacks.onEvent(new ConversationEvent.Hint(question));
        if ("clarify_request".equals(challenge.path("kind").asText()))
            callbacks.onEvent(new ConversationEvent.Custom("clarify_request", challenge.path("payload")));
    }

    private void waitingForInput(
            Active current, ConversationCallbacks callbacks, JsonNode payload) {
        JsonNode output = payload.path("output");
        if ("clarify_request".equals(output.path("kind").asText())) {
            callbacks.onEvent(new ConversationEvent.Custom(
                    "clarify_request", output.path("payload")));
        } else {
            String question = output.path("text").asText("");
            if (question.isBlank()) question = output.path("value").asText("");
            if (!question.isBlank()) {
                // 在结束本次投递前显示提问，等待输入的 Turn 仍保留在框架中。
                current.reply.canonical(question, callbacks);
            } else {
                callbacks.onEvent(new ConversationEvent.Hint(
                        payload.path("reason").asText("Agent 正在等待输入")));
            }
        }
        if (current.interactive) {
            runs.remove(current.handle.id(), current);
            current.disposeEvents();
            callbacks.onTerminal(new ConversationOutcome.WaitingInput(
                    current.handle.id().value(), payload.path("reason").asText("等待补充输入")));
        }
    }

    private static boolean interactive(RunRequest request) {
        return request.profile().id().equals("chat") || request.profile().id().equals("plan");
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
            if (runs.get(current.handle.id()) != current || current.callbacks.isTerminal()) return;
            FrameworkToolApprovalCoordinator.resolve(
                    agents, current.handle, origin, payload,
                    () -> closed.get() || runs.get(current.handle.id()) != current);
        });
    }

    private static String render(JsonNode value) {
        if (value == null || value.isNull()) return "";
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static ToolExecutionStatus executionStatus(JsonNode payload) {
        try {
            return ToolExecutionStatus.valueOf(payload.path("status").asText("UNKNOWN"));
        } catch (IllegalArgumentException invalid) {
            return ToolExecutionStatus.UNKNOWN;
        }
    }

    private static Throwable pausedFailure(JsonNode payload) {
        JsonNode output = payload.path("output");
        if ("BUDGET_EXHAUSTED".equals(payload.path("reason").asText(""))
                || "harness.budget_exhausted".equals(output.path("kind").asText(""))) {
            BudgetExceededException.Kind kind;
            try { kind = BudgetExceededException.Kind.valueOf(output.path("budgetKind").asText("")); }
            catch (IllegalArgumentException unknown) { kind = BudgetExceededException.Kind.UNKNOWN; }
            return new BudgetExceededException(kind, "run budget exhausted",
                    output.path("budgetActual").asText(""), output.path("budgetLimit").asText(""));
        }
        if (TurnPausedException.Reason.UNAUTHORIZED_CONTEXT_SOURCE.name()
                .equals(output.path("reasonCode").asText(""))) {
            return TurnPausedException.unauthorizedContextSource(
                    output.path("contextSourceId").asText(""));
        }
        return new TurnPausedException(pausedMessage(payload));
    }

    private static String pausedMessage(JsonNode payload) {
        JsonNode output = payload.path("output");
        String reason = payload.path("reason").asText("执行已暂停，请核对结果后继续");
        if ("harness.protocol_violation".equals(output.path("kind").asText(""))
                || "PROTOCOL_ERROR".equals(reason)) {
            return protocolPausedMessage(output);
        }
        if ("task.contract.unreliable".equals(output.path("kind").asText(""))) {
            return safeDiagnostic(output.path("text").asText("验收条件规划未通过校验"), 600);
        }
        if ("tool.effect_observation_required".equals(output.path("kind").asText(""))) {
            return safeDiagnostic(output.path("text").asText("先前操作结果待核验，本次操作尚未执行"), 600);
        }
        if (!"tool.recovery_required".equals(output.path("kind").asText(""))) return reason;
        StringBuilder message = new StringBuilder("工具调用已暂停：模型请求了当步未提供的工具，该批调用均未执行。");
        String requested = names(output.path("requestedTools"));
        String offered = names(output.path("offeredTools"));
        if (!requested.isBlank()) message.append(" 请求工具：").append(requested).append('。');
        if (!offered.isBlank()) message.append(" 当步可用工具：").append(offered).append('。');
        else if (output.has("offeredTools")) message.append(" 当步可用工具：无。 ");
        String unfinished = output.path("unfinishedAction").asText("");
        if (unfinished.equals("The requested tool batch was not executed")) {
            unfinished = "请求的工具调用尚未执行";
        }
        if (unfinished.isBlank()) unfinished = "需要重新选择可用工具并继续任务";
        message.append(" 未完成动作：").append(safeDiagnostic(unfinished, 160)).append('。');
        String stepId = output.path("stepId").asText("");
        if (!stepId.isBlank()) message.append(" 诊断步骤：")
                .append(safeDiagnostic(stepId, 80)).append('。');
        if (requested.isBlank() && offered.isBlank() && !reason.isBlank()) {
            message.append(" 原因：").append(safeDiagnostic(reason, 240));
        }
        return message.toString();
    }

    private static String protocolPausedMessage(JsonNode output) {
        String violation = output.path("violationCode").asText("");
        String explanation = switch (violation) {
            case "MODEL_DECISION_MISSING" -> "模型未提交通过校验的回合决策";
            case "MODEL_DECISION_MIXED_BATCH" -> "模型将回合决策与普通工具调用混在同一批中";
            case "MODEL_DECISION_MULTIPLE_GENERATIONS" -> "模型在包含多个生成结果的响应中提交了回合决策";
            default -> "模型的回合决策未通过协议校验";
        };
        StringBuilder message = new StringBuilder("回合已暂停：")
                .append(explanation).append("。协议诊断：PROTOCOL_ERROR");
        if (!violation.isBlank()) message.append(" / ").append(safeDiagnostic(violation, 80));
        message.append('。');
        String decisionError = output.path("decisionErrorCode").asText("");
        if (!decisionError.isBlank()) {
            String rejection = switch (decisionError) {
                case "UNKNOWN_EVIDENCE_REFERENCE" -> "证据引用未对应有效的系统回执";
                case "UNKNOWN_CRITERION_ID" -> "决策引用了任务中不存在的验收条件";
                case "DECISION_SCHEMA_INVALID" -> "决策字段不符合规定格式";
                case "DECISION_JSON_INVALID" -> "决策内容无法解析为有效 JSON";
                default -> "回合决策参数被拒绝";
            };
            message.append(" 决策错误：").append(rejection).append("（")
                    .append(safeDiagnostic(decisionError, 80)).append("）。");
        }
        String detail = output.path("reason").asText("");
        if (detail.isBlank()) detail = output.path("detail").asText("");
        if (!detail.isBlank()) message.append(" 详情：").append(safeDiagnostic(detail, 400));
        String decisionDetail = output.path("decisionErrorDetail").asText("");
        if (!decisionDetail.isBlank()) message.append(" 拒绝原因：")
                .append(safeDiagnostic(decisionDetail, 400));
        return message.toString();
    }

    private static String names(JsonNode values) {
        if (!values.isArray()) return "";
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode value : values) {
            String name = value.asText("").trim();
            if (!name.isBlank()) names.add(safeDiagnostic(name, 80));
            if (names.size() == 12) break;
        }
        return String.join("、", names);
    }

    private static String safeDiagnostic(String raw, int maxLength) {
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < raw.length() && safe.length() < maxLength; index++) {
            char ch = raw.charAt(index);
            if (Character.isISOControl(ch)) {
                safe.append(' ');
            } else if (ch == '[' || ch == ']' || ch == '<' || ch == '>'
                    || ch == '`' || ch == '*' || ch == '\\') {
                safe.append(' ');
            } else {
                safe.append(ch);
            }
        }
        return safe.toString().trim();
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
        if (errorType.equals(BudgetExceededException.class.getName())) {
            BudgetExceededException.Kind kind = BudgetExceededException.Kind.valueOf(kindName);
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
        return new IllegalStateException(persistedError);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Active current : runs.values()) closeActive(current);
        }
    }

    private void closeActive(Active current) {
        cancelActive(current, CancellationReason.SHUTDOWN);
    }

    private static final class Active {
        private final RunHandle handle;
        private final String sessionId;
        private final TerminalCallbackGuard callbacks;
        private final boolean interactive;
        private final AtomicBoolean shutdownDetached = new AtomicBoolean();
        private final ConversationReplyProjection reply;
        private volatile Disposable events;
        private volatile Throwable terminalFailure;
        private volatile TaskResult taskResult;
        private volatile String cancellationCode;
        private volatile String cancellationDetail;
        private long reportedPrimaryInputTokens;
        private long reportedPrimaryOutputTokens;

        private Active(RunHandle handle, String sessionId, TerminalCallbackGuard callbacks, boolean interactive) {
            this.handle = handle;
            this.sessionId = sessionId;
            this.callbacks = callbacks;
            this.interactive = interactive;
            this.reply = new ConversationReplyProjection(handle.id().value());
        }

        private void disposeEvents() {
            reply.close();
            Disposable current = events;
            if (current != null) current.dispose();
        }

        private synchronized void reportPrimaryUsage(ConversationCallbacks callbacks, JsonNode payload) {
            long input = tokenCount(payload, "inputTokens");
            long output = tokenCount(payload, "outputTokens");
            reportedPrimaryInputTokens = Math.addExact(reportedPrimaryInputTokens, input);
            reportedPrimaryOutputTokens = Math.addExact(reportedPrimaryOutputTokens, output);
            emitUsage(callbacks, input, output);
        }

        private void reportAuxiliaryUsage(ConversationCallbacks callbacks, JsonNode payload) {
            emitUsage(callbacks, tokenCount(payload, "inputTokens"),
                    tokenCount(payload, "outputTokens"));
        }

        /** The completion event contains the whole primary-model total, including calls already reported. */
        private synchronized void reportUnseenPrimaryUsage(ConversationCallbacks callbacks, JsonNode payload) {
            long input = tokenCount(payload, "inputTokens");
            long output = tokenCount(payload, "outputTokens");
            long inputDelta = Math.max(0, input - reportedPrimaryInputTokens);
            long outputDelta = Math.max(0, output - reportedPrimaryOutputTokens);
            reportedPrimaryInputTokens = Math.max(reportedPrimaryInputTokens, input);
            reportedPrimaryOutputTokens = Math.max(reportedPrimaryOutputTokens, output);
            emitUsage(callbacks, inputDelta, outputDelta);
        }

        private static long tokenCount(JsonNode payload, String field) {
            return Math.max(0, payload.path(field).asLong(0));
        }

        private static void emitUsage(ConversationCallbacks callbacks, long input, long output) {
            if (input > 0 || output > 0) callbacks.onEvent(new ConversationEvent.Usage(input, output));
        }

        private void captureFailure(JsonNode payload) {
            terminalFailure = decodeFailure(payload);
        }

        private void captureTaskResult(JsonNode payload) {
            TaskResult parsed = TaskResultJson.decode(payload);
            if (parsed != null) taskResult = parsed;
        }

        private void captureCancellation(JsonNode payload) {
            cancellationCode = payload.path("code").asText("");
            cancellationDetail = payload.path("detail").asText("");
        }

        private CancellationReason cancellationReason() {
            String code = cancellationCode;
            if ("TOOL_APPROVAL_DENIED".equals(code)) return CancellationReason.APPROVAL_DENIED;
            if (code != null) {
                try { return CancellationReason.valueOf(code); }
                catch (IllegalArgumentException ignored) { /* preserve unknown codes in detail */ }
            }
            return CancellationReason.UNKNOWN;
        }

        private String cancellationDetail(String persistedError) {
            String detail = cancellationDetail;
            if (detail != null && !detail.isBlank()) return detail;
            return persistedError == null || persistedError.isBlank()
                    ? java.util.Objects.requireNonNullElse(cancellationCode, "") : persistedError;
        }

        private TaskResult taskResultOrUnknown(TaskResult durableFallback) {
            TaskResult result = taskResult;
            if (result == null) result = durableFallback;
            return result == null
                    ? TaskResult.unverified("未找到可核验的任务结果")
                    : result;
        }

        private Throwable failureOrFallback(String persistedError) {
            Throwable captured = terminalFailure;
            return captured == null ? fallbackFailure(persistedError) : captured;
        }
    }
}
