package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobExecutor;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobPage;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.extension.spi.OrchestratedTurnFailureException;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionJobSupervisorTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final ExtensionId EXTENSION = new ExtensionId("com.javaclaw.workflow");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;
    private CoreCommandService core;
    private ExtensionJobService jobs;
    private Workspace workspace;

    @BeforeEach
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        jobs = new ExtensionJobService(database, json, clock);
        core = new CoreCommandService(database, json, clock);
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("Job 测试", temporaryDirectory.resolve("workspace"));
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
    }

    @Test
    void supervisor每次推进一个单元并原子提交Checkpoint与下一Outbox() throws Exception {
        ExtensionJob submitted = submit("two-units", "submit-two");
        AtomicInteger plans = new AtomicInteger();
        RecordingActivities activities = new RecordingActivities();
        ExtensionJobSupervisor supervisor = new ExtensionJobSupervisor(jobs, activities);
        supervisor.register(EXTENSION, "workflow", new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                int unit = plans.incrementAndGet();
                return Optional.of(new ExtensionJobWorkUnit("unit-" + unit, json.parse("{\"unit\":" + unit + "}")));
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation) {
                int unit = Math.toIntExact(execution.unit().sequence());
                ExecutionState next = unit == 1 ? ExecutionState.RUNNING : ExecutionState.COMPLETED;
                return new ExtensionJobStepResult(
                        json.parse("{\"ok\":true}"),
                        json.parse("{\"completed\":" + unit + "}"),
                        next,
                        Optional.empty(),
                        Optional.of("receipt-" + unit));
            }
        });

        assertTrue(supervisor.runOnce());
        assertEquals(
                ExecutionState.RUNNING, jobs.find(submitted.id()).orElseThrow().state());
        assertTrue(supervisor.runOnce());
        assertFalse(supervisor.runOnce());

        InputJobRpcContracts.JobReadResult details = jobs.read(submitted.id());
        assertEquals(ExecutionState.COMPLETED, details.job().state());
        assertEquals(2, details.units().size());
        assertEquals(
                List.of("unit-1", "unit-2"),
                details.units().stream().map(unit -> unit.unitId()).toList());
        assertEquals("receipt-2", details.units().getLast().effectReceiptKey().orElseThrow());
        assertEquals(2, countOutbox("DELIVERED"));
        assertEquals(2, activities.acquired.get());
        assertEquals(0, activities.active.get());
        assertEquals(1, activities.maximumActive.get());
    }

    @Test
    void 崩溃发生在意图提交后会恢复同一Unit而不重新规划() throws Exception {
        ExtensionJob submitted = submit("crash", "submit-crash");
        ExtensionJobService.ClaimedJob claimed = jobs.claimNext().orElseThrow();
        ExtensionJobService.ClaimedJob recorded = jobs.recordIntent(
                claimed, new ExtensionJobWorkUnit("stable-unit", json.parse("{\"effect\":\"once\"}")));
        assertEquals("stable-unit", recorded.unit().orElseThrow().unitId());
        assertEquals(1, countOutbox("PROCESSING"));

        ExtensionJobService restarted = new ExtensionJobService(database, json, clock);
        assertEquals(1, restarted.recoverOutbox());
        ExtensionJobSupervisor supervisor = new ExtensionJobSupervisor(restarted, ignored -> () -> {});
        supervisor.register(EXTENSION, "workflow", new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                throw new AssertionError("已有意图时不能重新规划");
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation) {
                assertEquals("stable-unit", execution.unit().unitId());
                return new ExtensionJobStepResult(
                        json.parse("{\"recovered\":true}"),
                        json.parse("{\"completed\":1}"),
                        ExecutionState.COMPLETED,
                        Optional.empty(),
                        Optional.of("effect-stable-unit"));
            }
        });

        assertTrue(supervisor.runOnce());
        assertEquals(
                ExecutionState.COMPLETED,
                restarted.find(submitted.id()).orElseThrow().state());
        assertEquals(1, restarted.read(submitted.id()).units().size());
    }

    @Test
    void 活动单元恢复后TurnUnknownOutcome会保留证据且不会重新规划() throws Exception {
        ExtensionJob submitted = submit("turn-unknown", "submit-turn-unknown");
        ExtensionJobService.ClaimedJob claimed = jobs.claimNext().orElseThrow();
        jobs.recordIntent(claimed, new ExtensionJobWorkUnit("turn-unit", json.parse("{\"effect\":\"once\"}")));

        ExtensionJobService restarted = new ExtensionJobService(database, json, clock);
        assertEquals(1, restarted.recoverOutbox());
        TurnId evidenceTurn = persistedTurn("unknown-outcome");
        ExtensionJobSupervisor supervisor = new ExtensionJobSupervisor(restarted, ignored -> () -> {});
        supervisor.register(EXTENSION, "workflow", new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                throw new AssertionError("已有活动单元时不能重新规划");
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation)
                    throws OrchestratedTurnFailureException {
                assertEquals("turn-unit", execution.unit().unitId());
                throw new OrchestratedTurnFailureException(
                        evidenceTurn, "UNKNOWN_OUTCOME", Optional.of("receipt-before-crash"));
            }
        });

        assertTrue(supervisor.runOnce());
        InputJobRpcContracts.JobReadResult details = restarted.read(submitted.id());
        assertEquals(ExecutionState.FAILED, details.job().state());
        assertEquals(Optional.of("UNKNOWN_OUTCOME"), details.job().errorCode());
        assertEquals(1, details.units().size());
        assertEquals("turn-unit", details.units().getFirst().unitId());
        assertEquals(Optional.of(evidenceTurn), details.units().getFirst().turnId());
        assertEquals(
                Optional.of("receipt-before-crash"), details.units().getFirst().effectReceiptKey());
        assertEquals(Optional.of("UNKNOWN_OUTCOME"), details.units().getFirst().errorCode());
        assertFalse(supervisor.runOnce());
    }

    @Test
    void lifecycle写命令支持幂等Revision并拒绝活动单元暂停() throws Exception {
        ExtensionJob submitted = submit("lifecycle", "submit-lifecycle");
        ExtensionJobMutation pause = new ExtensionJobMutation("pause", submitted.revision());
        ExtensionJob paused = jobs.pause(submitted.id(), pause);
        assertEquals(paused, jobs.pause(submitted.id(), pause));
        ExtensionJob resumed = jobs.resume(submitted.id(), new ExtensionJobMutation("resume", paused.revision()));
        ExtensionJob cancelled = jobs.cancel(submitted.id(), new ExtensionJobMutation("cancel", resumed.revision()));

        assertEquals(ExecutionState.CANCELLED, cancelled.state());
        assertThrows(
                PersistenceException.class,
                () -> jobs.resume(submitted.id(), new ExtensionJobMutation("stale", paused.revision())));

        ExtensionJob active = submit("active", "submit-active");
        ExtensionJobService.ClaimedJob claimed = claim(active.id());
        ExtensionJobService.ClaimedJob recorded =
                jobs.recordIntent(claimed, new ExtensionJobWorkUnit("active-unit", json.parse("{\"active\":true}")));
        ExtensionJob activeJob = recorded.job();
        assertThrows(
                PersistenceException.class,
                () -> jobs.pause(activeJob.id(), new ExtensionJobMutation("pause-active", activeJob.revision())));
    }

    @Test
    void executor失败提交失败终态且不再投递下一工作单元() throws Exception {
        ExtensionJob submitted = submit("failure", "submit-failure");
        ExtensionJobSupervisor supervisor = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        supervisor.register(EXTENSION, "workflow", new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                return Optional.of(new ExtensionJobWorkUnit("failing", json.parse("{\"run\":true}")));
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation) {
                throw new IllegalStateException("sensitive downstream detail");
            }
        });

        assertTrue(supervisor.runOnce());
        ExtensionJob failed = jobs.find(submitted.id()).orElseThrow();
        assertEquals(ExecutionState.FAILED, failed.state());
        assertEquals(Optional.of("JOB_UNIT_FAILED"), failed.errorCode());
        assertEquals(1, jobs.read(submitted.id()).units().size());
        assertFalse(supervisor.runOnce());
    }

    @Test
    void lifecycleLease暂不可用时保留同一工作单元等待重试() throws Exception {
        ExtensionJob submitted = submit("lease-retry", "submit-lease-retry");
        AtomicInteger executions = new AtomicInteger();
        ExtensionJobExecutor executor = completingExecutor(executions);
        ExtensionJobSupervisor unavailable = new ExtensionJobSupervisor(jobs, ignored -> {
            throw new IllegalStateException("lifecycle stopping");
        });
        unavailable.register(EXTENSION, "workflow", executor);

        assertTrue(unavailable.runOnce());
        assertEquals(0, executions.get());
        assertEquals(
                ExecutionState.RUNNING, jobs.find(submitted.id()).orElseThrow().state());
        assertEquals(1, countOutbox("PENDING"));

        ExtensionJobService resumedJobs =
                new ExtensionJobService(database, json, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        ExtensionJobSupervisor recovered = new ExtensionJobSupervisor(resumedJobs, ignored -> () -> {});
        recovered.register(EXTENSION, "workflow", executor);

        assertTrue(recovered.runOnce());
        assertEquals(1, executions.get());
        assertEquals(
                ExecutionState.COMPLETED,
                resumedJobs.find(submitted.id()).orElseThrow().state());
    }

    @Test
    void submit幂等恢复并拒绝相同Key绑定其他冻结定义() throws Exception {
        ExtensionJobSubmission submission = submission("idempotent");
        ExtensionJobMutation mutation = new ExtensionJobMutation("stable-submit", 0);
        ExtensionJob first = jobs.submit(json.encode(submission), mutation, () -> submission);
        ExtensionJob second = jobs.submit(json.encode(submission), mutation, () -> {
            throw new AssertionError("replay must not rebuild the frozen submission");
        });

        assertEquals(first, second);
        assertEquals(List.of(first), jobs.list(Optional.of(workspace.id()), Optional.of(EXTENSION), Set.of(), 10));
        ExtensionJobSubmission changed = submission("changed");
        assertThrows(PersistenceException.class, () -> jobs.submit(json.encode(changed), mutation, () -> changed));
        assertThrows(
                PersistenceException.class,
                () -> jobs.submit(
                        json.encode(submission), new ExtensionJobMutation("bad-revision", 1), () -> submission));
    }

    @Test
    void job分页在相同更新时间下使用Id稳定续页且不会重复() throws Exception {
        List<ExtensionJob> submitted = List.of(
                submit("page-1", "submit-page-1"),
                submit("page-2", "submit-page-2"),
                submit("page-3", "submit-page-3"),
                submit("page-4", "submit-page-4"),
                submit("page-5", "submit-page-5"));
        List<String> expected = submitted.stream()
                .sorted(Comparator.comparing(ExtensionJob::updatedAt).reversed().thenComparing(ExtensionJob::id))
                .map(ExtensionJob::id)
                .toList();

        ExtensionJobPage first = page(Optional.empty());
        ExtensionJobPage second = page(first.nextCursor());
        ExtensionJobPage third = page(second.nextCursor());
        List<String> actual = java.util.stream.Stream.of(first, second, third)
                .flatMap(page -> page.jobs().stream())
                .map(ExtensionJob::id)
                .toList();

        assertEquals(expected, actual);
        assertEquals(actual.size(), actual.stream().distinct().count());
        assertTrue(first.nextCursor().isPresent());
        assertTrue(second.nextCursor().isPresent());
        assertTrue(third.nextCursor().isEmpty());
    }

    @Test
    void supervisor注册启动关闭与缺失Executor均失败关闭() throws Exception {
        ExtensionJobExecutor executor = completingExecutor(new AtomicInteger());
        ExtensionJobSupervisor registrations = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        registrations.register(EXTENSION, "workflow", executor);
        assertThrows(IllegalArgumentException.class, () -> registrations.register(EXTENSION, "workflow", executor));
        assertThrows(IllegalArgumentException.class, () -> registrations.register(EXTENSION, "bad/type", executor));

        ExtensionJob missingExecutor = submit("missing-executor", "submit-missing-executor");
        ExtensionJobSupervisor unregistered = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        assertTrue(unregistered.runOnce());
        assertEquals(
                ExecutionState.RUNNING,
                jobs.find(missingExecutor.id()).orElseThrow().state());

        ExtensionJobSupervisor closed = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        closed.close();
        closed.close();
        assertFalse(closed.runOnce());
        assertThrows(IllegalStateException.class, closed::start);

        ExtensionJobSupervisor started = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        started.start();
        assertThrows(IllegalStateException.class, started::start);
        started.close();
    }

    @Test
    void supervisor区分规划失败无工作单元与Lease释放失败() throws Exception {
        ExtensionJob planningFailure = submit("plan-failure", "submit-plan-failure");
        ExtensionJobSupervisor failed = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        failed.register(EXTENSION, "workflow", new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                throw new IllegalStateException("planning failed");
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation) {
                throw new AssertionError("failed plan must not execute");
            }
        });
        assertTrue(failed.runOnce());
        assertEquals(
                Optional.of("JOB_PLAN_FAILED"),
                jobs.find(planningFailure.id()).orElseThrow().errorCode());

        ExtensionJob noUnit = submit("no-unit", "submit-no-unit");
        ExtensionJobSupervisor empty = new ExtensionJobSupervisor(jobs, ignored -> () -> {});
        empty.register(EXTENSION, "workflow", new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                return Optional.empty();
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation) {
                throw new AssertionError("empty plan must not execute");
            }
        });
        assertTrue(empty.runOnce());
        assertEquals(
                ExecutionState.COMPLETED, jobs.find(noUnit.id()).orElseThrow().state());

        ExtensionJob closeFailure = submit("lease-close", "submit-lease-close");
        ExtensionJobSupervisor closeFailureSupervisor = new ExtensionJobSupervisor(jobs, ignored -> () -> {
            throw new IllegalStateException("lease close failed");
        });
        closeFailureSupervisor.register(EXTENSION, "workflow", completingExecutor(new AtomicInteger()));
        assertTrue(closeFailureSupervisor.runOnce());
        assertEquals(
                ExecutionState.COMPLETED,
                jobs.find(closeFailure.id()).orElseThrow().state());
    }

    private ExtensionJob submit(String definitionId, String key) throws Exception {
        ExtensionJobSubmission submission = submission(definitionId);
        return jobs.submit(json.encode(submission), new ExtensionJobMutation(key, 0), () -> submission);
    }

    private ExtensionJobPage page(Optional<com.javaclaw.extension.spi.ExtensionJobCursor> after) {
        return jobs.page(Optional.of(workspace.id()), Optional.of(EXTENSION), Set.of(ExecutionState.QUEUED), after, 2);
    }

    private ExtensionJobService.ClaimedJob claim(String jobId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            Optional<ExtensionJobService.ClaimedJob> candidate = jobs.claimNext();
            if (candidate.isPresent() && candidate.orElseThrow().job().id().equals(jobId)) {
                return candidate.orElseThrow();
            }
        }
        throw new AssertionError("Job 未被领取: " + jobId);
    }

    private ExtensionJobSubmission submission(String definitionId) {
        return new ExtensionJobSubmission(
                EXTENSION,
                workspace.id(),
                "workflow",
                definitionId,
                1,
                json.parse("{\"profileRevision\":1}"),
                json.parse("{\"completed\":0}"));
    }

    private TurnId persistedTurn(String suffix) {
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Job evidence " + suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "evidence-thread-" + suffix, 0, threadPayload),
                workspace.id(),
                threadPayload.parentThreadId(),
                threadPayload.executionIntent(),
                threadPayload.title());
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload startPayload = TurnContractFixtures.payload(thread.id(), suffix);
        return core.startTurn(
                        identity("turn/start", "evidence-turn-" + suffix, 0, startPayload),
                        TurnContractFixtures.request(
                                thread.id(), new TurnBudget(1_000, 1_000, 1, 0, Duration.ofMinutes(1)), message))
                .id();
    }

    private ExtensionJobExecutor completingExecutor(AtomicInteger executions) {
        return new ExtensionJobExecutor() {
            @Override
            public Optional<ExtensionJobWorkUnit> plan(ExtensionJob job) {
                return Optional.of(new ExtensionJobWorkUnit("stable-unit", json.parse("{\"run\":true}")));
            }

            @Override
            public ExtensionJobStepResult execute(
                    ExtensionJobExecution execution, com.javaclaw.api.CancellationToken cancellation) {
                executions.incrementAndGet();
                return new ExtensionJobStepResult(
                        json.parse("{\"ok\":true}"),
                        json.parse("{\"completed\":1}"),
                        ExecutionState.COMPLETED,
                        Optional.empty(),
                        Optional.empty());
            }
        };
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private int countOutbox(String state) {
        try (var connection = database.open();
                var statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM CORE.OUTBOX WHERE DESTINATION = ? AND STATUS = ?
                        """)) {
            statement.setString(1, ExtensionJobOutboxRepository.DESTINATION);
            statement.setString(2, state);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (java.sql.SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class RecordingActivities implements ExtensionJobActivityPort {
        private final AtomicInteger acquired = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();

        @Override
        public Lease acquire(ExtensionJob job) {
            acquired.incrementAndGet();
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            return () -> active.decrementAndGet();
        }
    }
}
