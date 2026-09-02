package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionJobRepositoryBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final ExtensionId EXTENSION = new ExtensionId("com.javaclaw.workflow");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private ExtensionJobRepository repository;
    private ExtensionJobService service;
    private Workspace workspace;

    @BeforeEach
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        repository = new ExtensionJobRepository();
        service = new ExtensionJobService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        CoreCommandService core = new CoreCommandService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("Job 仓库测试", temporaryDirectory.resolve("workspace"));
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
    }

    @Test
    void repository覆盖完成失败等待续行与状态计数() throws Exception {
        try (var connection = database.open()) {
            ExtensionJob completed = running(connection, "complete");
            completed = repository.completeWithoutUnit(connection, completed, NOW);

            ExtensionJob failed = running(connection, "fail");
            failed = repository.failWithoutUnit(connection, failed, "UNIT_FAILED", NOW);

            ExtensionJob approval = active(connection, "approval");
            ExtensionJobUnit approvalUnit =
                    repository.activeUnit(connection, approval).orElseThrow();
            approval = repository.completeUnit(
                    connection, approval, approvalUnit, step(ExecutionState.WAITING_APPROVAL), NOW);
            approval = repository.continueWaiting(
                    connection, approval, ExecutionState.WAITING_APPROVAL, json.parse("{\"approved\":true}"), NOW);

            ExtensionJob expired = waitingInput(connection, "expired");
            expired = repository.finishInputWait(
                    connection, expired, ExecutionState.FAILED, Optional.of("INPUT_EXPIRED"), NOW);

            ExtensionJob cancelled = waitingInput(connection, "cancelled");
            cancelled =
                    repository.finishInputWait(connection, cancelled, ExecutionState.CANCELLED, Optional.empty(), NOW);

            Map<ExecutionState, Integer> counts = repository.countByState(connection);
            assertEquals(1, counts.get(ExecutionState.COMPLETED));
            assertEquals(2, counts.get(ExecutionState.FAILED));
            assertEquals(1, counts.get(ExecutionState.CANCELLED));
            assertEquals(1, counts.get(ExecutionState.QUEUED));
            assertEquals(ExecutionState.COMPLETED, completed.state());
            assertEquals(Optional.of("UNIT_FAILED"), failed.errorCode());
            assertEquals(ExecutionState.QUEUED, approval.state());
            assertEquals(Optional.of("INPUT_EXPIRED"), expired.errorCode());
            assertTrue(cancelled.errorCode().isEmpty());

            List<ExtensionJob> failedJobs = repository.list(
                    connection, Optional.of(workspace.id()), Optional.of(EXTENSION), Set.of(ExecutionState.FAILED), 10);
            assertEquals(2, failedJobs.size());
            assertEquals(
                    2,
                    repository
                            .page(connection, Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 2)
                            .size());
        }
    }

    @Test
    void repository拒绝非法等待终态活动单元与陈旧快照() throws Exception {
        try (var connection = database.open()) {
            ExtensionJob queued = insert(connection, "queued");
            assertThrows(
                    PersistenceException.class,
                    () -> repository.continueWaiting(
                            connection, queued, ExecutionState.WAITING_INPUT, json.parse("{\"next\":true}"), NOW));
            ExtensionJob running = repository.markRunning(connection, queued, NOW);
            assertThrows(PersistenceException.class, () -> repository.markRunning(connection, queued, NOW));
            assertThrows(
                    PersistenceException.class, () -> repository.failWithoutUnit(connection, queued, "STALE", NOW));

            ExtensionJob active = repository.recordIntent(
                    connection, running, new ExtensionJobWorkUnit("active", json.parse("{\"run\":true}")), NOW);
            assertThrows(
                    PersistenceException.class,
                    () -> repository.recordIntent(
                            connection, active, new ExtensionJobWorkUnit("second", json.parse("{\"run\":true}")), NOW));
            assertThrows(
                    PersistenceException.class,
                    () -> repository.transition(
                            connection, active, Set.of(ExecutionState.RUNNING), ExecutionState.PAUSED, NOW));

            ExtensionJob other = active(connection, "other");
            ExtensionJobUnit otherUnit =
                    repository.activeUnit(connection, other).orElseThrow();
            assertThrows(
                    PersistenceException.class,
                    () -> repository.completeUnit(connection, active, otherUnit, step(ExecutionState.COMPLETED), NOW));

            ExtensionJob waiting = waitingInput(connection, "invalid-terminal");
            assertThrows(
                    PersistenceException.class,
                    () -> repository.finishInputWait(
                            connection, waiting, ExecutionState.COMPLETED, Optional.empty(), NOW));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.finishInputWait(
                            connection, waiting, ExecutionState.FAILED, Optional.empty(), NOW));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.finishInputWait(
                            connection, waiting, ExecutionState.CANCELLED, Optional.of("unexpected"), NOW));
        }
    }

    @Test
    void service覆盖无工作单元完成失败重试与终态Outbox丢弃() throws Exception {
        ExtensionJob completed = submit("service-complete", "submit-complete");
        ExtensionJobService.ClaimedJob completing = claim(completed.id());
        assertEquals(
                ExecutionState.COMPLETED,
                service.completeWithoutUnit(completing).state());

        ExtensionJob failed = submit("service-fail", "submit-fail");
        ExtensionJobService.ClaimedJob failing = claim(failed.id());
        assertEquals(ExecutionState.FAILED, service.fail(failing, "NO_WORK").state());

        ExtensionJob retrying = submit("service-retry", "submit-retry");
        ExtensionJobService.ClaimedJob retry = claim(retrying.id());
        assertThrows(IllegalArgumentException.class, () -> service.retry(retry, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> service.retry(retry, Duration.ofSeconds(-1)));
        service.retry(retry, Duration.ofSeconds(10));
        assertTrue(service.claimNext().isEmpty());

        ExtensionJob paused = submit("paused-outbox", "submit-paused");
        service.pause(paused.id(), new ExtensionJobMutation("pause-before-claim", paused.revision()));
        assertTrue(service.claimNext().isEmpty());
        assertEquals(1, service.counts().paused());
    }

    @Test
    void service拒绝非法分页缺失Job和陈旧Claim() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> service.list(Optional.empty(), Optional.empty(), Set.of(), 0));
        assertThrows(
                IllegalArgumentException.class, () -> service.list(Optional.empty(), Optional.empty(), Set.of(), 201));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.page(Optional.empty(), Optional.empty(), Set.of(), Optional.empty(), 0));
        assertThrows(PersistenceException.class, () -> service.read("missing"));
        assertFalse(service.find("missing").isPresent());

        ExtensionJob submitted = submit("stale-claim", "submit-stale");
        ExtensionJobService.ClaimedJob claimed = claim(submitted.id());
        updateJob(claimed.job().id(), "REVISION = REVISION + 1");

        assertThrows(PersistenceException.class, () -> service.completeWithoutUnit(claimed));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobService.JobCounts(-1, 0, 0, 0));
    }

    @Test
    void claim检测活动单元引用损坏并在恢复后FailClosed() throws Exception {
        ExtensionJob submitted = submit("broken-active", "submit-broken");
        ExtensionJobService.ClaimedJob claimed = claim(submitted.id());
        updateJob(claimed.job().id(), "NEXT_UNIT_SEQUENCE = 2, ACTIVE_UNIT_SEQUENCE = 1");
        assertEquals(1, service.recoverOutbox());

        assertThrows(PersistenceException.class, service::claimNext);
    }

    private ExtensionJob submit(String definitionId, String key) throws Exception {
        ExtensionJobSubmission submission = submission(definitionId);
        return service.submit(json.encode(submission), new ExtensionJobMutation(key, 0), () -> submission);
    }

    private ExtensionJobService.ClaimedJob claim(String jobId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            Optional<ExtensionJobService.ClaimedJob> candidate = service.claimNext();
            if (candidate.isPresent() && candidate.orElseThrow().job().id().equals(jobId)) {
                return candidate.orElseThrow();
            }
        }
        throw new AssertionError("Job 未被领取: " + jobId);
    }

    private ExtensionJob running(java.sql.Connection connection, String id) throws Exception {
        return repository.markRunning(connection, insert(connection, id), NOW);
    }

    private ExtensionJob active(java.sql.Connection connection, String id) throws Exception {
        ExtensionJob running = running(connection, id);
        return repository.recordIntent(
                connection,
                running,
                new ExtensionJobWorkUnit("unit-" + id, json.parse("{\"id\":\"" + id + "\"}")),
                NOW);
    }

    private ExtensionJob waitingInput(java.sql.Connection connection, String id) throws Exception {
        ExtensionJob active = active(connection, id);
        ExtensionJobUnit unit = repository.activeUnit(connection, active).orElseThrow();
        return repository.completeUnit(connection, active, unit, step(ExecutionState.WAITING_INPUT), NOW);
    }

    private ExtensionJob insert(java.sql.Connection connection, String id) throws Exception {
        return repository.insert(connection, id, submission(id), NOW);
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

    private ExtensionJobStepResult step(ExecutionState next) {
        return new ExtensionJobStepResult(
                json.parse("{\"ok\":true}"),
                json.parse("{\"checkpoint\":true}"),
                next,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private void updateJob(String jobId, String assignment) throws Exception {
        try (var connection = database.open();
                var statement =
                        connection.prepareStatement("UPDATE CORE.EXTENSION_JOB SET " + assignment + " WHERE ID = ?")) {
            statement.setString(1, jobId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }
}
