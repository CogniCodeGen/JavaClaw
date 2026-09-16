package com.javaclaw.server.turn;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.server.extension.BrowserOperationUnconfirmedException;
import com.javaclaw.server.persistence.CoreCommandService;

/** 可信工具结果的模型观察投影；失败和非浏览器结果不能凭同名字段获得图片授权。 */
final class ToolModelObservations {
    private ToolModelObservations() {}

    static Optional<ToolExecutionOutcome> unconfirmed(
            ToolCallRequest request, Exception failure, boolean yieldRequested) {
        if (!(failure instanceof BrowserOperationUnconfirmedException unconfirmed)
                || !BuiltinExtensionIds.SITE.equals(request.tool().producerId())
                || !BrowserCommands.TOOL_NAMES.contains(request.tool().name())) {
            return Optional.empty();
        }
        var result = new ToolCallResult(request.callId(), false, unconfirmed.payload(), Optional.empty());
        return Optional.of(new ToolExecutionOutcome(result, List.of(), List.of(), List.of(), yieldRequested));
    }

    static ToolExecutionOutcome project(
            CoreCommandService core,
            CanonicalJson json,
            ToolCallRequest request,
            ToolCallResult result,
            List<ToolExecutionFact> facts,
            boolean yieldRequested) {
        if (!result.success()) {
            return new ToolExecutionOutcome(result, List.of(), facts, List.of(), yieldRequested);
        }
        var turn = core.findTurn(request.turnId()).orElseThrow();
        var workspace = core.workspaceForThread(turn.threadId());
        var images =
                BrowserImageProjection.project(json, workspace.id(), turn.threadId(), request.tool(), result.output());
        return new ToolExecutionOutcome(
                result,
                List.of(),
                facts,
                images,
                BrowserTurnContinuation.matches(json, request.tool(), result.output()) || yieldRequested);
    }
}
