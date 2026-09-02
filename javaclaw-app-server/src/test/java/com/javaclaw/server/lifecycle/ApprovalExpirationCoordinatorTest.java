package com.javaclaw.server.lifecycle;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionPhase;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalExpirationCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Duration TEST_SCAN_INTERVAL = Duration.ofMillis(10);

    @TempDir
    Path temporaryDirectory;

    private MutableClock clock;
    private CanonicalJson json;
    private H2Database database;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private ApprovalService approvals;

    @BeforeEach
    void initializeDataV5() {
        clock = new MutableClock(NOW);
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        approvals = new ApprovalService(database, json, clock);
    }

    @Test
    void restartBeforeExpiryRebuildsLeaseAndKeepsPersistedDeadline() throws Exception {
        LifecycleCoordinator firstLifecycle = lifecycle();
        ApprovalRequest request;
        try (ApprovalLifecycleCoordinator leaseProjection =
                        new ApprovalLifecycleCoordinator(approvals, firstLifecycle, clock);
                ApprovalExpirationCoordinator expiration =
                        new ApprovalExpirationCoordinator(approvals, clock, TEST_SCAN_INTERVAL)) {
            request = persistPending("restart-before-expiry", NOW.plusSeconds(5));
            assertEquals(1, firstLifecycle.status().activeLeases());
        } finally {
            firstLifecycle.close();
        }

        assertEquals(ApprovalState.PENDING, record(request.id()).state());
        assertEquals(NOW.plusSeconds(5), record(request.id()).request().expiresAt());

        LifecycleCoordinator restartedLifecycle = lifecycle();
        CountDownLatch resumed = new CountDownLatch(1);
        try (ApprovalLifecycleCoordinator leaseProjection =
                        new ApprovalLifecycleCoordinator(approvals, restartedLifecycle, clock);
                ApprovalExpirationCoordinator expiration =
                        new ApprovalExpirationCoordinator(approvals, clock, TEST_SCAN_INTERVAL)) {
            leaseProjection.bindResume(ignored -> resumed.countDown());
            assertEquals(1, restartedLifecycle.status().activeLeases());

            clock.advance(Duration.ofSeconds(5));

            assertTrue(resumed.await(1, TimeUnit.SECONDS));
            assertEquals(ApprovalState.EXPIRED, record(request.id()).state());
            assertEquals(0, restartedLifecycle.status().activeLeases());
            assertEquals(
                    TurnExecutionPhase.TOOL_APPROVAL_RESOLVED,
                    journal.readRecovery(request.turnId()).phase());
        } finally {
            restartedLifecycle.close();
        }
    }

    @Test
    void pendingApprovalExpiresWithoutClientReadOrWaitingThread() throws Exception {
        CountDownLatch expired = new CountDownLatch(1);
        approvals.onChanged(record -> {
            if (record.state() == ApprovalState.EXPIRED) {
                expired.countDown();
            }
        });
        try (ApprovalExpirationCoordinator expiration =
                new ApprovalExpirationCoordinator(approvals, clock, TEST_SCAN_INTERVAL)) {
            ApprovalRequest request = persistPending("expiry-without-read", NOW.plusSeconds(5));

            clock.advance(Duration.ofSeconds(6));

            assertTrue(expired.await(1, TimeUnit.SECONDS));
            ApprovalRecord terminal = record(request.id());
            assertEquals(ApprovalState.EXPIRED, terminal.state());
            assertEquals(2, terminal.revision());
            assertEquals(
                    TurnStatus.RUNNING,
                    core.findTurn(request.turnId()).orElseThrow().status());
        }
    }

    @Test
    void resolveAndExpirationRaceConvergesToOneExpiredTransition() throws Exception {
        ApprovalRequest request = persistPending("resolve-expiry-race", NOW.plusSeconds(5));
        clock.advance(Duration.ofSeconds(6));
        CoreRpcContracts.ApprovalResolvePayload payload =
                new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "允许执行");
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalExpirationCoordinator> projection = executor.submit(() -> {
                start.await();
                return new ApprovalExpirationCoordinator(approvals, clock, TEST_SCAN_INTERVAL);
            });
            Future<ApprovalRecord> decision = executor.submit(() -> {
                start.await();
                return approvals.resolve(identity("approval/resolve", "race-resolve", 1, payload), payload);
            });
            start.countDown();

            try (ApprovalExpirationCoordinator expiration = projection.get(1, TimeUnit.SECONDS)) {
                assertEquals(
                        ApprovalState.EXPIRED, decision.get(1, TimeUnit.SECONDS).state());
            }
        }

        ApprovalRecord terminal = record(request.id());
        assertEquals(ApprovalState.EXPIRED, terminal.state());
        assertEquals(2, terminal.revision());
        assertEquals(1, approvals.list(Optional.of(request.turnId()), true).size());
        assertEquals(
                TurnStatus.RUNNING,
                core.findTurn(request.turnId()).orElseThrow().status());
    }

    @Test
    void shutdownCancelsProjectionAndRestartExpiresFromOriginalDeadline() throws Exception {
        ApprovalExpirationCoordinator stopped = new ApprovalExpirationCoordinator(approvals, clock, TEST_SCAN_INTERVAL);
        ApprovalRequest request = persistPending("shutdown-restart", NOW.plusSeconds(5));

        stopped.close();
        clock.advance(Duration.ofSeconds(6));
        Thread.sleep(TEST_SCAN_INTERVAL.multipliedBy(4));

        assertEquals(ApprovalState.PENDING, record(request.id()).state());
        try (ApprovalExpirationCoordinator restarted =
                new ApprovalExpirationCoordinator(approvals, clock, TEST_SCAN_INTERVAL)) {
            assertEquals(ApprovalState.EXPIRED, record(request.id()).state());
            assertEquals(NOW.plusSeconds(5), record(request.id()).request().expiresAt());
        }
    }

    private ApprovalRequest persistPending(String suffix, Instant expiresAt) throws Exception {
        AgentTurn turn = runningTurn(suffix);
        ApprovalRequest request = request("approval-" + suffix, turn, expiresAt);
        AtomicReference<Throwable> interruption = new AtomicReference<>();
        Thread waiting = Thread.ofVirtual().name("approval-restart-fixture").start(() -> {
            try {
                approvals.await(request, new CancellationSource());
            } catch (Throwable failure) {
                interruption.set(failure);
            }
        });
        awaitState(request.id(), ApprovalState.PENDING);
        waiting.interrupt();
        waiting.join(1_000);

        assertFalse(waiting.isAlive(), "审批等待线程必须响应中断并退出");
        assertInstanceOf(InterruptedException.class, interruption.get());
        assertEquals(ApprovalState.PENDING, record(request.id()).state());
        return request;
    }

    private AgentTurn runningTurn(String suffix) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("审批到期测试", temporaryDirectory.resolve("workspace"));
            return core.createWorkspace(
                    identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
        });
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, "审批-" + suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                threadPayload.title());
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, "执行工具", List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload payload = TurnContractFixtures.payload(thread.id(), message.text());
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, payload),
                TurnContractFixtures.request(thread.id(), budget(), message));
        journal.beginOrRecover(command(turn));
        return core.findTurn(turn.id()).orElseThrow();
    }

    private ApprovalRequest request(String id, AgentTurn turn, Instant expiresAt) {
        com.javaclaw.api.CanonicalPayload arguments = json.parse("{\"approval\":\"" + id + "\"}");
        ApprovalRequest request = new ApprovalRequest(
                id,
                turn.id(),
                "call-" + id,
                new ToolIdentity("builtin.test", "write", 1),
                ToolRisk.EXTERNAL_EFFECT,
                "将写入工作区文件",
                arguments.sha256(),
                clock.instant(),
                expiresAt);
        prepareToolIntent(turn, request, arguments);
        return request;
    }

    private void prepareToolIntent(
            AgentTurn turn, ApprovalRequest approval, com.javaclaw.api.CanonicalPayload arguments) {
        ModelToolCall call = new ModelToolCall(approval.callId(), approval.tool(), arguments);
        journal.recordModelIntent(turn.id(), 1, "1".repeat(64));
        journal.commitModelResult(
                turn.id(),
                1,
                new ModelInvocationResult(
                        "",
                        List.of(call),
                        new ModelUsage(2, 1, 0, 0),
                        Optional.empty(),
                        Optional.empty(),
                        ModelFinishReason.TOOL_CALLS),
                new ModelUsage(2, 1, 0, 0));
        journal.recordToolIntent(
                turn.id(),
                0,
                new ToolCallRequest(
                        turn.id(), call.callId(), call.tool(), arguments, turn.id() + ":" + call.callId(), 1),
                1,
                "2".repeat(64));
    }

    private TurnExecutionCommand command(AgentTurn turn) {
        ToolCatalogSnapshot catalog = catalog(turn);
        return new TurnExecutionCommand(turn, turn.provider(), "system", "user", catalog.permissionCeiling(), catalog);
    }

    private ToolCatalogSnapshot catalog(AgentTurn turn) {
        return json.decode(core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
    }

    private ApprovalRecord record(String approvalId) {
        return approvals.list(Optional.empty(), true).stream()
                .filter(record -> record.request().id().equals(approvalId))
                .findFirst()
                .orElseThrow();
    }

    private void awaitState(String approvalId, ApprovalState state) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (approvals.list(Optional.empty(), true).stream()
                    .anyMatch(record -> record.request().id().equals(approvalId) && record.state() == state)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("审批未进入预期状态: " + state);
    }

    private LifecycleCoordinator lifecycle() {
        return new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock), Duration.ofSeconds(60));
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

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
