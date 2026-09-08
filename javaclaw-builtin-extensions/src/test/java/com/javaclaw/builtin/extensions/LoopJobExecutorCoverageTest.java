package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
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
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.TurnOrchestrationPort;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopJobExecutorCoverageTest {
    private static final AgentRoleRef PROFILE = new AgentRoleRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(5, 1_000, 500, 10);

    @Test
    void plannerStopsAfterLimitAndRejectsWaitingOrFrozenIdentityMismatch() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        LoopJobExecutor executor = executor(support, support.turns);
        LoopContracts.Definition definition = confirmationLoop(2, 2);

        assertEquals(
                "iteration-1",
                executor.plan(job(support, definition, checkpoint(1, "", 0), Optional.empty()))
                        .orElseThrow()
                        .unitId());
        assertTrue(executor.plan(job(support, definition, checkpoint(3, "", 0), Optional.empty()))
                .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.plan(job(
                        support,
                        definition,
                        new LoopJobExecutor.Checkpoint(1, "digest", 0, Optional.of(1)),
                        Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.plan(rawJob(support, definition, checkpoint(1, "", 0), Optional.empty(), "different")));
    }

    @Test
    void userConfirmationWaitsWithoutAdvancingAndUsesIsolatedChildThreadIntent() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        LoopJobExecutor executor = executor(support, support.turns);
        ThreadId parent = ThreadId.random();
        ExtensionJob job = job(support, confirmationLoop(2, 2), checkpoint(1, "", 0), Optional.of(parent));

        ExtensionJobStepResult result = execute(executor, job);
        LoopJobExecutor.Checkpoint next = checkpoint(support, result);

        assertEquals(ExecutionState.WAITING_INPUT, result.nextState());
        assertEquals(Optional.of(1), next.awaitingConfirmation());
        assertEquals(1, next.iteration());
        assertEquals(
                com.javaclaw.api.ThreadExecutionIntent.ISOLATED_WRITE,
                support.turns.commands().getFirst().executionIntent());
        assertEquals(Optional.of(parent), support.turns.commands().getFirst().parentThreadId());
        assertTrue(support.turns.commands().getFirst().instruction().contains("第 1 轮"));
    }

    @Test
    void successfulToolEvidenceAdvancesIterationAndConsumesBudget() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        TurnOrchestrationPort evidenceTurns = (command, cancellation) -> {
            cancellation.throwIfCancelled();
            OrchestratedToolEvidence evidence =
                    new OrchestratedToolEvidence("verify", true, support.payloads.encode(Map.of("exitCode", 0)));
            OrchestratedTurnSummary summary =
                    new OrchestratedTurnSummary("verified", 3, 2, 1, Optional.empty(), List.of(evidence));
            return new OrchestratedTurnResult(
                    ThreadId.random(), TurnId.random(), TurnStatus.COMPLETED, support.payloads.encode(summary));
        };
        LoopJobExecutor executor = executor(support, evidenceTurns);
        ExtensionJob job = job(support, toolLoop(), checkpoint(1, "", 0), Optional.empty());

        ExtensionJobStepResult result = execute(executor, job);

        assertEquals(ExecutionState.RUNNING, result.nextState());
        assertEquals(2, checkpoint(support, result).iteration());
        assertEquals(1, shared(support, result).consumption().turns());
        assertEquals(1, shared(support, result).consumption().toolCalls());
    }

    @Test
    void failedTurnAndRepeatedOutputStopBeforeCheckpointCommit() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        support.turns.returnStatuses(TurnStatus.FAILED);
        LoopJobExecutor executor = executor(support, support.turns);
        ExtensionJob failed = job(support, confirmationLoop(2, 2), checkpoint(1, "", 0), Optional.empty());
        assertThrows(IllegalStateException.class, () -> execute(executor, failed));

        BuiltinExtensionTestSupport repeatedSupport = new BuiltinExtensionTestSupport();
        String outputDigest = repeatedSupport
                .payloads
                .encode(Map.of("text", "result:Loop / 第 1 轮"))
                .sha256();
        ExtensionJob repeated =
                job(repeatedSupport, confirmationLoop(2, 1), checkpoint(1, outputDigest, 0), Optional.empty());
        assertThrows(
                IllegalStateException.class, () -> execute(executor(repeatedSupport, repeatedSupport.turns), repeated));
    }

    @Test
    void checkpointValidatesCountersAndOnlyConfirmedWaitingStateCanAdvance() {
        LoopJobExecutor.Checkpoint waiting = new LoopJobExecutor.Checkpoint(2, "digest", 1, Optional.of(2));

        LoopJobExecutor.Checkpoint advanced = waiting.afterConfirmation();

        assertEquals(3, advanced.iteration());
        assertTrue(advanced.awaitingConfirmation().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new LoopJobExecutor.Checkpoint(0, "", 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LoopJobExecutor.Checkpoint(1, "", -1, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new LoopJobExecutor.Checkpoint(1, null, 0, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new LoopJobExecutor.Checkpoint(1, "", 0, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> LoopJobExecutor.Checkpoint.initial().afterConfirmation());
    }

    private static LoopJobExecutor executor(BuiltinExtensionTestSupport support, TurnOrchestrationPort turns) {
        return new LoopJobExecutor(new ExtensionJobRuntimeContext(
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
                com.javaclaw.extension.spi.ScheduleLifecyclePort.unavailable(),
                com.javaclaw.extension.spi.ConversationEvidencePort.unavailable(),
                com.javaclaw.extension.spi.ScheduleDefinitionBindingPort.unavailable()));
    }

    private static ExtensionJobStepResult execute(LoopJobExecutor executor, ExtensionJob job) throws Exception {
        ExtensionJobWorkUnit work = executor.plan(job).orElseThrow();
        ExtensionJob active = active(job);
        return executor.execute(new ExtensionJobExecution(active, unit(active, work)), new CancellationSource());
    }

    private static ExtensionJob job(
            BuiltinExtensionTestSupport support,
            LoopContracts.Definition definition,
            LoopJobExecutor.Checkpoint checkpoint,
            Optional<ThreadId> parentThreadId) {
        return rawJob(support, definition, checkpoint, parentThreadId, definition.id());
    }

    private static ExtensionJob rawJob(
            BuiltinExtensionTestSupport support,
            LoopContracts.Definition definition,
            LoopJobExecutor.Checkpoint checkpoint,
            Optional<ThreadId> parentThreadId,
            String definitionId) {
        var frozen = new OrchestrationContracts.FrozenExecution(
                support.executionSnapshot(PROFILE), parentThreadId, support.payloads.encode(definition), BUDGET);
        var shared = new OrchestrationContracts.ExecutionCheckpoint(
                support.payloads.encode(checkpoint), OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                "loop-job",
                new ExtensionId(BuiltinExtensionIds.LOOP),
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

    private static OrchestrationContracts.ExecutionCheckpoint shared(
            BuiltinExtensionTestSupport support, ExtensionJobStepResult result) {
        return support.payloads.decode(result.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
    }

    private static LoopJobExecutor.Checkpoint checkpoint(
            BuiltinExtensionTestSupport support, ExtensionJobStepResult result) {
        return support.payloads.decode(shared(support, result).domain(), LoopJobExecutor.Checkpoint.class);
    }

    private static LoopJobExecutor.Checkpoint checkpoint(int iteration, String digest, int repeated) {
        return new LoopJobExecutor.Checkpoint(iteration, digest, repeated, Optional.empty());
    }

    private static LoopContracts.Definition confirmationLoop(int maximum, int threshold) {
        LoopContracts.VerificationRule rule = new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return new LoopContracts.Definition("loop", 1, "Loop", "完成目标", "继续执行", maximum, threshold, rule, NOW);
    }

    private static LoopContracts.Definition toolLoop() {
        LoopContracts.VerificationRule rule = new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.TOOL_EXIT_CODE,
                Optional.of("verify"),
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
        return new LoopContracts.Definition("loop", 1, "Loop", "完成目标", "继续执行", 2, 2, rule, NOW);
    }
}
