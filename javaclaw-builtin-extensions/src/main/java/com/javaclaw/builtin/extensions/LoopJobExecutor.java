package com.javaclaw.builtin.extensions;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;

/** Loop 每次只推进一个迭代，并只接受平台持久证据或用户确认。 */
final class LoopJobExecutor implements ExtensionJobExecutor {
    private final ExtensionJobRuntimeContext context;

    LoopJobExecutor(ExtensionJobRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
        FrozenLoop frozen = frozen(job);
        OrchestrationContracts.ExecutionCheckpoint shared = sharedCheckpoint(job);
        Checkpoint checkpoint = context.payloads().decode(shared.domain(), Checkpoint.class);
        if (checkpoint.awaitingConfirmation().isPresent()) {
            throw new IllegalArgumentException("等待人工确认的 Loop 不能规划工作单元");
        }
        if (checkpoint.iteration() > frozen.definition().maximumIterations()) {
            return Optional.empty();
        }
        IterationIntent intent = new IterationIntent(checkpoint.iteration());
        return Optional.of(new ExtensionJobWorkUnit(
                "iteration-" + checkpoint.iteration(), context.payloads().encode(intent)));
    }

    @Override
    public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
            throws Exception {
        FrozenLoop frozen = frozen(execution.job());
        IterationIntent intent = context.payloads().decode(execution.unit().intent(), IterationIntent.class);
        OrchestratedTurnResult turn =
                context.turns().execute(command(execution.job(), frozen, intent.iteration()), cancellation);
        if (turn.status() != TurnStatus.COMPLETED) {
            throw new IllegalStateException("Loop Turn did not complete: " + turn.status());
        }
        OrchestratedTurnSummary summary = context.payloads().decode(turn.output(), OrchestratedTurnSummary.class);
        OrchestrationContracts.ExecutionCheckpoint shared = sharedCheckpoint(execution.job());
        Checkpoint current = context.payloads().decode(shared.domain(), Checkpoint.class);
        Checkpoint progress = progress(current, summary, frozen.definition(), intent.iteration());
        var consumption = ExecutionBudgetGuard.consume(frozen.execution().budget(), shared.consumption(), summary);
        ExecutionState nextState = verify(summary, frozen.definition().verificationRule())
                ? ExecutionState.RUNNING
                : ExecutionState.WAITING_INPUT;
        OrchestrationContracts.UnitResult result = new OrchestrationContracts.UnitResult(
                execution.unit().unitId(), turn.turnId(), summary.assistantText());
        return new ExtensionJobStepResult(
                context.payloads().encode(result),
                context.payloads()
                        .encode(new OrchestrationContracts.ExecutionCheckpoint(
                                context.payloads().encode(progress), consumption)),
                nextState,
                Optional.of(turn.turnId()),
                Optional.empty());
    }

    private boolean verify(OrchestratedTurnSummary summary, LoopContracts.VerificationRule rule) {
        if (rule.kind() == LoopContracts.VerificationKind.USER_CONFIRMATION) {
            return false;
        }
        LoopVerification.requireSatisfied(summary.toolEvidence(), rule, context.payloads());
        return true;
    }

    private Checkpoint progress(
            Checkpoint current, OrchestratedTurnSummary summary, LoopContracts.Definition definition, int iteration) {
        String digest = context.payloads()
                .encode(new OutputDigestInput(summary.assistantText()))
                .sha256();
        int repeated =
                digest.equals(current.previousOutputDigest()) ? Math.addExact(current.consecutiveNoProgress(), 1) : 0;
        if (repeated >= definition.noProgressThreshold()) {
            throw new IllegalStateException("Loop reached its no-progress threshold");
        }
        Optional<Integer> awaiting =
                definition.verificationRule().kind() == LoopContracts.VerificationKind.USER_CONFIRMATION
                        ? Optional.of(iteration)
                        : Optional.empty();
        int nextIteration = awaiting.isPresent() ? iteration : Math.addExact(iteration, 1);
        return new Checkpoint(nextIteration, digest, repeated, awaiting);
    }

    private OrchestratedTurnCommand command(ExtensionJob job, FrozenLoop frozen, int iteration) {
        LoopContracts.Definition definition = frozen.definition();
        String instruction = definition.instruction() + "\n\n目标：" + definition.objective() + "\n这是第 " + iteration
                + " 轮，共 " + definition.maximumIterations() + " 轮。";
        return new OrchestratedTurnCommand(
                job.workspaceId(),
                frozen.execution().parentThreadId(),
                frozen.execution().parentThreadId().isPresent()
                        ? com.javaclaw.api.ThreadExecutionIntent.ISOLATED_WRITE
                        : com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                definition.name() + " / 第 " + iteration + " 轮",
                frozen.execution().platform(),
                instruction,
                context.payloads().encode(new IterationContext(definition, iteration)),
                "job-" + job.id() + "-iteration-" + iteration);
    }

    private FrozenLoop frozen(ExtensionJob job) {
        OrchestrationContracts.FrozenExecution execution =
                context.payloads().decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        LoopContracts.Definition definition =
                context.payloads().decode(execution.definition(), LoopContracts.Definition.class);
        if (!definition.id().equals(job.definitionId()) || definition.revision() != job.definitionRevision()) {
            throw new IllegalArgumentException("Loop frozen Definition identity differs from Job");
        }
        return new FrozenLoop(execution, definition);
    }

    private OrchestrationContracts.ExecutionCheckpoint sharedCheckpoint(ExtensionJob job) {
        return context.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
    }

    /**
     * Loop 恢复指针。
     *
     * @param iteration 当前或下一轮
     * @param previousOutputDigest 上一轮输出摘要；首轮前为空
     * @param consecutiveNoProgress 连续相同输出次数
     * @param awaitingConfirmation 等待人工确认的轮次
     */
    record Checkpoint(
            int iteration,
            String previousOutputDigest,
            int consecutiveNoProgress,
            Optional<Integer> awaitingConfirmation) {
        Checkpoint {
            if (iteration < 1 || consecutiveNoProgress < 0) {
                throw new IllegalArgumentException("Loop checkpoint counters are invalid");
            }
            previousOutputDigest = Objects.requireNonNull(previousOutputDigest, "previousOutputDigest");
            awaitingConfirmation = Objects.requireNonNull(awaitingConfirmation, "awaitingConfirmation");
        }

        static Checkpoint initial() {
            return new Checkpoint(1, "", 0, Optional.empty());
        }

        Checkpoint afterConfirmation() {
            if (awaitingConfirmation.isEmpty()) {
                throw new IllegalArgumentException("Loop is not waiting for confirmation");
            }
            return new Checkpoint(
                    Math.addExact(iteration, 1), previousOutputDigest, consecutiveNoProgress, Optional.empty());
        }
    }

    private record IterationIntent(int iteration) {}

    private record IterationContext(LoopContracts.Definition definition, int iteration) {}

    private record OutputDigestInput(String text) {}

    private record FrozenLoop(OrchestrationContracts.FrozenExecution execution, LoopContracts.Definition definition) {}
}
