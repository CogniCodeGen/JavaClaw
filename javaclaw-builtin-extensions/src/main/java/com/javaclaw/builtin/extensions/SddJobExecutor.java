package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;

/** 按固定阶段推进 SDD，并只以两次摘要审批和真实 ToolResult 作为门禁。 */
final class SddJobExecutor implements ExtensionJobExecutor {
    private final ExtensionJobRuntimeContext context;

    SddJobExecutor(ExtensionJobRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
        FrozenSdd frozen = frozen(job);
        SddContracts.Checkpoint checkpoint = checkpoint(job);
        return switch (checkpoint.phase()) {
            case SPECIFICATION_APPROVAL, TASK_APPROVAL ->
                throw new IllegalArgumentException("waiting SDD cannot plan another unit");
            case ARCHIVE -> {
                if (!checkpoint.verificationPassed()) {
                    throw new IllegalStateException("SDD cannot archive before verification passes");
                }
                yield Optional.empty();
            }
            default -> Optional.of(workUnit(checkpoint));
        };
    }

    @Override
    public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
            throws Exception {
        FrozenSdd frozen = frozen(execution.job());
        PhaseIntent intent = context.payloads().decode(execution.unit().intent(), PhaseIntent.class);
        SddContracts.Checkpoint checkpoint = checkpoint(execution.job());
        if (checkpoint.phase() != intent.phase() || checkpoint.nextTaskIndex() != intent.taskIndex()) {
            throw new IllegalArgumentException("SDD intent differs from checkpoint");
        }
        return switch (intent.phase()) {
            case PROPOSAL -> waitForSpecification(execution, checkpoint);
            case DESIGN -> executeDesign(execution, frozen, checkpoint, cancellation);
            case IMPLEMENT -> executeTask(execution, frozen, checkpoint, cancellation);
            case VERIFY -> executeVerification(execution, frozen, checkpoint, cancellation);
            case REMEDIATE -> executeRemediation(execution, frozen, checkpoint, cancellation);
            case SPECIFICATION_APPROVAL, TASK_APPROVAL, ARCHIVE ->
                throw new IllegalArgumentException("SDD waiting or terminal phase cannot execute");
        };
    }

    private ExtensionJobStepResult waitForSpecification(
            ExtensionJobExecution execution, SddContracts.Checkpoint current) {
        SddContracts.Checkpoint next = copy(current, SddContracts.Phase.SPECIFICATION_APPROVAL);
        return result(
                execution,
                next,
                shared(execution.job()).consumption(),
                ExecutionState.WAITING_APPROVAL,
                Optional.empty(),
                "等待规格摘要审批");
    }

    private ExtensionJobStepResult executeDesign(
            ExtensionJobExecution execution,
            FrozenSdd frozen,
            SddContracts.Checkpoint current,
            CancellationToken cancellation)
            throws Exception {
        requireSpecificationApproval(frozen.definition(), current);
        TurnOutcome outcome =
                turn(execution.job(), frozen, "design", designInstruction(frozen.definition()), cancellation);
        SddContracts.Checkpoint next = copy(current, SddContracts.Phase.TASK_APPROVAL);
        return turnResult(execution, frozen, next, outcome, ExecutionState.WAITING_APPROVAL, "等待任务摘要审批");
    }

    private ExtensionJobStepResult executeTask(
            ExtensionJobExecution execution,
            FrozenSdd frozen,
            SddContracts.Checkpoint current,
            CancellationToken cancellation)
            throws Exception {
        requireTaskApproval(frozen.definition(), current);
        List<String> tasks = frozen.definition().content().tasks();
        if (current.nextTaskIndex() >= tasks.size()) {
            throw new IllegalStateException("SDD task checkpoint exceeds frozen tasks");
        }
        int index = current.nextTaskIndex();
        TurnOutcome outcome =
                turn(execution.job(), frozen, "task-" + index, taskInstruction(tasks, index), cancellation);
        int nextIndex = Math.addExact(index, 1);
        SddContracts.Phase phase = nextIndex == tasks.size() ? SddContracts.Phase.VERIFY : SddContracts.Phase.IMPLEMENT;
        SddContracts.Checkpoint next = new SddContracts.Checkpoint(
                phase,
                nextIndex,
                current.specificationApproval(),
                current.taskApproval(),
                current.remediationAttempts(),
                false);
        return turnResult(execution, frozen, next, outcome, ExecutionState.RUNNING, "实施任务完成");
    }

