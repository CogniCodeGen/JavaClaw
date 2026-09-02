package com.javaclaw.server.turn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.runtime.CompactionOutcome;
import com.javaclaw.runtime.ContextCompactor;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.NativeCompactionRequest;
import com.javaclaw.runtime.NativeCompactionResult;
import com.javaclaw.runtime.NativeCompactionSupport;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnFailureException;

/** 优先使用 Provider 原生能力、否则执行确定性摘录的上下文压缩器。 */
public final class BudgetContextCompactor implements ContextCompactor {
    private static final String SUMMARY_PREFIX = "以下是因输入预算压缩的较早对话摘录：\n";

    /** 创建无状态压缩器。 */
    public BudgetContextCompactor() {}

    @Override
    public CompactionOutcome compact(
            TurnExecutionCommand command,
            ConversationWindow window,
            ModelGateway gateway,
            CancellationToken cancellation)
            throws Exception {
        cancellation.throwIfCancelled();
        if (window.providerState().isPresent()) {
            return compactNative(command, window, gateway, cancellation);
        }
        return compactMessages(command, window, cancellation);
    }

    private CompactionOutcome compactNative(
            TurnExecutionCommand command,
            ConversationWindow window,
            ModelGateway gateway,
            CancellationToken cancellation)
            throws Exception {
        if (!gateway.capabilities(command.modelRoute()).nativeCompaction()
                || !(gateway instanceof NativeCompactionSupport nativeSupport)) {
            throw new TurnFailureException("NATIVE_COMPACTION_UNAVAILABLE", "opaque context 无法使用确定性文本压缩");
        }
        NativeCompactionRequest request = new NativeCompactionRequest(
                command.turn().id(),
                command.modelRoute(),
                window.providerState().orElseThrow());
        NativeCompactionResult result = nativeSupport.compact(request, cancellation);
        long messageTokens = estimate(window.messages());
        ConversationWindow compacted =
                new ConversationWindow(window.messages(), Optional.of(result.state()), messageTokens);
        CorePayloads.Compaction item = new CorePayloads.Compaction(
                "provider-native",
                result.consumedTokens(),
                "Provider 已压缩 opaque conversation state。",
                Optional.of(result.state().digest()));
        return new CompactionOutcome(compacted, item);
    }

    private CompactionOutcome compactMessages(
            TurnExecutionCommand command, ConversationWindow window, CancellationToken cancellation) {
        long budget = command.turn().budget().inputTokens();
        long retainedBudget = Math.max(1, budget * 3 / 5);
        List<ModelMessage> retained = retainNewest(window.messages(), retainedBudget, cancellation);
        int omitted = window.messages().size() - retained.size();
        if (omitted < 1) {
            throw new TurnFailureException("CONTEXT_WINDOW_EXCEEDED", "当前消息本身已超过输入预算");
        }
        String summary = summarize(window.messages().subList(0, omitted), budget, cancellation);
        List<ModelMessage> compactedMessages = new ArrayList<>();
        compactedMessages.add(systemSummary(summary));
        compactedMessages.addAll(retained);
        long estimated = estimate(compactedMessages);
        ConversationWindow compacted = new ConversationWindow(compactedMessages, Optional.empty(), estimated);
        CorePayloads.Compaction item = new CorePayloads.Compaction(
                "extractive-summary", window.estimatedInputTokens(), summary, Optional.empty());
        return new CompactionOutcome(compacted, item);
    }

    private static List<ModelMessage> retainNewest(
            List<ModelMessage> messages, long tokenBudget, CancellationToken cancellation) {
        ArrayList<ModelMessage> reversed = new ArrayList<>();
        long used = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            cancellation.throwIfCancelled();
            ModelMessage message = messages.get(index);
            long tokens = estimate(message);
            if (!reversed.isEmpty() && used + tokens > tokenBudget) {
                break;
            }
            reversed.add(message);
            used = Math.addExact(used, tokens);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static String summarize(List<ModelMessage> omitted, long totalBudget, CancellationToken cancellation) {
        int characterLimit = Math.toIntExact(Math.min(16_000, Math.max(256, totalBudget)));
        StringBuilder summary = new StringBuilder(SUMMARY_PREFIX);
        for (ModelMessage message : omitted) {
            cancellation.throwIfCancelled();
            appendLine(summary, message);
            if (summary.length() >= characterLimit) {
                summary.setLength(characterLimit);
                summary.append("…");
                break;
            }
        }
        return summary.toString();
    }

    private static void appendLine(StringBuilder summary, ModelMessage message) {
        summary.append(message.role().name()).append(": ");
        if (!message.text().isEmpty()) {
            summary.append(message.text().replace('\n', ' '));
        }
        if (!message.toolCalls().isEmpty()) {
            summary.append(" [tools=");
            message.toolCalls()
                    .forEach(call -> summary.append(call.tool().name()).append(' '));
            summary.append(']');
        }
        summary.append('\n');
    }

    private static ModelMessage systemSummary(String summary) {
        return new ModelMessage(MessageRole.SYSTEM, summary, List.of(), Optional.empty(), Optional.empty());
    }

    private static long estimate(List<ModelMessage> messages) {
        return messages.stream().mapToLong(BudgetContextCompactor::estimate).sum();
    }

    private static long estimate(ModelMessage message) {
        return Math.max(1, (message.text().length() + 3L) / 4L)
                + message.toolCalls().size() * 32L;
    }
}
