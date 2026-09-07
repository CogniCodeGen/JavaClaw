package com.javaclaw.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.TurnStatus;

/**
 * 默认 Thin Turn Harness。
 *
 * <p>该类只管理模型、工具、预算、取消、流和 Item 生命周期；Plan、Loop、Workflow、SDD、Schedule 等业务分支不得进入此处。 工具来源在开始时冻结，模型只能逐步看到其子集；每次实际执行仍由
 * GovernedToolExecutor 完成实时撤权检查。
 */
public final class DefaultTurnHarness implements TurnHarness {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTurnHarness.class);

    private final TurnHarnessServices services;
    private final Clock clock;

    /**
     * 创建 Harness。
     *
     * @param services 显式平台端口
     * @param clock 平台时钟
     */
    public DefaultTurnHarness(TurnHarnessServices services, Clock clock) {
        this.services = Objects.requireNonNull(services, "services");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TurnExecutionResult execute(TurnExecutionCommand command, CancellationToken cancellation) throws Exception {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cancellation, "cancellation");
        requireExecutable(command);
        TurnRecoverySnapshot recovery = services.journal().beginOrRecover(command);
        TurnHarnessState state = new TurnHarnessState(command, recovery, clock);
        try {
            services.journal().activateBudget(command.turn().id(), state.budget());
            if (recovery.phase().unknownAfterRestart()) {
                return unknownOutcome(state, recovery);
            }
            return executeRunning(state, recovery, cancellation);
        } catch (TurnCancelledException failure) {
            return cancel(state);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return cancellation.isCancelled()
                    ? cancel(state)
                    : fail(state, "TURN_INTERRUPTED", "Turn 执行线程被中断", failure);
        } catch (BudgetExceededException failure) {
            return fail(state, "BUDGET_EXCEEDED", "Turn 已达到资源预算", failure);
        } catch (TurnFailureException failure) {
            return fail(state, failure.code(), failure.getMessage(), failure);
        } catch (Exception failure) {
            return fail(state, "TURN_EXECUTION_FAILED", "Turn 执行失败", failure);
        } finally {
            services.journal().deactivateBudget(command.turn().id(), state.budget());
        }
    }

    private TurnExecutionResult executeRunning(
            TurnHarnessState state, TurnRecoverySnapshot recovery, CancellationToken cancellation) throws Exception {
        state.budget().checkpoint(cancellation);
        ModelCapabilities capabilities =
                services.models().capabilities(state.command().modelRoute());
        ConversationWindow window = services.contexts().assemble(state.command(), cancellation);
        state.window(window);
        ToolCatalogSnapshot snapshot = state.command().toolCatalog();
        List<ToolDescriptor> initial = services.catalogs().initialTools(snapshot);
        requireCapabilities(capabilities, state.window());
        VisibleToolCatalog visibleTools = new VisibleToolCatalog(snapshot, initial);
        visibleTools.reveal(recovery.visibleTools().identities().stream()
                .map(snapshot::require)
                .toList());
        if (state.phase() == TurnExecutionPhase.MODEL_COMMITTED) {
            return complete(state);
        }
        if (state.phase() == TurnExecutionPhase.TOOLS_READY
                || state.phase() == TurnExecutionPhase.TOOL_APPROVAL_RESOLVED) {
            executeTools(state, snapshot, visibleTools, cancellation);
        }
        while (true) {
            state.budget().checkpoint(cancellation);
            CommittedModelResult committed = invokeModel(state, visibleTools, capabilities, cancellation);
            acceptModelResult(state, visibleTools, committed, capabilities);
            if (committed.result().toolCalls().isEmpty()) {
                return complete(state);
            }
            executeTools(state, snapshot, visibleTools, cancellation);
        }
    }

    private CommittedModelResult invokeModel(
            TurnHarnessState state,
            VisibleToolCatalog visibleTools,
            ModelCapabilities capabilities,
            CancellationToken cancellation)
            throws Exception {
        new TurnContextPreparation(services)
                .prepare(state, capabilities.toolCalls() ? visibleTools.list() : List.of(), cancellation);
        state.budget().checkpoint(cancellation);
        if (state.window().estimatedInputTokens() > state.budget().remainingInputTokens()) {
            throw new BudgetExceededException("input token budget exhausted after usage and child reservations");
        }
        long maximumOutput =
                state.command().contextPolicy().outputAllowance(state.budget().remainingOutputTokens());
        if (maximumOutput < 1) {
            throw new BudgetExceededException("output token budget exhausted");
        }
        ModelInvocation invocation = new ModelInvocation(
                state.command().modelRoute(),
                state.command().instructions(),
                state.window().messages(),
                capabilities.toolCalls() ? visibleTools.list() : List.of(),
                maximumOutput,
                state.command().turn().resolvedConfig().reasoning());
        int invocationNumber = state.nextModelInvocation();
        services.journal()
                .recordModelIntent(
                        state.command().turn().id(),
                        invocationNumber,
                        TurnInvocationDigests.model(invocation, invocationNumber));
        Optional<ProviderState> providerState = state.window().providerState();
        if (providerState.isEmpty()) {
            ModelInvocationResult result =
                    services.models().invoke(state.command().turn().id(), invocation, services.events(), cancellation);
            return new CommittedModelResult(invocationNumber, result);
        }
        if (!(services.models() instanceof NativeConversationSupport nativeSupport)) {
            throw new TurnFailureException("PROVIDER_STATE_UNSUPPORTED", "当前模型端点不能恢复原生会话状态");
        }
        ModelInvocationResult result = nativeSupport.invokeContinuing(
                state.command().turn().id(), invocation, providerState.orElseThrow(), services.events(), cancellation);
        return new CommittedModelResult(invocationNumber, result);
    }

    private void acceptModelResult(
            TurnHarnessState state,
            VisibleToolCatalog visibleTools,
            CommittedModelResult committed,
            ModelCapabilities capabilities) {
        ModelInvocationResult result = committed.result();
        if (!result.toolCalls().isEmpty() && !capabilities.toolCalls()) {
            throw new TurnFailureException("MODEL_TOOL_CALL_UNSUPPORTED", "模型端点返回了未声明支持的工具调用");
        }
        if (result.finishReason() == ModelFinishReason.LENGTH) {
            throw new TurnFailureException("MODEL_OUTPUT_TRUNCATED", "模型输出达到本 Turn 上限");
        }
        if (result.finishReason() == ModelFinishReason.CONTENT_FILTER) {
            throw new TurnFailureException("MODEL_CONTENT_FILTERED", "模型服务拒绝了本次输出");
        }
        for (ModelToolCall call : result.toolCalls()) {
            if (!state.callIds().add(call.callId())) {
                throw new TurnFailureException("DUPLICATE_TOOL_CALL", "模型重复使用了工具调用 ID");
            }
            visibleTools.requireVisible(call.tool());
        }
        boolean withinBudget = state.observeUsage(result.usage());
        services.journal()
                .commitModelResult(state.command().turn().id(), committed.invocationNumber(), result, state.usage());
        state.assistant().append(result.text());
        if (result.providerState().isPresent()) {
            long estimate =
                    Math.addExact(result.usage().inputTokens(), result.usage().generatedTokens());
            state.window(new ConversationWindow(
                    List.of(),
                    result.providerState(),
                    Math.max(estimate, state.window().fixedInputTokens()),
                    state.window().fixedInputTokens()));
        } else {
            state.window(state.window().withProviderState(result.providerState()));
            state.appendMessages(List.of(ModelMessage.assistant(result.text(), result.toolCalls())));
        }
        state.modelCommitted(result);
        if (!withinBudget) {
            throw new BudgetExceededException("Provider usage exceeded the frozen budget");
        }
    }

    private void executeTools(
            TurnHarnessState state,
            ToolCatalogSnapshot snapshot,
            VisibleToolCatalog visibleTools,
            CancellationToken cancellation)
            throws Exception {
        while (!state.pendingToolCalls().isEmpty()) {
            ModelToolCall call = state.pendingToolCalls().getFirst();
            state.budget().checkpoint(cancellation);
            if (!state.toolBudgetAlreadyConsumed()) {
                state.budget().consumeToolCall();
            }
            ToolDescriptor descriptor = visibleTools.requireVisible(call.tool());
            ToolCallRequest request = request(state, snapshot, call);
            int toolIndex = state.nextToolIndex();
            services.journal()
                    .recordToolIntent(
                            state.command().turn().id(),
                            toolIndex,
                            request,
                            state.budget().toolCalls(),
                            TurnInvocationDigests.tool(request, toolIndex));
            ToolExecutionOutcome outcome = services.tools()
                    .execute(request, descriptor, snapshot, state.command().effectivePermissions(), cancellation);
            validateOutcome(request, outcome);
            visibleTools.reveal(outcome.revealedTools());
            services.journal()
                    .commitToolResult(
                            state.command().turn().id(),
                            toolIndex,
                            request,
                            outcome,
                            visibleTools.list().stream()
                                    .map(ToolDescriptor::identity)
                                    .toList());
            state.appendMessages(List.of(ModelMessage.tool(
                    call.callId(), call.tool().name(), outcome.result().output().json())));
            state.toolCommitted();
        }
    }

    private static ToolCallRequest request(TurnHarnessState state, ToolCatalogSnapshot snapshot, ModelToolCall call) {
        String idempotencyKey = state.command().turn().id() + ":" + call.callId();
        return new ToolCallRequest(
                state.command().turn().id(),
                call.callId(),
                call.tool(),
                call.arguments(),
                idempotencyKey,
                snapshot.catalogRevision());
    }

    private static void validateOutcome(ToolCallRequest request, ToolExecutionOutcome outcome) {
        ToolCallResult result = outcome.result();
        if (!request.callId().equals(result.callId())) {
            throw new TurnFailureException("TOOL_RESULT_MISMATCH", "工具结果关联了错误的调用 ID");
        }
        result.receipt().ifPresent(receipt -> validateReceipt(request, result, receipt));
    }

    private static void validateReceipt(ToolCallRequest request, ToolCallResult result, EffectReceipt receipt) {
        boolean valid = receipt.idempotencyKey().equals(request.idempotencyKey())
                && receipt.toolName().equals(request.tool().name())
                && receipt.requestDigest().equals(request.arguments().sha256())
                && receipt.resultDigest().equals(result.output().sha256());
        if (!valid) {
            throw new TurnFailureException("EFFECT_RECEIPT_MISMATCH", "副作用凭据与工具调用不一致");
        }
    }

    private TurnExecutionResult complete(TurnHarnessState state) {
        finishResources(state);
        services.journal()
                .transition(state.command().turn().id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
        return result(state, TurnStatus.COMPLETED, Optional.empty());
    }

    private TurnExecutionResult cancel(TurnHarnessState state) {
        try {
            finishResources(state);
        } catch (TurnFailureException failure) {
            return fail(state, failure.code(), failure.getMessage(), failure);
        }
        services.journal()
                .transition(state.command().turn().id(), TurnStatus.RUNNING, TurnStatus.CANCELLED, Optional.empty());
        return result(state, TurnStatus.CANCELLED, Optional.empty());
    }

    private TurnExecutionResult fail(TurnHarnessState state, String code, String message, Exception failure) {
        return fail(state, code, message, failure, Map.of());
    }

    private TurnExecutionResult fail(
            TurnHarnessState state, String code, String message, Exception failure, Map<String, String> details) {
        try {
            finishResources(state);
        } catch (TurnFailureException cleanupFailure) {
            code = cleanupFailure.code();
            message = cleanupFailure.getMessage();
            failure.addSuppressed(cleanupFailure);
        }
        LOGGER.warn(
                "Turn {} failed with {} ({})",
                state.command().turn().id(),
                code,
                failure.getClass().getSimpleName());
        CorePayloads.Error error = new CorePayloads.Error(code, message, false, Instant.now(clock), details);
        services.journal().append(state.command().turn().id(), "error", CoreSchemas.ERROR, error, ItemStatus.FAILED);
        services.journal()
                .transition(state.command().turn().id(), TurnStatus.RUNNING, TurnStatus.FAILED, Optional.of(code));
        return result(state, TurnStatus.FAILED, Optional.of(code));
    }

    private void finishResources(TurnHarnessState state) {
        if (!state.beginFinalization()) {
            return;
        }
        try {
            services.resources().finish(state.command().turn().id());
        } catch (Exception failure) {
            throw new TurnFailureException("RESOURCE_FINALIZATION_UNKNOWN", "Turn 资源关闭或结束事实未能确认");
        }
    }

    private TurnExecutionResult unknownOutcome(TurnHarnessState state, TurnRecoverySnapshot recovery) {
        Map<String, String> details = Map.of(
                "phase", recovery.phase().name(),
                "intentDigest", recovery.activeIntentDigest().orElseThrow(),
                "modelInvocations", Integer.toString(recovery.modelInvocations()),
                "toolCalls", Integer.toString(recovery.toolCalls()));
        IllegalStateException evidence = new IllegalStateException("App Server restarted during an external call");
        return fail(state, "UNKNOWN_OUTCOME", "App Server 在外部调用期间异常终止；为避免重复副作用，本 Turn 已停止且不能自动重试", evidence, details);
    }

    private static TurnExecutionResult result(TurnHarnessState state, TurnStatus status, Optional<String> errorCode) {
        return new TurnExecutionResult(
                state.command().turn().id(),
                status,
                state.assistant().toString(),
                state.usage(),
                state.budget().toolCalls(),
                state.windowOptional().flatMap(ConversationWindow::providerState),
                errorCode);
    }

    private static void requireExecutable(TurnExecutionCommand command) {
        if (command.turn().status() != TurnStatus.QUEUED && command.turn().status() != TurnStatus.RUNNING) {
            throw new IllegalArgumentException("Turn must be QUEUED or RUNNING before execution");
        }
    }

    private static void requireCapabilities(ModelCapabilities capabilities, ConversationWindow window) {
        if (window.providerState().isPresent() && !capabilities.opaqueState()) {
            throw new TurnFailureException("PROVIDER_STATE_UNSUPPORTED", "当前模型端点不能恢复原生会话状态");
        }
    }

    private record CommittedModelResult(int invocationNumber, ModelInvocationResult result) {}
}