    private ExtensionJobStepResult executeVerification(
            ExtensionJobExecution execution,
            FrozenSdd frozen,
            SddContracts.Checkpoint current,
            CancellationToken cancellation)
            throws Exception {
        TurnOutcome outcome = turn(
                execution.job(),
                frozen,
                "verify-" + current.remediationAttempts(),
                verifyInstruction(frozen.definition()),
                cancellation);
        boolean passed = SddVerification.satisfied(
                outcome.summary().toolEvidence(), frozen.definition().verificationRule(), context.payloads());
        if (!passed && current.remediationAttempts() >= frozen.definition().maximumRemediations()) {
            throw new IllegalStateException("SDD verification failed after remediation limit");
        }
        SddContracts.Phase phase = passed ? SddContracts.Phase.ARCHIVE : SddContracts.Phase.REMEDIATE;
        SddContracts.Checkpoint next = new SddContracts.Checkpoint(
                phase,
                current.nextTaskIndex(),
                current.specificationApproval(),
                current.taskApproval(),
                current.remediationAttempts(),
                passed);
        return turnResult(execution, frozen, next, outcome, ExecutionState.RUNNING, passed ? "验收通过" : "验收未通过");
    }

    private ExtensionJobStepResult executeRemediation(
            ExtensionJobExecution execution,
            FrozenSdd frozen,
            SddContracts.Checkpoint current,
            CancellationToken cancellation)
            throws Exception {
        int attempt = Math.addExact(current.remediationAttempts(), 1);
        TurnOutcome outcome = turn(
                execution.job(),
                frozen,
                "remediate-" + attempt,
                remediationInstruction(frozen.definition(), attempt),
                cancellation);
        SddContracts.Checkpoint next = new SddContracts.Checkpoint(
                SddContracts.Phase.VERIFY,
                current.nextTaskIndex(),
                current.specificationApproval(),
                current.taskApproval(),
                attempt,
                false);
        return turnResult(execution, frozen, next, outcome, ExecutionState.RUNNING, "修复单元完成");
    }

    private ExtensionJobStepResult turnResult(
            ExtensionJobExecution execution,
            FrozenSdd frozen,
            SddContracts.Checkpoint next,
            TurnOutcome outcome,
            ExecutionState state,
            String summary) {
        var consumption = ExecutionBudgetGuard.consume(
                frozen.execution().budget(), shared(execution.job()).consumption(), outcome.summary());
        return result(
                execution,
                next,
                consumption,
                state,
                Optional.of(outcome.result().turnId()),
                summary);
    }

    private ExtensionJobStepResult result(
            ExtensionJobExecution execution,
            SddContracts.Checkpoint checkpoint,
            OrchestrationContracts.ExecutionConsumption consumption,
            ExecutionState state,
            Optional<com.javaclaw.api.TurnId> turnId,
            String summary) {
        PhaseResult domain = new PhaseResult(execution.unit().unitId(), checkpoint.phase(), summary);
        var shared = new OrchestrationContracts.ExecutionCheckpoint(
                context.payloads().encode(checkpoint), consumption);
        return new ExtensionJobStepResult(
                context.payloads().encode(domain), context.payloads().encode(shared), state, turnId, Optional.empty());
    }

    private TurnOutcome turn(
            ExtensionJob job, FrozenSdd frozen, String unitName, String instruction, CancellationToken cancellation)
            throws Exception {
        OrchestratedTurnCommand command = new OrchestratedTurnCommand(
                job.workspaceId(),
                frozen.execution().parentThreadId(),
                frozen.execution().parentThreadId().isPresent()
                        ? ThreadExecutionIntent.ISOLATED_WRITE
                        : ThreadExecutionIntent.WORKSPACE,
                frozen.definition().title() + " / " + unitName,
                frozen.execution().platform(),
                instruction,
                context.payloads().encode(new SddContext(frozen.definition().content(), unitName)),
                "job-" + job.id() + "-" + unitName);
        OrchestratedTurnResult result = context.turns().execute(command, cancellation);
        if (result.status() != TurnStatus.COMPLETED) {
            throw new IllegalStateException("SDD Turn did not complete: " + result.status());
        }
        return new TurnOutcome(result, context.payloads().decode(result.output(), OrchestratedTurnSummary.class));
    }

