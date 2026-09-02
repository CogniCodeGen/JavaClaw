package com.javaclaw.server.turn;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.TurnStartRequest;

/** 为 Workflow 工具与输入节点创建独立受治理 Turn，并完整记录 Tool/EffectReceipt。 */
public final class ServerAutomationStepPort implements AutomationStepPort {
    private static final String PRODUCER_ID = "javaclaw.workflow";

    private final Dependencies dependencies;

    /**
     * 创建平台步骤端口。
     *
     * @param dependencies Core、工具、输入和持久化依赖
     */
    public ServerAutomationStepPort(Dependencies dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    @Override
    public ToolResult executeTool(ToolCommand command, CancellationToken cancellation) throws Exception {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        TurnContext turn = createTurn(command.context(), "执行固定工具 " + command.toolName());
        Optional<ToolCallResult> completed = persistedResult(turn);
        if (completed.isPresent()) {
            return result(turn, completed.orElseThrow());
        }
        requireExecutable(turn.turn());
        TurnExecutionCommand bound = dependencies
                .dispatcher()
                .bindOrchestrated(turn.turn(), turn.payload(), command.context().snapshot());
        ToolDescriptor descriptor = requireTool(bound, command.toolName());
        ToolCallRequest request = toolRequest(turn.turn(), command, descriptor, bound);
        startToolTurn(turn, request, descriptor);
        try {
            ToolCallResult executed = executeOrRecover(request, descriptor, bound, cancellation);
            completeToolTurn(turn, executed);
            return result(turn, executed);
        } catch (Exception failure) {
            failTurn(turn.turn(), "WORKFLOW_TOOL_FAILED");
            throw failure;
        }
    }

    @Override
    public InputResult openInput(InputCommand command, CancellationToken cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        TurnContext turn = createTurn(command.context(), command.prompt());
        String requestId = identifier("workflow-input", command.context().idempotencyKey());
        Optional<InputRequestRecord> existing = dependencies.inputs().find(requestId);
        if (existing.isPresent()) {
            return new InputResult(turn.thread().id(), turn.turn().id(), requestId);
        }
        requireExecutable(turn.turn());
        startWaitingTurn(turn.turn());
        InputRequest request = new InputRequest(
                requestId,
                turn.turn().id(),
                PRODUCER_ID,
                command.prompt(),
                command.responseSchema(),
                dependencies.clock().instant(),
                command.expiresAt());
        dependencies.inputs().open(request);
        return new InputResult(turn.thread().id(), turn.turn().id(), requestId);
    }

    private TurnContext createTurn(StepContext context, String message) {
        ThreadExecutionIntent intent = context.parentThreadId().isPresent()
                ? ThreadExecutionIntent.ISOLATED_WRITE
                : ThreadExecutionIntent.WORKSPACE;
        ConversationThread thread = dependencies
                .core()
                .createThread(
                        identity("automation/thread/create", context.idempotencyKey() + ":thread", context),
                        context.workspaceId(),
                        context.parentThreadId(),
                        intent,
                        context.title());
        provisionWorktree(context, thread);
        CoreRpcContracts.TurnStartPayload payload = new CoreRpcContracts.TurnStartPayload(
                thread.id(), Optional.of(context.snapshot().profile()), message);
        CorePayloads.Message user = new CorePayloads.Message(MessageRole.USER, message, List.of(), Optional.empty());
        TurnStartRequest request = dependencies.dispatcher().resolveOrchestrated(payload, user, context.snapshot());
        AgentTurn turn = dependencies
                .core()
                .startTurn(identity("automation/turn/start", context.idempotencyKey() + ":turn", payload), request);
        return new TurnContext(thread, turn, payload);
    }

    private void provisionWorktree(StepContext context, ConversationThread thread) {
        if (thread.executionIntent() == ThreadExecutionIntent.ISOLATED_WRITE) {
            dependencies
                    .worktrees()
                    .provisionForChild(
                            identity("worktree/provision", context.idempotencyKey() + ":worktree", context),
                            thread.workspaceId(),
                            thread.parentThreadId().orElseThrow(),
                            thread.id());
        }
    }

    private Optional<ToolCallResult> persistedResult(TurnContext turn) {
        return dependencies.core().listItems(turn.thread().id()).stream()
                .filter(item -> item.turnId().equals(turn.turn().id()))
                .filter(item -> CoreSchemas.TOOL_RESULT.equals(item.schemaId()))
                .map(item -> dependencies.json().decode(item.payload(), CorePayloads.ToolResult.class))
                .map(item -> new ToolCallResult(item.callId(), item.success(), item.output(), item.receipt()))
                .findFirst();
    }

    private void startToolTurn(TurnContext turn, ToolCallRequest request, ToolDescriptor descriptor) {
        if (turn.turn().status() == TurnStatus.QUEUED) {
            dependencies.worktrees().markRunning(turn.thread().id());
            dependencies
                    .journal()
                    .transition(turn.turn().id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        }
        boolean callExists = dependencies.core().listItems(turn.thread().id()).stream()
                .filter(item -> item.turnId().equals(turn.turn().id()))
                .anyMatch(item -> CoreSchemas.TOOL_CALL.equals(item.schemaId()));
        if (!callExists) {
            CorePayloads.ToolCall call = new CorePayloads.ToolCall(
                    request.callId(),
                    descriptor.identity().producerId(),
                    descriptor.identity().name(),
                    descriptor.identity().revision(),
                    request.arguments());
            dependencies
                    .journal()
                    .append(turn.turn().id(), "tool-call", CoreSchemas.TOOL_CALL, call, ItemStatus.COMPLETED);
        }
    }

    private ToolCallResult executeOrRecover(
            ToolCallRequest request,
            ToolDescriptor descriptor,
            TurnExecutionCommand bound,
            CancellationToken cancellation)
            throws Exception {
        Optional<ToolCallResult> recovered = dependencies.journal().recoverEffect(request);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        ToolExecutionOutcome outcome = dependencies
                .tools()
                .execute(request, descriptor, bound.toolCatalog(), bound.effectivePermissions(), cancellation);
        ToolCallResult result = outcome.result();
        if (!request.callId().equals(result.callId())) {
            throw new IllegalStateException("governed tool returned a mismatched call ID");
        }
        return result;
    }

    private void completeToolTurn(TurnContext turn, ToolCallResult result) {
        CorePayloads.ToolResult item =
                new CorePayloads.ToolResult(result.callId(), result.success(), result.output(), result.receipt());
        dependencies
                .journal()
                .append(turn.turn().id(), "tool-result", CoreSchemas.TOOL_RESULT, item, ItemStatus.COMPLETED);
        result.receipt().ifPresent(receipt -> appendReceipt(turn.turn(), receipt));
        dependencies.journal().transition(turn.turn().id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
        dependencies.worktrees().finishExecution(turn.thread().id(), TurnStatus.COMPLETED);
    }

    private void appendReceipt(AgentTurn turn, EffectReceipt receipt) {
        dependencies
                .journal()
                .append(turn.id(), "effect-receipt", CoreSchemas.EFFECT_RECEIPT, receipt, ItemStatus.COMPLETED);
    }

    private void startWaitingTurn(AgentTurn turn) {
        if (turn.status() == TurnStatus.QUEUED) {
            dependencies.worktrees().markRunning(turn.threadId());
            dependencies.journal().transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        }
    }

    private void failTurn(AgentTurn turn, String code) {
        AgentTurn current = dependencies.core().findTurn(turn.id()).orElseThrow();
        if (current.status() != TurnStatus.RUNNING) {
            return;
        }
        CorePayloads.Error error =
                new CorePayloads.Error(code, "Workflow 工具执行失败", true, Instant.now(dependencies.clock()), Map.of());
        dependencies.journal().append(turn.id(), "error", CoreSchemas.ERROR, error, ItemStatus.FAILED);
        dependencies.journal().transition(turn.id(), TurnStatus.RUNNING, TurnStatus.FAILED, Optional.of(code));
        dependencies.worktrees().finishExecution(turn.threadId(), TurnStatus.FAILED);
    }

    private ToolCallRequest toolRequest(
            AgentTurn turn, ToolCommand command, ToolDescriptor descriptor, TurnExecutionCommand bound) {
        String callId = identifier("workflow-tool", command.context().idempotencyKey());
        return new ToolCallRequest(
                turn.id(),
                callId,
                descriptor.identity(),
                command.arguments(),
                command.context().idempotencyKey() + ":effect",
                bound.toolCatalog().catalogRevision());
    }

    private static ToolDescriptor requireTool(TurnExecutionCommand bound, String name) {
        return bound.toolCatalog().tools().stream()
                .filter(tool -> tool.identity().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tool is not present in frozen Workflow catalog"));
    }

    private static void requireExecutable(AgentTurn turn) {
        if (turn.status() != TurnStatus.QUEUED && turn.status() != TurnStatus.RUNNING) {
            throw new IllegalStateException("automation step Turn is not executable: " + turn.status());
        }
    }

    private ToolResult result(TurnContext turn, ToolCallResult result) {
        return new ToolResult(
                turn.thread().id(),
                turn.turn().id(),
                result.success(),
                result.output(),
                result.receipt().map(EffectReceipt::idempotencyKey));
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return new CommandIdentity(
                method, key, 0, dependencies.json().encode(payload).sha256());
    }

    private String identifier(String prefix, String value) {
        String digest = dependencies.json().encode(Map.of("value", value)).sha256();
        return prefix + "-" + digest.substring(0, 32);
    }

    /**
     * 步骤执行的显式组合依赖。
     *
     * @param core Core 命令与查询
     * @param worktrees Managed Worktree 隔离
     * @param dispatcher Turn 配置权威解析
     * @param journal Item、Turn 与 EffectReceipt 日志
     * @param tools 治理工具平台
     * @param inputs 结构化输入状态机
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public record Dependencies(
            CoreCommandService core,
            ManagedWorktreeService worktrees,
            HarnessTurnDispatcher dispatcher,
            H2TurnJournal journal,
            ExtensionToolPlatform tools,
            InputRequestService inputs,
            CanonicalJson json,
            Clock clock) {
        /** 校验全部依赖。 */
        public Dependencies {
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(worktrees, "worktrees");
            Objects.requireNonNull(dispatcher, "dispatcher");
            Objects.requireNonNull(journal, "journal");
            Objects.requireNonNull(tools, "tools");
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(clock, "clock");
        }
    }

    private record TurnContext(ConversationThread thread, AgentTurn turn, CoreRpcContracts.TurnStartPayload payload) {}
}
