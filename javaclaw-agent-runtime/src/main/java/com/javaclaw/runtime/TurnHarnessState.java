package com.javaclaw.runtime;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** DefaultTurnHarness 的单次执行可变状态；实例不跨执行线程共享。 */
final class TurnHarnessState {
    private final TurnExecutionCommand command;
    private final BudgetAccount budget;
    private final StringBuilder assistant = new StringBuilder();
    private final Set<String> callIds = new HashSet<>();
    private ConversationWindow window;
    private ModelUsage usage;
    private TurnExecutionPhase phase;
    private TurnToolBatch toolBatch;
    private int nextToolIndex;
    private int modelInvocations;
    private boolean finalizationAttempted;

    TurnHarnessState(TurnExecutionCommand command, TurnRecoverySnapshot recovery, Clock clock) {
        this.command = Objects.requireNonNull(command, "command");
        TurnRecoverySnapshot restored = Objects.requireNonNull(recovery, "recovery");
        budget = new BudgetAccount(
                command.turn().budget(), clock, command.turn().createdAt(), restored.usage(), restored.toolCalls());
        assistant.append(restored.assistantText());
        callIds.addAll(restored.seenCallIds());
        usage = restored.usage();
        phase = restored.phase();
        toolBatch = restored.toolBatch();
        nextToolIndex = restored.nextToolIndex();
        modelInvocations = restored.modelInvocations();
    }

    TurnExecutionCommand command() {
        return command;
    }

    BudgetAccount budget() {
        return budget;
    }

    ConversationWindow window() {
        return Objects.requireNonNull(window, "window");
    }

    Optional<ConversationWindow> windowOptional() {
        return Optional.ofNullable(window);
    }

    void window(ConversationWindow value) {
        window = Objects.requireNonNull(value, "value");
    }

    ModelUsage usage() {
        return usage;
    }

    void consume(ModelUsage value) {
        budget.consume(value);
        usage = usage.plus(value);
    }

    boolean observeUsage(ModelUsage value) {
        usage = usage.plus(value);
        return budget.recordObservedUsage(value);
    }

    boolean beginFinalization() {
        if (finalizationAttempted) {
            return false;
        }
        finalizationAttempted = true;
        return true;
    }

    StringBuilder assistant() {
        return assistant;
    }

    Set<String> callIds() {
        return callIds;
    }

    TurnExecutionPhase phase() {
        return phase;
    }

    void modelCommitted(ModelInvocationResult result) {
        phase = result.toolCalls().isEmpty() ? TurnExecutionPhase.MODEL_COMMITTED : TurnExecutionPhase.TOOLS_READY;
        toolBatch = new TurnToolBatch(result.toolCalls());
        nextToolIndex = 0;
    }

    List<ModelToolCall> pendingToolCalls() {
        return toolBatch.calls().subList(nextToolIndex, toolBatch.calls().size());
    }

    int nextToolIndex() {
        return nextToolIndex;
    }

    boolean toolBudgetAlreadyConsumed() {
        return phase == TurnExecutionPhase.TOOL_APPROVAL_RESOLVED;
    }

    void toolCommitted() {
        nextToolIndex = Math.addExact(nextToolIndex, 1);
        phase = nextToolIndex == toolBatch.calls().size()
                ? TurnExecutionPhase.READY_FOR_MODEL
                : TurnExecutionPhase.TOOLS_READY;
    }

    int nextModelInvocation() {
        modelInvocations = Math.addExact(modelInvocations, 1);
        return modelInvocations;
    }

    void appendMessages(List<ModelMessage> messages) {
        long estimate = ContextTokenEstimator.messages(messages);
        window = window().append(messages, estimate);
    }
}