    private ExtensionJobWorkUnit workUnit(SddContracts.Checkpoint checkpoint) {
        String suffix = unitSuffix(checkpoint);
        PhaseIntent intent = new PhaseIntent(checkpoint.phase(), checkpoint.nextTaskIndex());
        return new ExtensionJobWorkUnit(
                checkpoint.phase().name().toLowerCase(java.util.Locale.ROOT) + suffix,
                context.payloads().encode(intent));
    }

    private static String unitSuffix(SddContracts.Checkpoint checkpoint) {
        return switch (checkpoint.phase()) {
            case IMPLEMENT -> "-" + checkpoint.nextTaskIndex();
            case VERIFY -> "-" + checkpoint.remediationAttempts();
            case REMEDIATE -> "-" + Math.addExact(checkpoint.remediationAttempts(), 1);
            default -> "";
        };
    }

    private FrozenSdd frozen(ExtensionJob job) {
        OrchestrationContracts.FrozenExecution execution =
                context.payloads().decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        SddContracts.Definition definition =
                context.payloads().decode(execution.definition(), SddContracts.Definition.class);
        if (!definition.id().equals(job.definitionId()) || definition.revision() != job.definitionRevision()) {
            throw new IllegalArgumentException("SDD frozen Definition identity differs from Job");
        }
        return new FrozenSdd(execution, definition);
    }

    private SddContracts.Checkpoint checkpoint(ExtensionJob job) {
        return context.payloads().decode(shared(job).domain(), SddContracts.Checkpoint.class);
    }

    private OrchestrationContracts.ExecutionCheckpoint shared(ExtensionJob job) {
        return context.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
    }

    private static SddContracts.Checkpoint copy(SddContracts.Checkpoint current, SddContracts.Phase phase) {
        return new SddContracts.Checkpoint(
                phase,
                current.nextTaskIndex(),
                current.specificationApproval(),
                current.taskApproval(),
                current.remediationAttempts(),
                current.verificationPassed());
    }

    private static void requireSpecificationApproval(
            SddContracts.Definition definition, SddContracts.Checkpoint checkpoint) {
        if (checkpoint
                .specificationApproval()
                .filter(definition.specificationDigest()::equals)
                .isEmpty()) {
            throw new IllegalStateException("SDD specification approval is missing or stale");
        }
    }

    private static void requireTaskApproval(SddContracts.Definition definition, SddContracts.Checkpoint checkpoint) {
        requireSpecificationApproval(definition, checkpoint);
        if (checkpoint.taskApproval().filter(definition.taskDigest()::equals).isEmpty()) {
            throw new IllegalStateException("SDD task approval is missing or stale");
        }
    }

    private static String designInstruction(SddContracts.Definition definition) {
        return "依据已审批需求审查并细化设计，不修改冻结规格。\n需求：" + definition.content().requirements() + "\n设计："
                + definition.content().design();
    }

    private static String taskInstruction(List<String> tasks, int index) {
        return "实施冻结任务 " + (index + 1) + "/" + tasks.size() + "：" + tasks.get(index);
    }

    private static String verifyInstruction(SddContracts.Definition definition) {
        return "执行真实验证工具并产生可核验 ToolResult。验收条件：" + definition.content().requirements() + "。必须调用工具："
                + definition.verificationRule().toolName();
    }

    private static String remediationInstruction(SddContracts.Definition definition, int attempt) {
        return "第 " + attempt + " 次修复未通过的验收项；修复后不要宣称已完成，后续阶段会重新运行真实验证。目标："
                + definition.content().requirements();
    }

    private record PhaseIntent(SddContracts.Phase phase, int taskIndex) {}

    private record PhaseResult(String unitId, SddContracts.Phase nextPhase, String summary) {}

    private record SddContext(SddContracts.Content content, String unitName) {}

    private record TurnOutcome(OrchestratedTurnResult result, OrchestratedTurnSummary summary) {}

    private record FrozenSdd(OrchestrationContracts.FrozenExecution execution, SddContracts.Definition definition) {}
}
