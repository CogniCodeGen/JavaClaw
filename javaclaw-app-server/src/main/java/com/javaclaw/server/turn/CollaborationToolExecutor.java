package com.javaclaw.server.turn;

import java.time.Clock;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;

/** 已完成治理检查后的 Core 协作工具映射，复用与 SDK 相同的应用服务。 */
final class CollaborationToolExecutor {
    private final AgentCollaborationService collaboration;
    private final CoreCommandService core;
    private final CanonicalJson json;
    private final Clock clock;

    CollaborationToolExecutor(
            AgentCollaborationService collaboration, CoreCommandService core, CanonicalJson json, Clock clock) {
        this.collaboration = collaboration;
        this.core = core;
        this.json = json;
        this.clock = clock;
    }

    ToolExecutionOutcome execute(ToolCallRequest request, com.javaclaw.api.CancellationToken cancellation)
            throws Exception {
        CanonicalPayload output =
                switch (request.tool().name()) {
                    case CollaborationTools.SPAWN -> spawn(request);
                    case CollaborationTools.WAIT -> read(request, cancellation);
                    case CollaborationTools.INTERRUPT -> interrupt(request);
                    default -> throw new TurnFailureException("TOOL_NOT_FOUND", "协作工具不存在");
                };
        Optional<EffectReceipt> receipt =
                CollaborationTools.WAIT.equals(request.tool().name())
                        ? Optional.empty()
                        : Optional.of(new EffectReceipt(
                                request.idempotencyKey(),
                                request.tool().name(),
                                request.arguments().sha256(),
                                output.sha256(),
                                clock.instant()));
        return ToolExecutionOutcome.resultOnly(new ToolCallResult(request.callId(), true, output, receipt));
    }

    private CanonicalPayload spawn(ToolCallRequest request) {
        SpawnArguments arguments = json.decode(request.arguments(), SpawnArguments.class);
        AgentTurn parent = core.findTurn(request.turnId()).orElseThrow();
        var payload = new CollaborationRpcContracts.SpawnPayload(
                parent.id(), arguments.agentType(), arguments.message(), ExecutionOverrides.empty(), arguments.title());
        return json.encode(collaboration.spawn(
                new CommandIdentity(
                        "agent/spawn",
                        request.idempotencyKey(),
                        parent.revision(),
                        json.encode(payload).sha256()),
                payload));
    }

    private CanonicalPayload read(ToolCallRequest request, com.javaclaw.api.CancellationToken cancellation)
            throws Exception {
        WaitArguments input = json.decode(request.arguments(), WaitArguments.class);
        long milliseconds = input.timeoutMillis().orElse(30_000L);
        if (milliseconds < 0 || milliseconds > 60_000) {
            throw new IllegalArgumentException("等待时间必须在 0 到 60000 毫秒之间");
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds);
        AgentTurn child = requireOwnedChild(request.turnId(), input.turnId());
        while (!java.util.Set.of(
                                com.javaclaw.api.TurnStatus.COMPLETED,
                                com.javaclaw.api.TurnStatus.CANCELLED,
                                com.javaclaw.api.TurnStatus.FAILED)
                        .contains(child.status())
                && System.nanoTime() < deadline) {
            cancellation.throwIfCancelled();
            Thread.sleep(Math.min(100, Math.max(1, milliseconds)));
            child = requireOwnedChild(request.turnId(), input.turnId());
        }
        cancellation.throwIfCancelled();
        Optional<String> response = core.turnAssistantMessage(child.id()).map(value -> value.text());
        return json.encode(new ChildResult(child, response));
    }

    private CanonicalPayload interrupt(ToolCallRequest request) {
        var input = json.decode(request.arguments(), CollaborationRpcContracts.InterruptPayload.class);
        AgentTurn child = requireOwnedChild(request.turnId(), input.turnId());
        return json.encode(collaboration.interrupt(
                new CommandIdentity(
                        "agent/interrupt",
                        request.idempotencyKey(),
                        child.revision(),
                        request.arguments().sha256()),
                child.id(),
                input.reason()));
    }

    private AgentTurn requireOwnedChild(TurnId parent, TurnId childId) {
        AgentTurn child = collaboration.read(childId);
        if (core.parentTurn(child.threadId())
                .filter(value -> value.id().equals(parent))
                .isEmpty()) {
            throw new TurnFailureException("CHILD_NOT_OWNED", "不能操作其他 Turn 创建的子任务");
        }
        return child;
    }

    private record WaitArguments(TurnId turnId, Optional<Long> timeoutMillis) {}

    private record ChildResult(AgentTurn turn, Optional<String> assistantText) {}

    private record SpawnArguments(String agentType, String message, String title) {}
}
