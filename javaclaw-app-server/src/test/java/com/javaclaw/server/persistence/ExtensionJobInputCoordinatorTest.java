package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobInputWait;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionJobWorkUnit;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionJobInputCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Duration TEST_SCAN_INTERVAL = Duration.ofMillis(10);
    private static final ExtensionId WORKFLOW = new ExtensionId("javaclaw.workflow");

    @TempDir
    Path temporaryDirectory;

    private MutableClock clock;
    private CanonicalJson json;
    private H2Database database;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private InputRequestService inputs;
    private ExtensionJobService jobs;
    private Workspace workspace;

    @BeforeEach
    void initializeDataV6() {
        clock = new MutableClock(NOW);
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        inputs = new InputRequestService(database, json, clock);
        jobs = new ExtensionJobService(database, json, clock);
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("输入协调测试", temporaryDirectory.resolve("workspace"));
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
    }

    @Test
    void 周期扫描在无人读取时原子过期Input并失败等待Job() throws Exception {
        WaitingFixture waiting = waiting("periodic", NOW.plusSeconds(5));

        try (ExtensionJobInputCoordinator coordinator =
                new ExtensionJobInputCoordinator(database, inputs, clock, TEST_SCAN_INTERVAL)) {
            clock.advance(Duration.ofSeconds(6));
            awaitJobState(waiting.job().id(), ExecutionState.FAILED);
        }

        assertEquals(
                InputRequestState.EXPIRED,
                inputs.find(waiting.request().id()).orElseThrow().state());
        assertEquals(
                Optional.of("INPUT_REQUEST_EXPIRED"),
                jobs.find(waiting.job().id()).orElseThrow().errorCode());
        assertEquals(
                TurnStatus.COMPLETED,
                core.findTurn(waiting.request().turnId()).orElseThrow().status());
    }

    @Test
    void 重启按数据库原期限恢复等待关联且关闭不机械过期() throws Exception {
        WaitingFixture waiting = waiting("restart", NOW.plusSeconds(5));
        ExtensionJobInputCoordinator stopped =
                new ExtensionJobInputCoordinator(database, inputs, clock, TEST_SCAN_INTERVAL);
        stopped.close();

        assertEquals(
                InputRequestState.PENDING,
                inputs.find(waiting.request().id()).orElseThrow().state());
        clock.advance(Duration.ofSeconds(6));
        InputRequestService restartedInputs = new InputRequestService(database, json, clock);
        try (ExtensionJobInputCoordinator restarted =
                new ExtensionJobInputCoordinator(database, restartedInputs, clock, TEST_SCAN_INTERVAL)) {
            assertEquals(
                    InputRequestState.EXPIRED,
                    restartedInputs.find(waiting.request().id()).orElseThrow().state());
            assertEquals(
                    ExecutionState.FAILED,
                    jobs.find(waiting.job().id()).orElseThrow().state());
        }
    }

    @Test
    void Input取消会取消等待Job且Job取消会反向取消Input() throws Exception {
        WaitingFixture inputCancelled = waiting("input-cancelled", NOW.plusSeconds(30));
        WaitingFixture jobCancelled = waiting("job-cancelled", NOW.plusSeconds(30));
        WaitingFixture resolvedThenCancelled = waiting("resolved-cancelled", NOW.plusSeconds(30));
        try (ExtensionJobInputCoordinator coordinator =
                new ExtensionJobInputCoordinator(database, inputs, clock, TEST_SCAN_INTERVAL)) {
            inputs.cancel(inputCancelled.request().id(), WORKFLOW.value(), "用户取消输入");
            coordinator.runOnce();
            ExtensionJob cancelled = jobs.cancel(
                    jobCancelled.job().id(),
                    new ExtensionJobMutation("cancel-job", jobCancelled.job().revision()));
            assertEquals(ExecutionState.CANCELLED, cancelled.state());
            InputJobRpcContracts.InputResolvePayload resolvedPayload = new InputJobRpcContracts.InputResolvePayload(
                    resolvedThenCancelled.request().id(), json.parse("{\"choice\":\"continue\"}"));
            inputs.resolve(
                    identity("turn/input/resolve", "resolve-before-cancel", 1, resolvedPayload), resolvedPayload);
            jobs.cancel(
                    resolvedThenCancelled.job().id(),
                    new ExtensionJobMutation(
                            "cancel-resolved-job", resolvedThenCancelled.job().revision()));
            coordinator.runOnce();
        }

        assertEquals(
                ExecutionState.CANCELLED,
                jobs.find(inputCancelled.job().id()).orElseThrow().state());
        assertEquals(
                InputRequestState.CANCELLED,
                inputs.find(jobCancelled.request().id()).orElseThrow().state());
        assertEquals(
                TurnStatus.COMPLETED,
                core.findTurn(inputCancelled.request().turnId()).orElseThrow().status());
        assertEquals(
                TurnStatus.COMPLETED,
                core.findTurn(jobCancelled.request().turnId()).orElseThrow().status());
        assertEquals(
                InputRequestState.RESOLVED,
                inputs.find(resolvedThenCancelled.request().id()).orElseThrow().state());
        assertEquals(
                TurnStatus.COMPLETED,
                core.findTurn(resolvedThenCancelled.request().turnId())
                        .orElseThrow()
                        .status());
    }

    @Test
    void 到期后决议和继续Job都FailClosed() throws Exception {
        WaitingFixture waiting = waiting("late-continue", NOW.plusSeconds(5));
        try (ExtensionJobInputCoordinator coordinator =
                new ExtensionJobInputCoordinator(database, inputs, clock, TEST_SCAN_INTERVAL)) {
            clock.advance(Duration.ofSeconds(6));
            coordinator.runOnce();
        }
        InputJobRpcContracts.InputResolvePayload response = new InputJobRpcContracts.InputResolvePayload(
                waiting.request().id(), json.parse("{\"choice\":\"continue\"}"));

        assertThrows(
                PersistenceException.class,
                () -> inputs.resolve(identity("turn/input/resolve", "late-input", 1, response), response));
        assertThrows(
                PersistenceException.class,
                () -> jobs.continueWaiting(
                        waiting.job().id(),
                        ExecutionState.WAITING_INPUT,
                        json.parse("{\"continued\":true}"),
                        new ExtensionJobMutation("late-job", waiting.job().revision())));
    }

    @Test
    void Job只能在权威Input完成决议后继续并删除等待关联() throws Exception {
        WaitingFixture waiting = waiting("resolved-continue", NOW.plusSeconds(30));
        CanonicalPayload checkpoint = json.parse("{\"continued\":true}");

        assertThrows(
                PersistenceException.class,
                () -> jobs.continueWaiting(
                        waiting.job().id(),
                        ExecutionState.WAITING_INPUT,
                        checkpoint,
                        new ExtensionJobMutation(
                                "continue-pending", waiting.job().revision())));
        InputJobRpcContracts.InputResolvePayload response = new InputJobRpcContracts.InputResolvePayload(
                waiting.request().id(), json.parse("{\"choice\":\"continue\"}"));
        inputs.resolve(identity("turn/input/resolve", "resolve-for-continue", 1, response), response);

        ExtensionJob continued = jobs.continueWaiting(
                waiting.job().id(),
                ExecutionState.WAITING_INPUT,
                checkpoint,
                new ExtensionJobMutation("continue-resolved", waiting.job().revision()));

        assertEquals(ExecutionState.QUEUED, continued.state());
        assertEquals(checkpoint, continued.checkpoint());
    }

    @Test
    void coordinator拒绝非正扫描间隔且关闭可重复() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionJobInputCoordinator(database, inputs, clock, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionJobInputCoordinator(database, inputs, clock, Duration.ofMillis(-1)));

        ExtensionJobInputCoordinator coordinator =
                new ExtensionJobInputCoordinator(database, inputs, clock, TEST_SCAN_INTERVAL);
        coordinator.close();
        coordinator.close();
        assertEquals(0, coordinator.runOnce());
    }

    private WaitingFixture waiting(String suffix, Instant expiresAt) throws Exception {
        AgentTurn turn = runningTurn(suffix);
        InputRequest request = new InputRequest(
                "input-" + suffix,
                turn.id(),
                WORKFLOW.value(),
                "请选择下一步",
                json.parse("{\"properties\":{\"choice\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                clock.instant(),
                expiresAt);
        inputs.open(request);
        ExtensionJobSubmission submission = new ExtensionJobSubmission(
                WORKFLOW,
                workspace.id(),
                "definition-execution",
                "workflow-" + suffix,
                1,
                json.parse("{\"roleRevision\":1}"),
                json.parse("{\"node\":\"input\"}"));
        ExtensionJob submitted =
                jobs.submit(json.encode(submission), new ExtensionJobMutation("submit-" + suffix, 0), () -> submission);
        ExtensionJobService.ClaimedJob claimed = jobs.claimNext().orElseThrow();
        ExtensionJobService.ClaimedJob active =
                jobs.recordIntent(claimed, new ExtensionJobWorkUnit("input-node", json.parse("{\"openInput\":true}")));
        ExtensionJob waiting = jobs.complete(
                active,
                new ExtensionJobStepResult(
                        json.parse("{\"waiting\":true}"),
                        json.parse("{\"requestId\":\"" + request.id() + "\"}"),
                        ExecutionState.WAITING_INPUT,
                        Optional.of(turn.id()),
                        Optional.empty(),
                        Optional.of(new ExtensionJobInputWait(request.id(), turn.id()))));
        assertEquals(submitted.id(), waiting.id());
        return new WaitingFixture(waiting, request);
    }

    private AgentTurn runningTurn(String suffix) {
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload payload = TurnContractFixtures.payload(thread.id(), suffix);
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, payload),
                TurnContractFixtures.request(thread.id(), budget(), message));
        journal.transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return core.findTurn(turn.id()).orElseThrow();
    }

    private void awaitJobState(String jobId, ExecutionState expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline)) {
            if (jobs.find(jobId).orElseThrow().state() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, jobs.find(jobId).orElseThrow().state());
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private record WaitingFixture(ExtensionJob job, InputRequest request) {}

    private static final class MutableClock extends Clock {
        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
