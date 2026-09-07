package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedToolEvidence;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.TurnOrchestrationPort;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SddJobExecutorCoverageTest {
    private static final AgentRoleRef PROFILE = new AgentRoleRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(20, 10_000, 10_000, 20);

    @Test
    void plannerMapsEveryRunnablePhaseAndRejectsWaitingOrPrematureArchive() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.Definition definition = definition();
        SddJobExecutor executor = executor(support, new EvidenceTurns(support));

        assertEquals("proposal", plan(executor, job(support, definition, checkpoint(SddContracts.Phase.PROPOSAL, 0))));
        assertEquals("design", plan(executor, job(support, definition, checkpoint(SddContracts.Phase.DESIGN, 0))));
        assertEquals(
                "implement-1", plan(executor, job(support, definition, checkpoint(SddContracts.Phase.IMPLEMENT, 1))));
        assertEquals(
                "verify-2",
                plan(executor, job(support, definition, checkpointWithAttempts(SddContracts.Phase.VERIFY, 2, 2))));
        assertEquals(
                "remediate-3",
                plan(executor, job(support, definition, checkpointWithAttempts(SddContracts.Phase.REMEDIATE, 2, 2))));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.plan(
                        job(support, definition, checkpoint(SddContracts.Phase.SPECIFICATION_APPROVAL, 0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.plan(job(support, definition, checkpoint(SddContracts.Phase.TASK_APPROVAL, 0))));
        assertThrows(
                IllegalStateException.class,
                () -> executor.plan(job(support, definition, checkpoint(SddContracts.Phase.ARCHIVE, 2))));
        assertTrue(executor.plan(job(
                        support,
                        definition,
                        new SddContracts.Checkpoint(
                                SddContracts.Phase.ARCHIVE,
                                2,
                                Optional.of(definition.specificationDigest()),
                                Optional.of(definition.taskDigest()),
                                0,
                                true)))
                .isEmpty());
    }

    @Test
    void proposalAndDesignWaitForDigestBoundApprovals() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.Definition definition = definition();
        EvidenceTurns turns = new EvidenceTurns(support);
        SddJobExecutor executor = executor(support, turns);

        ExtensionJobStepResult proposal =
                execute(executor, job(support, definition, checkpoint(SddContracts.Phase.PROPOSAL, 0)));
        assertEquals(ExecutionState.WAITING_APPROVAL, proposal.nextState());
        assertEquals(
                SddContracts.Phase.SPECIFICATION_APPROVAL,
                checkpoint(support, proposal).phase());

        assertThrows(
                IllegalStateException.class,
                () -> execute(executor, job(support, definition, checkpoint(SddContracts.Phase.DESIGN, 0))));
        SddContracts.Checkpoint stale = new SddContracts.Checkpoint(
                SddContracts.Phase.DESIGN, 0, Optional.of("a".repeat(64)), Optional.empty(), 0, false);
        assertThrows(IllegalStateException.class, () -> execute(executor, job(support, definition, stale)));

        SddContracts.Checkpoint approved = new SddContracts.Checkpoint(
                SddContracts.Phase.DESIGN,
                0,
                Optional.of(definition.specificationDigest()),
                Optional.empty(),
                0,
                false);
        ExtensionJobStepResult design = execute(executor, job(support, definition, approved));
        assertEquals(
                SddContracts.Phase.TASK_APPROVAL, checkpoint(support, design).phase());
        assertTrue(turns.commands.getFirst().instruction().contains("已审批需求"));
    }

    @Test
    void implementationRequiresBothApprovalsAndAdvancesFrozenTasksOneAtATime() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.Definition definition = definition();
        EvidenceTurns turns = new EvidenceTurns(support);
        SddJobExecutor executor = executor(support, turns);

        SddContracts.Checkpoint missingTasks = new SddContracts.Checkpoint(
                SddContracts.Phase.IMPLEMENT,
                0,
                Optional.of(definition.specificationDigest()),
                Optional.empty(),
                0,
                false);
        assertThrows(IllegalStateException.class, () -> execute(executor, job(support, definition, missingTasks)));

        ExtensionJobStepResult first = execute(executor, job(support, definition, approved(definition, 0)));
        ExtensionJobStepResult last = execute(executor, job(support, definition, approved(definition, 1)));
        assertEquals(SddContracts.Phase.IMPLEMENT, checkpoint(support, first).phase());
        assertEquals(1, checkpoint(support, first).nextTaskIndex());
        assertEquals(SddContracts.Phase.VERIFY, checkpoint(support, last).phase());
        assertEquals(2, checkpoint(support, last).nextTaskIndex());
        assertTrue(turns.commands.stream().anyMatch(command -> command.title().contains("task-0")));

        assertThrows(
                IllegalStateException.class,
                () -> execute(executor, job(support, definition, approved(definition, 2))));
    }

    @Test
    void verificationArchivesOnlyRealPassingEvidenceAndHonorsRemediationLimit() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.Definition definition = definition();
        EvidenceTurns passing = new EvidenceTurns(support);
        SddJobExecutor passExecutor = executor(support, passing);

        ExtensionJobStepResult passed = execute(passExecutor, job(support, definition, verification(definition, 0)));
        assertEquals(SddContracts.Phase.ARCHIVE, checkpoint(support, passed).phase());
        assertTrue(checkpoint(support, passed).verificationPassed());

        EvidenceTurns failing = new EvidenceTurns(support);
        failing.exitCode = 1;
        SddJobExecutor failExecutor = executor(support, failing);
        ExtensionJobStepResult retry = execute(failExecutor, job(support, definition, verification(definition, 0)));
        assertEquals(SddContracts.Phase.REMEDIATE, checkpoint(support, retry).phase());
        assertThrows(
                IllegalStateException.class,
                () -> execute(failExecutor, job(support, definition, verification(definition, 1))));
    }

    @Test
    void remediationReturnsToVerificationAndFailedTurnDoesNotAdvance() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.Definition definition = definition();
        EvidenceTurns turns = new EvidenceTurns(support);
        SddJobExecutor executor = executor(support, turns);
        SddContracts.Checkpoint remediation = new SddContracts.Checkpoint(
                SddContracts.Phase.REMEDIATE,
                2,
                Optional.of(definition.specificationDigest()),
                Optional.of(definition.taskDigest()),
                0,
                false);

        ExtensionJobStepResult result = execute(executor, job(support, definition, remediation));

        assertEquals(SddContracts.Phase.VERIFY, checkpoint(support, result).phase());
        assertEquals(1, checkpoint(support, result).remediationAttempts());
        turns.status = TurnStatus.FAILED;
        assertThrows(
                IllegalStateException.class,
                () -> execute(executor, job(support, definition, approved(definition, 0))));
    }

    @Test
    void executorRejectsFrozenIdentityAndIntentThatDiffersFromCheckpoint() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.Definition definition = definition();
        SddJobExecutor executor = executor(support, new EvidenceTurns(support));
        ExtensionJob wrongIdentity =
                rawJob(support, definition, checkpoint(SddContracts.Phase.PROPOSAL, 0), "different");
        assertThrows(IllegalArgumentException.class, () -> executor.plan(wrongIdentity));

        ExtensionJob job = job(support, definition, checkpoint(SddContracts.Phase.PROPOSAL, 0));
        ExtensionJob active = active(job);
        ExtensionJobWorkUnit stale =
                new ExtensionJobWorkUnit("stale", support.payloads.encode(Map.of("phase", "DESIGN", "taskIndex", 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(
                        new ExtensionJobExecution(active, unit(active, stale)), new CancellationSource()));
    }

    private static SddJobExecutor executor(BuiltinExtensionTestSupport support, TurnOrchestrationPort turns) {
        return new SddJobExecutor(new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                turns,
                support.store,
                invocation -> {
                    throw new IllegalStateException("isolated service is not configured");
                },
                support.embeddings,
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                com.javaclaw.extension.spi.ScheduleLifecyclePort.unavailable()));
    }

    private static ExtensionJobStepResult execute(SddJobExecutor executor, ExtensionJob job) throws Exception {
        ExtensionJobWorkUnit work = executor.plan(job).orElseThrow();
        ExtensionJob active = active(job);
        return executor.execute(new ExtensionJobExecution(active, unit(active, work)), new CancellationSource());
    }

    private static String plan(SddJobExecutor executor, ExtensionJob job) {
        return executor.plan(job).orElseThrow().unitId();
    }

    private static ExtensionJob job(
            BuiltinExtensionTestSupport support,
            SddContracts.Definition definition,
            SddContracts.Checkpoint checkpoint) {
        return rawJob(support, definition, checkpoint, definition.id());
    }

    private static ExtensionJob rawJob(
            BuiltinExtensionTestSupport support,
            SddContracts.Definition definition,
            SddContracts.Checkpoint checkpoint,
            String definitionId) {
        var frozen = new OrchestrationContracts.FrozenExecution(
                support.executionSnapshot(PROFILE), Optional.empty(), support.payloads.encode(definition), BUDGET);
        var shared = new OrchestrationContracts.ExecutionCheckpoint(
                support.payloads.encode(checkpoint), OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                "sdd-job",
                new ExtensionId(BuiltinExtensionIds.SDD),
                support.workspaceId,
                AutomationExecutionResource.JOB_TYPE,
                definitionId,
                definition.revision(),
                support.payloads.encode(frozen),
                ExecutionState.RUNNING,
                1,
                support.payloads.encode(shared),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }

    private static ExtensionJob active(ExtensionJob job) {
        return new ExtensionJob(
                job.id(),
                job.extensionId(),
                job.workspaceId(),
                job.jobType(),
                job.definitionId(),
                job.definitionRevision(),
                job.frozenInput(),
                ExecutionState.RUNNING,
                2,
                job.checkpoint(),
                2,
                Optional.of(1L),
                Optional.empty(),
                job.createdAt(),
                job.updatedAt());
    }

    private static ExtensionJobUnit unit(ExtensionJob job, ExtensionJobWorkUnit work) {
        return new ExtensionJobUnit(
                job.id(),
                1,
                work.unitId(),
                work.intent(),
                ExtensionJobUnitState.INTENT_RECORDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                Optional.empty());
    }

    private static SddContracts.Checkpoint checkpoint(
            BuiltinExtensionTestSupport support, ExtensionJobStepResult result) {
        var shared = support.payloads.decode(result.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        return support.payloads.decode(shared.domain(), SddContracts.Checkpoint.class);
    }

    private static SddContracts.Checkpoint checkpoint(SddContracts.Phase phase, int taskIndex) {
        return new SddContracts.Checkpoint(phase, taskIndex, Optional.empty(), Optional.empty(), 0, false);
    }

    private static SddContracts.Checkpoint checkpointWithAttempts(
            SddContracts.Phase phase, int taskIndex, int attempts) {
        return new SddContracts.Checkpoint(phase, taskIndex, Optional.empty(), Optional.empty(), attempts, false);
    }

    private static SddContracts.Checkpoint approved(SddContracts.Definition definition, int taskIndex) {
        return new SddContracts.Checkpoint(
                SddContracts.Phase.IMPLEMENT,
                taskIndex,
                Optional.of(definition.specificationDigest()),
                Optional.of(definition.taskDigest()),
                0,
                false);
    }

    private static SddContracts.Checkpoint verification(SddContracts.Definition definition, int remediationAttempts) {
        return new SddContracts.Checkpoint(
                SddContracts.Phase.VERIFY,
                2,
                Optional.of(definition.specificationDigest()),
                Optional.of(definition.taskDigest()),
                remediationAttempts,
                false);
    }

    private static SddContracts.Definition definition() {
        SddContracts.VerificationRule rule = new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_EXIT_CODE,
                "verify",
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
        return new SddContracts.Definition(
                "sdd", 1, "Feature", new SddContracts.Content("验收条件", "设计", List.of("任务一", "任务二")), rule, 1, NOW);
    }

    private static final class EvidenceTurns implements TurnOrchestrationPort {
        private final BuiltinExtensionTestSupport support;
        private final List<OrchestratedTurnCommand> commands = new ArrayList<>();
        private TurnStatus status = TurnStatus.COMPLETED;
        private int exitCode;

        private EvidenceTurns(BuiltinExtensionTestSupport support) {
            this.support = support;
        }

        @Override
        public OrchestratedTurnResult execute(OrchestratedTurnCommand command, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            commands.add(command);
            OrchestratedToolEvidence evidence =
                    new OrchestratedToolEvidence("verify", true, support.payloads.encode(Map.of("exitCode", exitCode)));
            OrchestratedTurnSummary summary = new OrchestratedTurnSummary(
                    "result",
                    4,
                    2,
                    1,
                    status == TurnStatus.COMPLETED ? Optional.empty() : Optional.of("FAILED"),
                    List.of(evidence));
            return new OrchestratedTurnResult(
                    ThreadId.random(), TurnId.random(), status, support.payloads.encode(summary));
        }
    }
}
