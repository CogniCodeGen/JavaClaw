package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;
import com.javaclaw.extension.spi.ScheduledCommand;

/** 在 Extension Job Supervisor 中执行一个冻结 Schedule Occurrence，Quartz 不运行目标业务。 */
final class ScheduleOccurrenceJobExecutor implements ExtensionJobExecutor {
    static final String JOB_TYPE = "schedule-occurrence";

    private final ExtensionJobRuntimeContext context;

    ScheduleOccurrenceJobExecutor(ExtensionJobRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
        ScheduleContracts.OccurrenceCheckpoint checkpoint = checkpoint(job);
        if (checkpoint.completed()) {
            return Optional.empty();
        }
        return Optional.of(new ExtensionJobWorkUnit(
                "dispatch-" + job.definitionRevision(),
                context.payloads().encode(new DispatchIntent(job.definitionId(), job.definitionRevision()))));
    }

    @Override
    public ExtensionJobStepResult execute(ExtensionJobExecution execution, CancellationToken cancellation)
            throws Exception {
        ScheduleContracts.ScheduledExecution frozen = frozen(execution.job());
        requireIntent(execution, frozen);
        try {
            return switch (frozen.definition().target().kind()) {
                case DEFINITION -> dispatchDefinition(execution, frozen, cancellation);
                case TURN_TEMPLATE -> executeTurn(execution, frozen, cancellation);
                case ACTION -> executeAction(execution, frozen, cancellation);
            };
        } catch (Exception failure) {
            failOccurrence(execution.job(), frozen.occurrenceId());
            throw failure;
        }
    }

    private ExtensionJobStepResult dispatchDefinition(
            ExtensionJobExecution execution,
            ScheduleContracts.ScheduledExecution frozen,
            CancellationToken cancellation)
            throws Exception {
        ScheduleContracts.DefinitionTarget target =
                frozen.definition().target().definition().orElseThrow();
        var start = new OrchestrationContracts.StartRequest(target.definitionId(), target.execution(), target.budget());
        var response = context.scheduledCommands()
                .execute(
                        new ScheduledCommand(
                                execution.job().workspaceId(),
                                target.extensionId(),
                                "execution/start",
                                context.payloads().encode(start),
                                key(frozen.occurrenceId()),
                                target.definitionRevision(),
                                Optional.empty(),
                                Optional.of(scope(execution.job(), frozen))),
                        cancellation);
        ExtensionExecutionReceipt targetJob =
                context.payloads().decode(response.payload(), ExtensionExecutionReceipt.class);
        transition(
                execution.job(),
                frozen.occurrenceId(),
                ScheduleContracts.OccurrenceState.RUNNING,
                Optional.of(targetJob.id()),
                Optional.empty());
        return completed(execution, "已投递固定 Definition revision", Optional.empty(), Optional.empty());
    }

    private ExtensionJobStepResult executeTurn(
            ExtensionJobExecution execution,
            ScheduleContracts.ScheduledExecution frozen,
            CancellationToken cancellation)
            throws Exception {
        ScheduleContracts.TurnTemplate target =
                frozen.definition().target().turnTemplate().orElseThrow();
        OrchestratedTurnResult turn = context.turns()
                .execute(
                        new OrchestratedTurnCommand(
                                execution.job().workspaceId(),
                                Optional.empty(),
                                ThreadExecutionIntent.WORKSPACE,
                                target.title(),
                                frozen.executionSnapshot().orElseThrow(),
                                target.instruction(),
                                context.payloads().encode(Map.of("occurrenceId", frozen.occurrenceId())),
                                key(frozen.occurrenceId())),
                        cancellation);
        if (turn.status() != TurnStatus.COMPLETED) {
            throw new IllegalStateException("scheduled Turn did not complete: " + turn.status());
        }
        OrchestratedTurnSummary summary = context.payloads().decode(turn.output(), OrchestratedTurnSummary.class);
        OrchestrationContracts.ExecutionConsumption consumption = ExecutionBudgetGuard.consume(
                target.budget(), shared(execution.job()).consumption(), summary);
        transition(
                execution.job(),
                frozen.occurrenceId(),
                ScheduleContracts.OccurrenceState.COMPLETED,
                Optional.of(execution.job().id()),
                Optional.empty());
        return completed(execution, "固定 Turn 已完成", Optional.of(turn.turnId()), Optional.of(consumption));
    }

