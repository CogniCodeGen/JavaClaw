package com.javaclaw.server.turn;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.runtime.CompactionOutcome;
import com.javaclaw.runtime.CompactionRequest;
import com.javaclaw.runtime.ContextCompactor;
import com.javaclaw.runtime.ContextTokenEstimator;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.NativeCompactionRequest;
import com.javaclaw.runtime.NativeCompactionResult;
import com.javaclaw.runtime.NativeCompactionSupport;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnFailureException;

/** 按 Harness 明确目标压缩；历史只能生成低信任参考，完整工具调用组不可拆分。 */
public final class BudgetContextCompactor implements ContextCompactor {
    /** 创建无状态压缩器。 */
    public BudgetContextCompactor() {}

    @Override
    public CompactionOutcome compact(
            TurnExecutionCommand command,
            ConversationWindow window,
            ModelGateway gateway,
            CancellationToken cancellation)
            throws Exception {
        return compact(
                new CompactionRequest(command, window, command.turn().budget().inputTokens(), 0),
                gateway,
                cancellation);
    }

    @Override
    public CompactionOutcome compact(CompactionRequest request, ModelGateway gateway, CancellationToken cancellation)
            throws Exception {
        cancellation.throwIfCancelled();
        if (request.window().providerState().isPresent()) {
            return compactNative(request, gateway, cancellation);
        }
        var messages = new MessageContextCompaction().compact(request, cancellation);
        long estimate = Math.addExact(request.fixedInputTokens(), ContextTokenEstimator.messages(messages));
        ConversationWindow compacted =
                new ConversationWindow(messages, Optional.empty(), estimate, request.fixedInputTokens());
        String summary = messages.isEmpty() ? "" : messages.getFirst().text();
        return new CompactionOutcome(
                compacted,
                new CorePayloads.Compaction(
                        "extractive-summary", request.window().estimatedInputTokens(), summary, Optional.empty()));
    }

    private CompactionOutcome compactNative(
            CompactionRequest request, ModelGateway gateway, CancellationToken cancellation) throws Exception {
        if (!gateway.capabilities(request.command().modelRoute()).nativeCompaction()
                || !(gateway instanceof NativeCompactionSupport support)) {
            throw new TurnFailureException("NATIVE_COMPACTION_UNAVAILABLE", "opaque context 无法使用确定性文本压缩");
        }
        NativeCompactionRequest nativeRequest = new NativeCompactionRequest(
                request.command().turn().id(),
                request.command().modelRoute(),
                request.window().providerState().orElseThrow(),
                request.window().messages(),
                request.targetInputTokens());
        NativeCompactionResult result = support.compact(nativeRequest, cancellation);
        // 新 state 已包含所有待发送增量；保留旧消息会重复工具结果。
        ConversationWindow compacted = new ConversationWindow(
                List.of(),
                Optional.of(result.state()),
                Math.addExact(request.fixedInputTokens(), result.estimatedInputTokens()),
                request.fixedInputTokens());
        CorePayloads.Compaction item = new CorePayloads.Compaction(
                "provider-native",
                result.consumedTokens(),
                "Provider 已压缩 opaque conversation state。",
                Optional.of(result.state().digest()));
        return new CompactionOutcome(compacted, item, result.usage());
    }
}
