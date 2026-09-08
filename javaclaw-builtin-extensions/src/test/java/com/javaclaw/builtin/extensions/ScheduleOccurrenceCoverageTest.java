package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ManagedExtensionStore;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.extension.spi.ScheduledCommandPort;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleOccurrenceCoverageTest {
    private static final AgentRoleRef PROFILE = new AgentRoleRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(4, 1_000, 1_000, 4);

    @Test
    void occurrenceStoreDeduplicatesSkipsOverlapFiltersAndEnforcesTransitions() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleContracts.Definition alpha = definition("alpha", turnTarget());
        ScheduleContracts.Definition beta = definition("beta", turnTarget());

        ScheduleContracts.Occurrence first = create(support, alpha, "first");
        ScheduleContracts.Occurrence duplicate = create(support, alpha, "first");
        ScheduleContracts.Occurrence overlap = create(support, alpha, "overlap");
        ScheduleContracts.Occurrence other = create(support, beta, "other");

        assertOccurrenceQueries(support, first, duplicate, overlap, other);
        assertOccurrenceTransitions(support, first, overlap);
        assertThrows(IllegalArgumentException.class, () -> create(support, beta, "  "));
    }

    private static void assertOccurrenceQueries(
            BuiltinExtensionTestSupport support,
            ScheduleContracts.Occurrence first,
            ScheduleContracts.Occurrence duplicate,
            ScheduleContracts.Occurrence overlap,
            ScheduleContracts.Occurrence other)
            throws Exception {
        assertEquals(first, duplicate);
        assertEquals(ScheduleContracts.OccurrenceState.PENDING, first.status().state());
        assertEquals(ScheduleContracts.OccurrenceState.SKIPPED, overlap.status().state());
        assertEquals(
                2,
                ScheduleOccurrenceStore.active(support.store, support.payloads, support.workspaceId)
                        .size());
        ScheduleContracts.OccurrencePage filtered = ScheduleOccurrenceStore.list(
                support.store,
                support.payloads,
                support.workspaceId,
                new ScheduleContracts.OccurrenceQuery(Optional.of("alpha"), "", 10));
        assertEquals(2, filtered.occurrences().size());
        assertEquals("", filtered.nextKey());
        assertEquals(
                1,
                ScheduleOccurrenceStore.list(
                                support.store,
                                support.payloads,
                                support.workspaceId,
                                new ScheduleContracts.OccurrenceQuery(Optional.of("beta"), "", 1))
                        .occurrences()
                        .size());
        assertTrue(other.identity().id().startsWith("occ-"));
    }

    private static void assertOccurrenceTransitions(
            BuiltinExtensionTestSupport support,
            ScheduleContracts.Occurrence first,
            ScheduleContracts.Occurrence overlap)
            throws Exception {
        ScheduleContracts.Occurrence dispatched = transition(
                support,
                first.identity().id(),
                status(ScheduleContracts.OccurrenceState.DISPATCHED, Optional.of("job"), Optional.empty()));
        assertEquals(dispatched, transition(support, first.identity().id(), dispatched.status()));
        transition(
                support,
                first.identity().id(),
                status(ScheduleContracts.OccurrenceState.RUNNING, Optional.of("job"), Optional.empty()));
        transition(
                support,
                first.identity().id(),
                status(ScheduleContracts.OccurrenceState.COMPLETED, Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> transition(
                        support,
                        first.identity().id(),
                        status(ScheduleContracts.OccurrenceState.CANCELLED, Optional.empty(), Optional.empty())));
        assertThrows(
                IllegalArgumentException.class,
                () -> transition(
                        support,
                        overlap.identity().id(),
                        status(ScheduleContracts.OccurrenceState.FAILED, Optional.empty(), Optional.of("late"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> transition(
                        support,
                        "missing",
                        status(ScheduleContracts.OccurrenceState.CANCELLED, Optional.empty(), Optional.empty())));
        assertFalse(ScheduleOccurrenceStore.active(support.store, support.payloads, support.workspaceId).stream()
                .anyMatch(value -> value.identity().id().equals(first.identity().id())));
    }

    @Test
    void definitionTargetDispatchesFrozenRevisionAndMarksOccurrenceRunning() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        RecordingCommands commands = new RecordingCommands(support);
        ScheduleContracts.Definition definition = definition(
                "definition",
                ScheduleContracts.Target.definition(new ScheduleContracts.DefinitionTarget(
                        "javaclaw.plan", "plan", 3, AutomationV6Fixtures.selection(PROFILE), BUDGET)));
        ExtensionJob job = dispatchedJob(support, definition, Optional.empty());
        ScheduleOccurrenceJobExecutor executor = executor(support, commands);

        ExtensionJobStepResult result = execute(executor, job);

        assertTrue(checkpoint(support, result).completed());
        assertEquals("javaclaw.plan", commands.calls.getFirst().extensionId());
        assertEquals("execution/start", commands.calls.getFirst().operation());
        assertEquals(3, commands.calls.getFirst().expectedRevision());
        assertEquals(
                occurrenceId(job),
                commands.calls
                        .getFirst()
                        .unattendedExecutionScope()
                        .orElseThrow()
                        .occurrenceId());
        assertEquals(
                ScheduleContracts.OccurrenceState.RUNNING,
                occurrence(support, occurrenceId(job)).status().state());
    }

    @Test
    void turnTargetConsumesBudgetAndCompletesOccurrence() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleContracts.Definition definition = definition("turn", turnTarget());
        ExtensionJob job = dispatchedJob(support, definition, Optional.of(support.executionSnapshot(PROFILE)));
        ScheduleOccurrenceJobExecutor executor = executor(support, new RecordingCommands(support));

        ExtensionJobStepResult result = execute(executor, job);

        assertEquals(1, shared(support, result).consumption().turns());
        assertEquals(5, shared(support, result).consumption().inputTokens());
        assertTrue(result.turnId().isPresent());
        assertEquals(
                ScheduleContracts.OccurrenceState.COMPLETED,
                occurrence(support, occurrenceId(job)).status().state());
        assertEquals("定时任务", support.turns.commands().getFirst().title());
        assertEquals(
                occurrenceId(job),
                support.turns
                        .commands()
                        .getFirst()
                        .executionSnapshot()
                        .unattendedExecutionScope()
                        .orElseThrow()
                        .occurrenceId());
    }

    @Test
    void actionTargetUsesFixedPayloadRevisionAndCompletionKey() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        RecordingCommands commands = new RecordingCommands(support);
        ScheduleContracts.Definition definition =
                definition("action", ScheduleContracts.Target.action(actionTarget(7)));
        ExtensionJob job = dispatchedJob(support, definition, Optional.empty());

        ExtensionJobStepResult result = execute(executor(support, commands), job);

        ScheduledCommand call = commands.calls.getFirst();
        assertEquals("refresh", call.operation());
        assertEquals(7, call.expectedRevision());
        assertEquals(new CanonicalPayload("{\"scope\":\"workspace\"}"), call.payload());
        assertEquals(actionTarget(7).schemaHash(), call.actionSchemaHash().orElseThrow());
        assertTrue(call.idempotencyKey().contains(occurrenceId(job)));
        assertEquals(
                occurrenceId(job), call.unattendedExecutionScope().orElseThrow().occurrenceId());
        assertTrue(checkpoint(support, result).completed());
    }

    @Test
    void targetFailureMarksOccurrenceFailedAndPreservesOriginalFailure() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        support.turns.returnStatuses(TurnStatus.FAILED);
        ScheduleContracts.Definition definition = definition("failed-turn", turnTarget());
        ExtensionJob job = dispatchedJob(support, definition, Optional.of(support.executionSnapshot(PROFILE)));
        ScheduleOccurrenceJobExecutor executor = executor(support, new RecordingCommands(support));

        assertThrows(IllegalStateException.class, () -> execute(executor, job));
        ScheduleContracts.Occurrence failed = occurrence(support, occurrenceId(job));
        assertEquals(ScheduleContracts.OccurrenceState.FAILED, failed.status().state());
        assertEquals(Optional.of("TARGET_FAILED"), failed.status().reason());

        RecordingCommands broken = new RecordingCommands(support);
        broken.failure = new IllegalArgumentException("target rejected");
        ScheduleContracts.Definition action =
                definition("failed-action", ScheduleContracts.Target.action(actionTarget(0)));
        ExtensionJob actionJob = dispatchedJob(support, action, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> execute(executor(support, broken), actionJob));
    }

    @Test
    void plannerAndExecutorRejectCompletedStaleIdentityAndStaleIntent() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleContracts.Definition definition = definition("guarded", turnTarget());
        ExtensionJob job = dispatchedJob(support, definition, Optional.of(support.executionSnapshot(PROFILE)));
        ScheduleOccurrenceJobExecutor executor = executor(support, new RecordingCommands(support));

        assertTrue(executor.plan(job).isPresent());
        assertTrue(executor.plan(withCheckpoint(support, job, true)).isEmpty());
        ExtensionJob wrongIdentity = new ExtensionJob(
                job.id(),
                job.extensionId(),
                job.workspaceId(),
                job.jobType(),
                "different",
                job.definitionRevision(),
                job.frozenInput(),
                job.state(),
                job.revision(),
                job.checkpoint(),
                job.nextUnitSequence(),
                job.activeUnitSequence(),
                job.errorCode(),
                job.createdAt(),
                job.updatedAt());
        ExtensionJobWorkUnit identityWork = executor.plan(wrongIdentity).orElseThrow();
        ExtensionJob activeWrongIdentity = active(wrongIdentity);
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(
                        new ExtensionJobExecution(activeWrongIdentity, unit(activeWrongIdentity, identityWork)),
                        new CancellationSource()));

        ExtensionJobWorkUnit work = executor.plan(job).orElseThrow();
        ExtensionJob active = active(job);
        ExtensionJobWorkUnit stale = new ExtensionJobWorkUnit(
                "stale", support.payloads.encode(Map.of("scheduleId", "wrong", "scheduleRevision", 1)));
        assertThrows(
                RuntimeException.class,
                () -> executor.execute(
                        new ExtensionJobExecution(active, unit(active, stale)), new CancellationSource()));
        assertEquals("dispatch-1", work.unitId());
    }

    private static ScheduleOccurrenceJobExecutor executor(
            BuiltinExtensionTestSupport support, ScheduledCommandPort commands) {
        return new ScheduleOccurrenceJobExecutor(new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> {
                    throw new IllegalStateException("isolated service is not configured");
                },
                support.embeddings,
                AutomationStepPort.unavailable(),
                commands,
                (workspaceId, required) -> {},
                com.javaclaw.extension.spi.ConversationEvidencePort.unavailable(),
                com.javaclaw.extension.spi.ScheduleDefinitionBindingPort.unavailable()));
    }

    private static ExtensionJobStepResult execute(ScheduleOccurrenceJobExecutor executor, ExtensionJob job)
            throws Exception {
        ExtensionJobWorkUnit work = executor.plan(job).orElseThrow();
        ExtensionJob active = active(job);
        return executor.execute(new ExtensionJobExecution(active, unit(active, work)), new CancellationSource());
    }

    private static ExtensionJob dispatchedJob(
            BuiltinExtensionTestSupport support,
            ScheduleContracts.Definition definition,
            Optional<com.javaclaw.api.AutomationExecutionSnapshot> snapshot)
            throws Exception {
        ScheduleContracts.Occurrence created = create(support, definition, "run-" + definition.id());
        String jobId = "schedule-job-" + definition.id();
        transition(
                support,
                created.identity().id(),
                status(ScheduleContracts.OccurrenceState.DISPATCHED, Optional.of(jobId), Optional.empty()));
        UnattendedExecutionScope scope = new UnattendedExecutionScope(
                support.workspaceId,
                definition.id(),
                definition.revision(),
                created.identity().id());
        ScheduleContracts.ScheduledExecution frozen = new ScheduleContracts.ScheduledExecution(
                definition, created.identity().id(), snapshot.map(value -> value.withUnattendedExecutionScope(scope)));
        OrchestrationContracts.ExecutionCheckpoint checkpoint = new OrchestrationContracts.ExecutionCheckpoint(
                support.payloads.encode(new ScheduleContracts.OccurrenceCheckpoint(false)),
                OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                jobId,
                new ExtensionId(BuiltinExtensionIds.SCHEDULE),
                support.workspaceId,
                ScheduleOccurrenceJobExecutor.JOB_TYPE,
                definition.id(),
                definition.revision(),
                support.payloads.encode(frozen),
                ExecutionState.RUNNING,
                1,
                support.payloads.encode(checkpoint),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }

    private static ExtensionJob withCheckpoint(
            BuiltinExtensionTestSupport support, ExtensionJob job, boolean completed) {
        OrchestrationContracts.ExecutionCheckpoint checkpoint = new OrchestrationContracts.ExecutionCheckpoint(
                support.payloads.encode(new ScheduleContracts.OccurrenceCheckpoint(completed)),
                OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                job.id(),
                job.extensionId(),
                job.workspaceId(),
                job.jobType(),
                job.definitionId(),
                job.definitionRevision(),
                job.frozenInput(),
                job.state(),
                job.revision(),
                support.payloads.encode(checkpoint),
                job.nextUnitSequence(),
                job.activeUnitSequence(),
                job.errorCode(),
                job.createdAt(),
                job.updatedAt());
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

    private static ScheduleContracts.OccurrenceCheckpoint checkpoint(
            BuiltinExtensionTestSupport support, ExtensionJobStepResult result) {
        return support.payloads.decode(shared(support, result).domain(), ScheduleContracts.OccurrenceCheckpoint.class);
    }

    private static ScheduleContracts.Definition definition(String id, ScheduleContracts.Target target) {
        return new ScheduleContracts.Definition(
                id,
                1,
                id,
                true,
                ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW.plusSeconds(300)),
                target,
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                NOW);
    }

    private static ScheduleContracts.Target turnTarget() {
        return ScheduleContracts.Target.turn(
                new ScheduleContracts.TurnTemplate(AutomationV6Fixtures.selection(PROFILE), "定时任务", "执行固定任务", BUDGET));
    }

    private static ScheduleContracts.Occurrence create(
            BuiltinExtensionTestSupport support, ScheduleContracts.Definition definition, String key) throws Exception {
        return ScheduleOccurrenceStore.create(
                (ManagedExtensionStore) support.store,
                support.payloads,
                support.workspaceId,
                definition,
                NOW.plusSeconds(60),
                key,
                NOW);
    }

    private static ScheduleContracts.Occurrence transition(
            BuiltinExtensionTestSupport support, String occurrenceId, ScheduleContracts.OccurrenceStatus status)
            throws Exception {
        return ScheduleOccurrenceStore.transition(
                support.store, support.payloads, support.workspaceId, occurrenceId, status, NOW.plusSeconds(1));
    }

    private static ScheduleContracts.Occurrence occurrence(BuiltinExtensionTestSupport support, String occurrenceId)
            throws Exception {
        return ScheduleOccurrenceStore.list(
                        support.store,
                        support.payloads,
                        support.workspaceId,
                        new ScheduleContracts.OccurrenceQuery(Optional.empty(), "", 100))
                .occurrences()
                .stream()
                .filter(value -> value.identity().id().equals(occurrenceId))
                .findFirst()
                .orElseThrow();
    }

    private static ScheduleContracts.OccurrenceStatus status(
            ScheduleContracts.OccurrenceState state, Optional<String> jobId, Optional<String> reason) {
        return new ScheduleContracts.OccurrenceStatus(state, jobId, reason);
    }

    private static String occurrenceId(ExtensionJob job) {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        return support.payloads
                .decode(job.frozenInput(), ScheduleContracts.ScheduledExecution.class)
                .occurrenceId();
    }

    private static ScheduleActionContracts.Target actionTarget(long expectedRevision) {
        ScheduleTargetCatalogPort.ActionOption option = new ScheduleTargetCatalogPort.ActionOption(
                "javaclaw.safe",
                "refresh",
                "刷新",
                List.of(new ScheduleTargetCatalogPort.ActionField(
                        "scope", "范围", ScheduleTargetCatalogPort.ScalarType.STRING, true)),
                expectedRevision);
        return ScheduleActionParameters.target(
                option,
                List.of(new ScheduleActionContracts.Argument(
                        "scope", ScheduleActionContracts.ValueType.STRING, "workspace")));
    }

    private static final class RecordingCommands implements ScheduledCommandPort {
        private final BuiltinExtensionTestSupport support;
        private final List<ScheduledCommand> calls = new ArrayList<>();
        private RuntimeException failure;

        private RecordingCommands(BuiltinExtensionTestSupport support) {
            this.support = support;
        }

        @Override
        public ExtensionResponse execute(ScheduledCommand command, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            calls.add(command);
            if (failure != null) {
                throw failure;
            }
            ExtensionExecutionReceipt receipt = new ExtensionExecutionReceipt(
                    "target-job",
                    new ExtensionId("javaclaw.plan"),
                    command.workspaceId(),
                    "automation-execution",
                    "plan",
                    3,
                    ExecutionState.RUNNING,
                    1,
                    Optional.empty(),
                    NOW,
                    NOW);
            return new ExtensionResponse(support.payloads.encode(receipt), 1);
        }
    }
}
