package com.javaclaw.runtime;

import java.util.List;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ToolDescriptor;

/** 在每次模型请求前校验窗口；压缩不能返还累计费用，原生调用必须先提交恢复证据。 */
final class TurnContextPreparation {
    private final TurnHarnessServices services;

    TurnContextPreparation(TurnHarnessServices services) {
        this.services = services;
    }

    void prepare(TurnHarnessState state, List<ToolDescriptor> tools, CancellationToken cancellation) throws Exception {
        ModelContextPolicy policy = state.command().contextPolicy();
        long output = policy.outputAllowance(state.budget().remainingOutputTokens());
        if (output < 1) {
            throw new BudgetExceededException("output token budget exhausted");
        }
        long capacity = policy.inputCapacity(output);
        long hardLimit = Math.min(capacity, state.budget().remainingInputTokens());
        long fixed = ContextTokenEstimator.fixed(state.command().instructions(), tools);
        ConversationWindow window = state.window();
        long estimate = window.providerState().isPresent()
                ? Math.addExact(window.estimatedInputTokens() - window.fixedInputTokens(), fixed)
                : Math.max(window.estimatedInputTokens(), fixed + ContextTokenEstimator.messages(window.messages()));
        state.window(new ConversationWindow(window.messages(), window.providerState(), estimate, fixed));
        if (estimate <= percentage(capacity, 85) && estimate <= hardLimit) {
            return;
        }
        long target =
                Math.min(percentage(capacity, 60), percentage(state.budget().remainingInputTokens(), 80));
        if (target <= fixed) {
            if (estimate <= hardLimit) {
                return;
            }
            throw new BudgetExceededException("fixed context exceeds available input budget");
        }
        compact(state, Math.max(1, target), fixed, cancellation);
        long remaining = Math.min(
                policy.inputCapacity(policy.outputAllowance(state.budget().remainingOutputTokens())),
                state.budget().remainingInputTokens());
        if (state.window().estimatedInputTokens() > remaining) {
            throw new BudgetExceededException("compacted context still exceeds input budget");
        }
    }

    private void compact(TurnHarnessState state, long target, long fixed, CancellationToken cancellation)
            throws Exception {
        CompactionRequest request = new CompactionRequest(state.command(), state.window(), target, fixed);
        boolean nativeCall = state.window().providerState().isPresent();
        if (nativeCall
                && (state.window().estimatedInputTokens() > state.budget().remainingInputTokens() - target
                        || state.budget().remainingOutputTokens() < 2)) {
            throw new BudgetExceededException("native compaction cannot leave enough budget for continuation");
        }
        CompactionOutcome outcome =
                nativeCall ? compactNative(state, request, cancellation) : compactLocal(state, request, cancellation);
        boolean withinBudget = state.observeUsage(outcome.usage());
        state.window(outcome.window());
        if (!withinBudget) {
            throw new BudgetExceededException("native compaction exceeded the frozen budget");
        }
    }

    private long percentage(long value, int percent) {
        return value / 100 * percent + value % 100 * percent / 100;
    }

    private CompactionOutcome compactLocal(
            TurnHarnessState state, CompactionRequest request, CancellationToken cancellation) throws Exception {
        CompactionTicket ticket = services.journal().recordCompactionIntent(request, false);
        CompactionOutcome outcome = services.compactor().compact(request, services.models(), cancellation);
        services.journal()
                .commitCompaction(request, ticket, outcome, state.usage().plus(outcome.usage()));
        return outcome;
    }

    private CompactionOutcome compactNative(
            TurnHarnessState state, CompactionRequest request, CancellationToken cancellation) throws Exception {
        long input = Math.addExact(request.window().estimatedInputTokens(), request.targetInputTokens());
        try (var reservation = state.budget().reserveModel(input, state.budget().remainingOutputTokens())) {
            CompactionTicket ticket = services.journal().recordCompactionIntent(request, true);
            try {
                CompactionOutcome outcome = services.compactor().compact(request, services.models(), cancellation);
                services.journal()
                        .commitCompaction(
                                request, ticket, outcome, state.usage().plus(outcome.usage()));
                return outcome;
            } catch (Exception failure) {
                TurnFailureException unknown = new TurnFailureException("UNKNOWN_OUTCOME", "原生压缩结果未能持久确认，不能自动重试");
                unknown.addSuppressed(failure);
                throw unknown;
            }
        }
    }
}