    private ExtensionJobStepResult executeAction(
            ExtensionJobExecution execution,
            ScheduleContracts.ScheduledExecution frozen,
            CancellationToken cancellation)
            throws Exception {
        ScheduleActionContracts.Target target =
                frozen.definition().target().action().orElseThrow();
        context.scheduledCommands()
                .execute(
                        new ScheduledCommand(
                                execution.job().workspaceId(),
                                target.extensionId(),
                                target.operation(),
                                ScheduleActionParameters.payload(target, context.payloads()),
                                key(frozen.occurrenceId()),
                                target.expectedRevision(),
                                Optional.of(target.schemaHash()),
                                Optional.of(scope(execution.job(), frozen))),
                        cancellation);
        transition(
                execution.job(),
                frozen.occurrenceId(),
                ScheduleContracts.OccurrenceState.COMPLETED,
                Optional.of(execution.job().id()),
                Optional.empty());
        return completed(execution, "SchedulableAction 已完成", Optional.empty(), Optional.empty());
    }

    private ExtensionJobStepResult completed(
            ExtensionJobExecution execution,
            String summary,
            Optional<com.javaclaw.api.TurnId> turnId,
            Optional<OrchestrationContracts.ExecutionConsumption> consumption) {
        OrchestrationContracts.ExecutionCheckpoint current = shared(execution.job());
        OrchestrationContracts.ExecutionCheckpoint checkpoint = new OrchestrationContracts.ExecutionCheckpoint(
                context.payloads().encode(new ScheduleContracts.OccurrenceCheckpoint(true)),
                consumption.orElse(current.consumption()));
        return new ExtensionJobStepResult(
                context.payloads().encode(new DispatchResult(execution.unit().unitId(), summary)),
                context.payloads().encode(checkpoint),
                ExecutionState.RUNNING,
                turnId,
                Optional.empty());
    }

    private void failOccurrence(ExtensionJob job, String occurrenceId) {
        try {
            transition(
                    job,
                    occurrenceId,
                    ScheduleContracts.OccurrenceState.FAILED,
                    Optional.of(job.id()),
                    Optional.of("TARGET_FAILED"));
        } catch (Exception ignored) {
            // 原异常必须保留；重启 Reconciler 会再次收敛仍处于活动状态的 Occurrence。
        }
    }

    private void transition(
            ExtensionJob job,
            String occurrenceId,
            ScheduleContracts.OccurrenceState state,
            Optional<String> targetJobId,
            Optional<String> reason)
            throws Exception {
        ScheduleOccurrenceStore.transition(
                context.managedStore(),
                context.payloads(),
                job.workspaceId(),
                occurrenceId,
                new ScheduleContracts.OccurrenceStatus(state, targetJobId, reason),
                context.clock().instant());
    }

    private ScheduleContracts.ScheduledExecution frozen(ExtensionJob job) {
        ScheduleContracts.ScheduledExecution value =
                context.payloads().decode(job.frozenInput(), ScheduleContracts.ScheduledExecution.class);
        if (!value.definition().id().equals(job.definitionId())
                || value.definition().revision() != job.definitionRevision()) {
            throw new IllegalArgumentException("Schedule frozen Definition identity differs from Job");
        }
        return value;
    }

    private ScheduleContracts.OccurrenceCheckpoint checkpoint(ExtensionJob job) {
        return context.payloads().decode(shared(job).domain(), ScheduleContracts.OccurrenceCheckpoint.class);
    }

    private OrchestrationContracts.ExecutionCheckpoint shared(ExtensionJob job) {
        return context.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
    }

    private UnattendedExecutionScope scope(ExtensionJob job, ScheduleContracts.ScheduledExecution frozen) {
        UnattendedExecutionScope expected = new UnattendedExecutionScope(
                job.workspaceId(), frozen.definition().id(), frozen.definition().revision(), frozen.occurrenceId());
        frozen.executionSnapshot().ifPresent(snapshot -> {
            if (snapshot.unattendedExecutionScope().filter(expected::equals).isEmpty()) {
                throw new IllegalArgumentException("Schedule Turn snapshot lacks its exact unattended scope");
            }
        });
        return expected;
    }

    private void requireIntent(ExtensionJobExecution execution, ScheduleContracts.ScheduledExecution frozen) {
        DispatchIntent intent = context.payloads().decode(execution.unit().intent(), DispatchIntent.class);
        if (!intent.scheduleId().equals(frozen.definition().id())
                || intent.scheduleRevision() != frozen.definition().revision()) {
            throw new IllegalArgumentException("Schedule dispatch intent differs from frozen Definition");
        }
    }

    private static String key(String occurrenceId) {
        return "schedule-occurrence-" + occurrenceId + "-target";
    }

    private record DispatchIntent(String scheduleId, long scheduleRevision) {}

    private record DispatchResult(String unitId, String summary) {}
}
