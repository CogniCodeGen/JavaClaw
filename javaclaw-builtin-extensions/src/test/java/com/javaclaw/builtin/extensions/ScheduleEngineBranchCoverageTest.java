package com.javaclaw.builtin.extensions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobPage;
import com.javaclaw.extension.spi.ExtensionJobPort;
import com.javaclaw.extension.spi.ExtensionJobSubmissionFactory;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.extension.spi.ScheduledCommandPort;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleEngineBranchCoverageTest {
    private static final AgentRoleRef PROFILE = new AgentRoleRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(2, 2_000, 1_000, 10);

    @Test
    void runtimeBindingIsIdempotentButRejectsEveryPartialOrChangedPort() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        RecordingScheduledCommands commands = new RecordingScheduledCommands();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        ScheduleEngine engine = new ScheduleEngine();

        ScheduleContracts.Occurrence pending =
                occurrence(support, "pending-unbound", turnDefinition("pending-unbound"));
        assertThrows(
                IllegalStateException.class,
                () -> engine.dispatchRecorded(context(support, new ScheduleExtension()), pending));
        engine.close();

        engine.bindRuntime(support.payloads, commands, lifecycle);
        engine.bindRuntime(support.payloads, commands, lifecycle);
        assertRebindingFailures(support, commands, lifecycle);

        ScheduleEngine missingCommands = new ScheduleEngine();
        assertThrows(NullPointerException.class, () -> missingCommands.bindRuntime(support.payloads, null, lifecycle));
        assertThrows(
                IllegalStateException.class,
                () -> missingCommands.dispatchRecorded(context(support, new ScheduleExtension()), pending));
        missingCommands.close();

        ScheduleEngine missingLifecycle = new ScheduleEngine();
        assertThrows(NullPointerException.class, () -> missingLifecycle.bindRuntime(support.payloads, commands, null));
        assertThrows(
                IllegalStateException.class,
                () -> missingLifecycle.dispatchRecorded(context(support, new ScheduleExtension()), pending));
        missingLifecycle.close();
        engine.close();
    }

    @Test
    void restoreProjectsCronFixedDisabledAndStaleDefinitionsAndSynchronizesLifecycle() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleEngine engine = new ScheduleEngine();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        engine.bindRuntime(support.payloads, new RecordingScheduledCommands(), lifecycle);
        ExtensionExecutionContext context = context(support, new ScheduleExtension());
        try {
            ScheduleContracts.Definition fixed = turnDefinition("fixed");
            ScheduleContracts.Definition cron = definition(
                    "cron", true, ScheduleContracts.Timing.cron("0 0 9 ? * MON-FRI", "Asia/Shanghai"), actionTarget());
            ScheduleContracts.Definition disabled = definition(
                    "disabled",
                    false,
                    ScheduleContracts.Timing.fixed(Duration.ofMinutes(10), NOW.plusSeconds(120)),
                    actionTarget());

            engine.restore(context, List.of(fixed, cron, disabled));
            engine.changed(context, List.of(cron));
            engine.restore(context, List.of());

            assertEquals(List.of(true, true, false), lifecycle.values);
        } finally {
            engine.close();
        }
    }

    @Test
    void dispatchOnlyQueuesPendingOccurrencesAndFreezesTurnSnapshotWhenRequired() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleEngine engine = new ScheduleEngine();
        engine.bindRuntime(support.payloads, new RecordingScheduledCommands(), new RecordingLifecycle());
        ExtensionExecutionContext context = context(support, new ScheduleExtension());
        try {
            ScheduleContracts.Occurrence pendingTurn =
                    occurrence(support, "pending-turn", turnDefinition("pending-turn"));
            ScheduleContracts.Occurrence dispatched = engine.dispatchRecorded(context, pendingTurn);
            ScheduleContracts.Occurrence pendingAction = occurrence(
                    support,
                    "pending-action",
                    definition(
                            "pending-action",
                            true,
                            ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW.plusSeconds(60)),
                            actionTarget()));
            ScheduleContracts.Occurrence dispatchedAction = engine.dispatchRecorded(context, pendingAction);
            ScheduleContracts.Occurrence unchanged = engine.dispatchRecorded(context, dispatchedAction);

            assertEquals(
                    ScheduleContracts.OccurrenceState.DISPATCHED,
                    dispatched.status().state());
            assertEquals(
                    ScheduleContracts.OccurrenceState.DISPATCHED,
                    dispatchedAction.status().state());
            assertSame(dispatchedAction, unchanged);
            assertEquals(
                    2,
                    support.jobs
                            .list(Optional.empty(), Optional.empty(), Set.of(), 10)
                            .size());
            assertTrue(support.jobs.list(Optional.empty(), Optional.empty(), Set.of(), 10).stream()
                    .map(ExtensionJob::frozenInput)
                    .map(payload -> support.payloads.decode(payload, ScheduleContracts.ScheduledExecution.class))
                    .anyMatch(value -> value.executionSnapshot().isPresent()));
            assertTrue(support.jobs.list(Optional.empty(), Optional.empty(), Set.of(), 10).stream()
                    .map(ExtensionJob::frozenInput)
                    .map(payload -> support.payloads.decode(payload, ScheduleContracts.ScheduledExecution.class))
                    .anyMatch(value -> value.executionSnapshot().isEmpty()));
        } finally {
            engine.close();
        }
    }

    @Test
    void commandDispatchAndQuartzReconcileClaimOnePendingOccurrenceOnlyOnce() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        BlockingScheduleJobPort jobs = new BlockingScheduleJobPort(support);
        ScheduleEngine engine = new ScheduleEngine();
        engine.bindRuntime(support.payloads, new RecordingScheduledCommands(), new RecordingLifecycle());
        ExtensionExecutionContext context = copyContext(context(support, new ScheduleExtension()), jobs);
        ScheduleContracts.Occurrence pending = occurrence(support, "single-claim", turnDefinition("single-claim"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch reconcileStarted = new CountDownLatch(1);
        try {
            Future<ScheduleContracts.Occurrence> command =
                    executor.submit(() -> engine.dispatchRecorded(context, pending));
            assertTrue(jobs.awaitSubmission());
            Future<?> reconcile = executor.submit(() -> {
                reconcileStarted.countDown();
                invoke(engine, "reconcileAll", new Class<?>[0]);
                return null;
            });
            assertTrue(reconcileStarted.await(10, TimeUnit.SECONDS));

            jobs.releaseSubmission();
            assertEquals(
                    ScheduleContracts.OccurrenceState.DISPATCHED,
                    command.get(10, TimeUnit.SECONDS).status().state());
            reconcile.get(10, TimeUnit.SECONDS);

            assertEquals(1, jobs.submissionCount());
            assertEquals(
                    ScheduleContracts.OccurrenceState.DISPATCHED,
                    ScheduleOccurrenceStore.list(
                                    support.store,
                                    support.payloads,
                                    support.workspaceId,
                                    new ScheduleContracts.OccurrenceQuery(Optional.empty(), "", 10))
                            .occurrences()
                            .getFirst()
                            .status()
                            .state());
        } finally {
            jobs.releaseSubmission();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            engine.close();
        }
    }

    @Test
    void reconcileOccurrenceCoversMissingQueuedRunningWaitingAndTerminalJobs() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        ScheduleEngine engine = new ScheduleEngine();
        engine.bindRuntime(support.payloads, new RecordingScheduledCommands(), new RecordingLifecycle());
        try {
            Map<String, ExtensionJob> jobs = new HashMap<>();
            List<ScheduleContracts.Occurrence> occurrences = reconciliationOccurrences(support, jobs);
            ExtensionExecutionContext context =
                    copyContext(context(support, new ScheduleExtension()), new StateJobPort(jobs));

            reconcileOccurrences(engine, context, occurrences);
            assertReconciledStates(occurrenceStates(support));
            assertNonTerminalJobRejected(engine, context, occurrences.getFirst());
        } finally {
            engine.close();
        }
    }

    @Test
    void fireIgnoresUnknownWorkspaceAndContainsScheduledCommandFailures() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        RecordingScheduledCommands commands = new RecordingScheduledCommands();
        ScheduleEngine engine = new ScheduleEngine();
        engine.bindRuntime(support.payloads, commands, new RecordingLifecycle());
        ExtensionExecutionContext context = context(support, new ScheduleExtension());
        try {
            engine.restore(context, List.of());
            invoke(
                    engine,
                    "fire",
                    new Class<?>[] {WorkspaceId.class, String.class, long.class, Instant.class},
                    WorkspaceId.random(),
                    "unknown",
                    1L,
                    NOW);
            invoke(
                    engine,
                    "fire",
                    new Class<?>[] {WorkspaceId.class, String.class, long.class, Instant.class},
                    support.workspaceId,
                    "known",
                    2L,
                    NOW.plusSeconds(10));
            commands.fail = true;
            invoke(
                    engine,
                    "fire",
                    new Class<?>[] {WorkspaceId.class, String.class, long.class, Instant.class},
                    support.workspaceId,
                    "failure",
                    3L,
                    NOW.plusSeconds(20));

            assertEquals(List.of("known", "failure"), commands.scheduleIds);
        } finally {
            engine.close();
        }
    }

    private static void assertRebindingFailures(
            BuiltinExtensionTestSupport support, RecordingScheduledCommands commands, RecordingLifecycle lifecycle) {
        assertThrows(
                IllegalStateException.class,
                () -> newBoundEngine(support.payloads, commands, lifecycle)
                        .bindRuntime(new BuiltinExtensionTestSupport.TestPayloadCodec(), commands, lifecycle));
        assertThrows(
                IllegalStateException.class,
                () -> newBoundEngine(support.payloads, commands, lifecycle)
                        .bindRuntime(support.payloads, new RecordingScheduledCommands(), lifecycle));
        assertThrows(
                IllegalStateException.class,
                () -> newBoundEngine(support.payloads, commands, lifecycle)
                        .bindRuntime(support.payloads, commands, new RecordingLifecycle()));
    }

    private static ScheduleEngine newBoundEngine(
            ExtensionPayloadCodec payloads, ScheduledCommandPort commands, ScheduleLifecyclePort lifecycle) {
        ScheduleEngine engine = new ScheduleEngine();
        engine.bindRuntime(payloads, commands, lifecycle);
        return engine;
    }

    private static ScheduleContracts.Occurrence occurrence(
            BuiltinExtensionTestSupport support, String key, ScheduleContracts.Definition definition) throws Exception {
        return ScheduleOccurrenceStore.create(
                (com.javaclaw.extension.spi.ManagedExtensionStore) support.store,
                support.payloads,
                support.workspaceId,
                definition,
                NOW.plusSeconds(30),
                key,
                NOW);
    }

    private static ScheduleContracts.Occurrence dispatched(
            BuiltinExtensionTestSupport support, String scheduleId, String jobId) throws Exception {
        ScheduleContracts.Occurrence pending = occurrence(support, scheduleId, turnDefinition(scheduleId));
        return ScheduleOccurrenceStore.transition(
                support.store,
                support.payloads,
                support.workspaceId,
                pending.identity().id(),
                new ScheduleContracts.OccurrenceStatus(
                        ScheduleContracts.OccurrenceState.DISPATCHED, Optional.of(jobId), Optional.empty()),
                NOW);
    }

    private static ScheduleContracts.Occurrence withJob(
            BuiltinExtensionTestSupport support,
            Map<String, ExtensionJob> jobs,
            String scheduleId,
            ExecutionState state)
            throws Exception {
        String jobId = "job-" + scheduleId;
        ScheduleContracts.Occurrence occurrence = dispatched(support, scheduleId, jobId);
        jobs.put(jobId, job(support, jobId, scheduleId, state));
        return occurrence;
    }

    private static List<ScheduleContracts.Occurrence> reconciliationOccurrences(
            BuiltinExtensionTestSupport support, Map<String, ExtensionJob> jobs) throws Exception {
        List<ScheduleContracts.Occurrence> occurrences = new ArrayList<>();
        occurrences.add(dispatched(support, "missing", "job-missing"));
        occurrences.add(withJob(support, jobs, "queued", ExecutionState.QUEUED));
        occurrences.add(withJob(support, jobs, "running", ExecutionState.RUNNING));
        occurrences.add(withJob(support, jobs, "completed", ExecutionState.COMPLETED));
        occurrences.add(withJob(support, jobs, "failed", ExecutionState.FAILED));
        occurrences.add(withJob(support, jobs, "cancelled", ExecutionState.CANCELLED));
        ScheduleContracts.Occurrence waiting = withJob(support, jobs, "waiting", ExecutionState.WAITING_INPUT);
        occurrences.add(ScheduleOccurrenceStore.transition(
                support.store,
                support.payloads,
                support.workspaceId,
                waiting.identity().id(),
                new ScheduleContracts.OccurrenceStatus(
                        ScheduleContracts.OccurrenceState.RUNNING,
                        waiting.status().jobId(),
                        Optional.empty()),
                NOW));
        return List.copyOf(occurrences);
    }

    private static void reconcileOccurrences(
            ScheduleEngine engine, ExtensionExecutionContext context, List<ScheduleContracts.Occurrence> occurrences)
            throws Exception {
        for (ScheduleContracts.Occurrence occurrence : occurrences) {
            invoke(
                    engine,
                    "reconcileOccurrence",
                    new Class<?>[] {ExtensionExecutionContext.class, ScheduleContracts.Occurrence.class},
                    context,
                    occurrence);
        }
    }

    private static Map<String, ScheduleContracts.OccurrenceState> occurrenceStates(BuiltinExtensionTestSupport support)
            throws Exception {
        return ScheduleOccurrenceStore.list(
                        support.store,
                        support.payloads,
                        support.workspaceId,
                        new ScheduleContracts.OccurrenceQuery(Optional.empty(), "", 100))
                .occurrences()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.identity().scheduleId(),
                        value -> value.status().state()));
    }

    private static void assertReconciledStates(Map<String, ScheduleContracts.OccurrenceState> states) {
        assertEquals(ScheduleContracts.OccurrenceState.DISPATCHED, states.get("missing"));
        assertEquals(ScheduleContracts.OccurrenceState.DISPATCHED, states.get("queued"));
        assertEquals(ScheduleContracts.OccurrenceState.RUNNING, states.get("running"));
        assertEquals(ScheduleContracts.OccurrenceState.COMPLETED, states.get("completed"));
        assertEquals(ScheduleContracts.OccurrenceState.FAILED, states.get("failed"));
        assertEquals(ScheduleContracts.OccurrenceState.CANCELLED, states.get("cancelled"));
        assertEquals(ScheduleContracts.OccurrenceState.RUNNING, states.get("waiting"));
    }

    private static void assertNonTerminalJobRejected(
            ScheduleEngine engine, ExtensionExecutionContext context, ScheduleContracts.Occurrence occurrence) {
        assertThrows(
                IllegalArgumentException.class,
                () -> invoke(
                        engine,
                        "finishFromJob",
                        new Class<?>[] {
                            ExtensionExecutionContext.class, ScheduleContracts.Occurrence.class, ExecutionState.class
                        },
                        context,
                        occurrence,
                        ExecutionState.RUNNING));
    }

    private static ExtensionJob job(
            BuiltinExtensionTestSupport support, String jobId, String definitionId, ExecutionState state) {
        return new ExtensionJob(
                jobId,
                new ExtensionId("javaclaw.schedule"),
                support.workspaceId,
                ScheduleOccurrenceJobExecutor.JOB_TYPE,
                definitionId,
                1,
                support.payloads.encode(Map.of()),
                state,
                1,
                support.payloads.encode(Map.of()),
                1,
                Optional.empty(),
                state == ExecutionState.FAILED ? Optional.of("TARGET_FAILED") : Optional.empty(),
                NOW,
                NOW);
    }

    private static ScheduleContracts.Definition turnDefinition(String id) {
        return definition(
                id,
                true,
                ScheduleContracts.Timing.fixed(Duration.ofMinutes(5), NOW.plusSeconds(60)),
                ScheduleContracts.Target.turn(new ScheduleContracts.TurnTemplate(
                        AutomationV6Fixtures.selection(PROFILE), id, "执行定时任务", BUDGET)));
    }

    private static ScheduleContracts.Target actionTarget() {
        ScheduleTargetCatalogPort.ActionOption option =
                new ScheduleTargetCatalogPort.ActionOption("javaclaw.safe", "refresh", "刷新", List.of(), 0);
        return ScheduleContracts.Target.action(new ScheduleActionContracts.Target(
                option.extensionId(),
                option.operation(),
                List.of(),
                List.of(),
                option.schemaHash(),
                option.expectedRevision()));
    }

    private static ScheduleContracts.Definition definition(
            String id, boolean enabled, ScheduleContracts.Timing timing, ScheduleContracts.Target target) {
        return new ScheduleContracts.Definition(
                id,
                1,
                id,
                enabled,
                timing,
                target,
                ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                NOW);
    }

    private static ExtensionExecutionContext context(BuiltinExtensionTestSupport support, ScheduleExtension extension) {
        return support.context(extension);
    }

    private static ExtensionExecutionContext copyContext(ExtensionExecutionContext source, ExtensionJobPort jobs) {
        return new ExtensionExecutionContext(
                source.extension(),
                source.workspaceId(),
                source.effectivePermissions(),
                source.cancellation(),
                source.clock(),
                source.managedStore(),
                source.turns(),
                source.executionPolicies(),
                source.scheduleTargets(),
                source.inputs(),
                jobs,
                source.evidence(),
                source.attachments(),
                source.credentials(),
                source.privateNetworkGrants(),
                source.services(),
                source.embeddings(),
                com.javaclaw.extension.spi.WorkspaceExecutionPort.denied());
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception checked) {
                throw checked;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static final class RecordingScheduledCommands implements ScheduledCommandPort {
        private final List<String> scheduleIds = new ArrayList<>();
        private boolean fail;

        @Override
        public ExtensionResponse execute(ScheduledCommand command, com.javaclaw.api.CancellationToken cancellation) {
            ScheduleContracts.DeliveryRequest request = new BuiltinExtensionTestSupport.TestPayloadCodec()
                    .decode(command.payload(), ScheduleContracts.DeliveryRequest.class);
            scheduleIds.add(request.scheduleId());
            if (fail) {
                throw new IllegalStateException("投递失败");
            }
            return new ExtensionResponse(new CanonicalPayload("{}"), command.expectedRevision());
        }
    }

    private static final class RecordingLifecycle implements ScheduleLifecyclePort {
        private final List<Boolean> values = new ArrayList<>();

        @Override
        public void synchronize(WorkspaceId workspaceId, boolean required) {
            values.add(required);
        }
    }

    private static final class StateJobPort implements ExtensionJobPort {
        private final Map<String, ExtensionJob> jobs;

        private StateJobPort(Map<String, ExtensionJob> jobs) {
            this.jobs = Map.copyOf(jobs);
        }

        @Override
        public ExtensionJob submit(
                CanonicalPayload requestIdentity,
                ExtensionJobMutation mutation,
                ExtensionJobSubmissionFactory submissionFactory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ExtensionJob> find(String jobId) {
            return Optional.ofNullable(jobs.get(jobId));
        }

        @Override
        public List<ExtensionJob> list(
                Optional<WorkspaceId> workspaceId,
                Optional<ExtensionId> extensionId,
                Set<ExecutionState> states,
                int limit) {
            return jobs.values().stream().limit(limit).toList();
        }

        @Override
        public ExtensionJobPage page(
                Optional<WorkspaceId> workspaceId,
                Optional<ExtensionId> extensionId,
                Set<ExecutionState> states,
                Optional<ExtensionJobCursor> after,
                int limit) {
            return new ExtensionJobPage(list(workspaceId, extensionId, states, limit), Optional.empty());
        }

        @Override
        public ExtensionJob pause(String jobId, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionJob resume(String jobId, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionJob continueWaiting(
                String jobId, ExecutionState waitingState, CanonicalPayload checkpoint, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtensionJob cancel(String jobId, ExtensionJobMutation mutation) {
            throw new UnsupportedOperationException();
        }
    }
}
