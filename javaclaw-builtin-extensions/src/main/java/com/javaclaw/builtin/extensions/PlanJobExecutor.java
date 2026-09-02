package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;

/** Plan 每次只执行一个已持久化步骤的 Job executor。 */
final class PlanJobExecutor implements ExtensionJobExecutor {
    private final ExtensionJobRuntimeContext context;

    PlanJobExecutor(ExtensionJobRuntimeContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
        FrozenPlan frozen = frozen(job);
        OrchestrationContracts.ExecutionCheckpoint shared = sharedCheckpoint(job);
        Checkpoint checkpoint = context.payloads().decode(shared.domain(), Checkpoint.class);
        List<PlanContracts.Step> steps = PlanExtension.executionOrder(frozen.definition());
        if (checkpoint.nextStepIndex() >= steps.size()) {
            return Optional.empty();
        }
        PlanContracts.Step step = steps.get(checkpoint.nextStepIndex());
        StepIntent intent = new StepIntent(checkpoint.nextStepIndex(), step);
        return Optional.of(
                new ExtensionJobWorkUnit(step.id(), context.payloads().encode(intent)));
    }

    @Override
    public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
            throws Exception {
        FrozenPlan frozen = frozen(execution.job());
        StepIntent intent = context.payloads().decode(execution.unit().intent(), StepIntent.class);
        OrchestratedTurnCommand command = command(execution.job(), frozen, intent.step());
        OrchestratedTurnResult turn = context.turns().execute(command, cancellation);
        if (turn.status() != TurnStatus.COMPLETED) {
            throw new IllegalStateException("Plan step Turn did not complete: " + turn.status());
        }
        OrchestratedTurnSummary summary = context.payloads().decode(turn.output(), OrchestratedTurnSummary.class);
        Checkpoint checkpoint = new Checkpoint(Math.addExact(intent.stepIndex(), 1));
        OrchestrationContracts.ExecutionCheckpoint shared = sharedCheckpoint(execution.job());
        var consumption = ExecutionBudgetGuard.consume(frozen.execution().budget(), shared.consumption(), summary);
        OrchestrationContracts.UnitResult result = new OrchestrationContracts.UnitResult(
                execution.unit().unitId(), turn.turnId(), summary.assistantText());
        return new ExtensionJobStepResult(
                context.payloads().encode(result),
                context.payloads()
                        .encode(new OrchestrationContracts.ExecutionCheckpoint(
                                context.payloads().encode(checkpoint), consumption)),
                ExecutionState.RUNNING,
                Optional.of(turn.turnId()),
                Optional.empty());
    }

    private OrchestratedTurnCommand command(ExtensionJob job, FrozenPlan frozen, PlanContracts.Step step) {
        String instruction = step.instruction() + "\n\n验收条件：" + step.acceptanceCriteria() + "\n计划目标："
                + frozen.definition().objective();
        return new OrchestratedTurnCommand(
                job.workspaceId(),
                frozen.execution().parentThreadId(),
                frozen.execution().parentThreadId().isPresent()
                        ? com.javaclaw.api.ThreadExecutionIntent.ISOLATED_WRITE
                        : com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                frozen.definition().title() + " / " + step.title(),
                frozen.execution().platform(),
                instruction,
                context.payloads().encode(step),
                unitKey(job, step.id()));
    }

    private FrozenPlan frozen(ExtensionJob job) {
        OrchestrationContracts.FrozenExecution execution =
                context.payloads().decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        PlanContracts.Definition definition =
                context.payloads().decode(execution.definition(), PlanContracts.Definition.class);
        if (!definition.id().equals(job.definitionId()) || definition.revision() != job.definitionRevision()) {
            throw new IllegalArgumentException("Plan frozen Definition identity differs from Job");
        }
        return new FrozenPlan(execution, definition);
    }

    private OrchestrationContracts.ExecutionCheckpoint sharedCheckpoint(ExtensionJob job) {
        return context.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
    }

    private static String unitKey(ExtensionJob job, String unitId) {
        return "job-" + job.id() + "-unit-" + unitId;
    }

    /**
     * Plan 恢复指针。
     *
     * @param nextStepIndex 下一个拓扑步骤下标
     */
    record Checkpoint(int nextStepIndex) {
        Checkpoint {
            if (nextStepIndex < 0) {
                throw new IllegalArgumentException("nextStepIndex must not be negative");
            }
        }
    }

    private record StepIntent(int stepIndex, PlanContracts.Step step) {}

    private record FrozenPlan(OrchestrationContracts.FrozenExecution execution, PlanContracts.Definition definition) {}
}
