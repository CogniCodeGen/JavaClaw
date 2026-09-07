package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private MutableClock clock;
    private CanonicalJson json;
    private H2Database database;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private ApprovalService approvals;

    @BeforeEach
    void initializeDataV6() {
        clock = new MutableClock(NOW);
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        approvals = new ApprovalService(database, json, clock);
    }

    @Test
    void approvalRoundTripIsDurableIdempotentAndRevocable() throws Exception {
        AgentTurn turn = runningTurn("approved");
        ApprovalRequest request = request("approval-1", turn, NOW.plusSeconds(60));
        CoreRpcContracts.ApprovalResolvePayload payload =
                new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "允许本次写入");
        CommandIdentity identity = identity("approval/resolve", "approval-key", 1, payload);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(request, new CancellationSource()));
            awaitState(request.id(), ApprovalState.PENDING);
            assertEquals(
                    TurnStatus.WAITING, core.findTurn(turn.id()).orElseThrow().status());

            ApprovalRecord resolved = approvals.resolve(identity, payload);
            ApprovalRecord returned = waiting.get();
            ApprovalRecord retried = approvals.resolve(identity, payload);
            ApprovalRecord awaitedAgain = approvals.await(request, new CancellationSource());
            ApprovalRecord revoked = approvals.revokeApproved(request.id(), "管理员撤权");

            assertEquals(ApprovalState.APPROVED, resolved.state());
            assertEquals(resolved, returned);
            assertEquals(resolved, retried);
            assertEquals(resolved, awaitedAgain);
            assertEquals(ApprovalState.REVOKED, revoked.state());
            assertEquals(revoked, approvals.revokeApproved(request.id(), "重复撤权"));
            assertEquals(
                    TurnStatus.RUNNING, core.findTurn(turn.id()).orElseThrow().status());
            assertTrue(approvals.list(Optional.of(turn.id()), true).size() == 1);
            assertTrue(approvals.list(Optional.empty(), false).isEmpty());
        }
    }

    @Test
    void denialAndResolveConflictsDoNotCorruptPendingState() throws Exception {
        AgentTurn turn = runningTurn("denied");
        ApprovalRequest request = request("approval-2", turn, NOW.plusSeconds(60));
        CoreRpcContracts.ApprovalResolvePayload denied =
                new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.DENIED, "风险过高");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(request, new CancellationSource()));
            awaitState(request.id(), ApprovalState.PENDING);

            assertThrows(
                    PersistenceException.class,
                    () -> approvals.resolve(identity("approval/resolve", "missing-revision", 0, denied), denied));
            assertThrows(
                    PersistenceException.class,
                    () -> approvals.resolve(identity("approval/resolve", "stale-revision", 2, denied), denied));
            ApprovalRecord resolved = approvals.resolve(identity("approval/resolve", "deny-key", 1, denied), denied);
            assertEquals(ApprovalState.DENIED, waiting.get().state());
            assertEquals(ApprovalState.DENIED, resolved.state());

            CoreRpcContracts.ApprovalResolvePayload changed =
                    new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.DENIED, "不同内容");
            assertThrows(
                    PersistenceException.class,
                    () -> approvals.resolve(identity("approval/resolve", "deny-key", 1, changed), changed));
            assertThrows(
                    PersistenceException.class,
                    () -> approvals.resolve(identity("other/resolve", "deny-key", 1, denied), denied));
        }
    }

    @Test
    void expiredClientDecisionAndInvalidApprovalReferencesFailClosed() throws Exception {
        AgentTurn turn = runningTurn("client-expired");
        ApprovalRequest request = request("approval-expired", turn, NOW.plusSeconds(5));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(request, new CancellationSource()));
            awaitState(request.id(), ApprovalState.PENDING);
            clock.advance(Duration.ofSeconds(10));
            CoreRpcContracts.ApprovalResolvePayload payload =
                    new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "过期批准");
            CommandIdentity identity = identity("approval/resolve", "expired-decision", 1, payload);
            ApprovalRecord resolved = approvals.resolve(identity, payload);
            assertEquals(ApprovalState.EXPIRED, resolved.state());
            assertEquals(ApprovalState.EXPIRED, waiting.get().state());
            assertEquals(resolved, approvals.resolve(identity, payload));
        }

        CoreRpcContracts.ApprovalResolvePayload missing =
                new CoreRpcContracts.ApprovalResolvePayload("missing", ApprovalDecision.DENIED, "不存在");
        assertThrows(
                PersistenceException.class,
                () -> approvals.resolve(identity("approval/resolve", "missing", 1, missing), missing));
        assertThrows(PersistenceException.class, () -> approvals.revokeApproved("missing", "不存在"));
        assertThrows(NullPointerException.class, () -> approvals.list(null, true));

        AgentTurn stopped = runningTurn("stopped");
        journal.transition(stopped.id(), TurnStatus.RUNNING, TurnStatus.FAILED, Optional.of("STOPPED"));
        assertThrows(
                PersistenceException.class,
                () -> approvals.await(
                        request("approval-stopped", stopped, clock.instant().plusSeconds(60)),
                        new CancellationSource()));
    }

    @Test
    void concurrentExpiredDecisionsConvergeWithoutDatabaseDeadlock() throws Exception {
        AgentTurn turn = runningTurn("concurrent-expiration");
        ApprovalRequest request = request("approval-concurrent-expiration", turn, NOW.plusSeconds(5));
        CoreRpcContracts.ApprovalResolvePayload payload =
                new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "并发过期批准");
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(request, new CancellationSource()));
            awaitState(request.id(), ApprovalState.PENDING);
            clock.advance(Duration.ofSeconds(10));

            List<Future<ApprovalRecord>> decisions = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                String key = "concurrent-expiration-" + index;
                decisions.add(executor.submit(() -> {
                    start.await();
                    return approvals.resolve(identity("approval/resolve", key, 1, payload), payload);
                }));
            }
            start.countDown();

            assertEquals(ApprovalState.EXPIRED, waiting.get(5, TimeUnit.SECONDS).state());
            for (Future<ApprovalRecord> decision : decisions) {
                assertEquals(
                        ApprovalState.EXPIRED, decision.get(5, TimeUnit.SECONDS).state());
            }
            assertEquals(
                    TurnStatus.RUNNING, core.findTurn(turn.id()).orElseThrow().status());
        }
    }

    @Test
    void cancellationAndExpirationConvergeWaitingTurn() throws Exception {
        AgentTurn cancelledTurn = runningTurn("cancelled");
        ApprovalRequest cancelledRequest = request("approval-3", cancelledTurn, NOW.plusSeconds(60));
        CancellationSource cancellation = new CancellationSource();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(cancelledRequest, cancellation));
            awaitState(cancelledRequest.id(), ApprovalState.PENDING);
            cancellation.cancel("用户取消");
            ExecutionException failure = assertThrows(ExecutionException.class, waiting::get);
            assertInstanceOf(com.javaclaw.api.TurnCancelledException.class, failure.getCause());
            assertEquals(
                    ApprovalState.CANCELLED,
                    approvals
                            .list(Optional.of(cancelledTurn.id()), true)
                            .getFirst()
                            .state());
        }

        AgentTurn expiredTurn = runningTurn("expired");
        ApprovalRequest expiredRequest = request("approval-4", expiredTurn, NOW.plusSeconds(5));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting =
                    executor.submit(() -> approvals.await(expiredRequest, new CancellationSource()));
            awaitState(expiredRequest.id(), ApprovalState.PENDING);
            clock.advance(Duration.ofSeconds(10));
            assertEquals(ApprovalState.EXPIRED, waiting.get().state());
            assertEquals(
                    TurnStatus.RUNNING,
                    core.findTurn(expiredTurn.id()).orElseThrow().status());
        }
    }

    @Test
    void duplicateApprovalIdMustRepresentTheSameToolCall() throws Exception {
        AgentTurn turn = runningTurn("duplicate");
        ApprovalRequest request = request("approval-5", turn, NOW.plusSeconds(60));
        ApprovalRequest conflict = new ApprovalRequest(
                request.id(),
                request.turnId(),
                "other-call",
                request.tool(),
                request.risk(),
                request.explanation(),
                request.requestDigest(),
                request.createdAt(),
                request.expiresAt());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> first = executor.submit(() -> approvals.await(request, new CancellationSource()));
            awaitState(request.id(), ApprovalState.PENDING);
            assertThrows(PersistenceException.class, () -> approvals.await(conflict, new CancellationSource()));
            assertRequestConflict(
                    request,
                    copy(
                            request,
                            com.javaclaw.api.TurnId.random(),
                            request.tool(),
                            request.risk(),
                            request.requestDigest()));
            assertRequestConflict(
                    request,
                    copy(
                            request,
                            request.turnId(),
                            new ToolIdentity("builtin.other", "write", 1),
                            request.risk(),
                            request.requestDigest()));
            assertRequestConflict(
                    request,
                    copy(request, request.turnId(), request.tool(), ToolRisk.WORKSPACE_WRITE, request.requestDigest()));
            assertRequestConflict(
                    request, copy(request, request.turnId(), request.tool(), request.risk(), "b".repeat(64)));
            CoreRpcContracts.ApprovalResolvePayload payload =
                    new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "允许");
            approvals.resolve(identity("approval/resolve", "duplicate-key", 1, payload), payload);
            assertEquals(ApprovalState.APPROVED, first.get().state());
        }
    }

    @Test
    void 重启保留可恢复审批而正常关闭才取消进程内等待() throws Exception {
        AgentTurn interruptedTurn = runningTurn("restart");
        ApprovalRequest interrupted = request("approval-6", interruptedTurn, NOW.plusSeconds(60));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting =
                    executor.submit(() -> approvals.await(interrupted, new CancellationSource()));
            awaitState(interrupted.id(), ApprovalState.PENDING);
            ApprovalService restarted = new ApprovalService(database, json, clock);
            assertEquals(0, restarted.expireDue());
            assertEquals(
                    ApprovalState.PENDING,
                    approvals.list(Optional.empty(), false).getFirst().state());
            restarted.resolve(
                    identity(
                            "approval/resolve",
                            "restart-resolve",
                            1,
                            new CoreRpcContracts.ApprovalResolvePayload(
                                    interrupted.id(), ApprovalDecision.APPROVED, "重启后批准")),
                    new CoreRpcContracts.ApprovalResolvePayload(interrupted.id(), ApprovalDecision.APPROVED, "重启后批准"));
            assertEquals(ApprovalState.APPROVED, waiting.get().state());
            assertEquals(
                    TurnStatus.RUNNING,
                    core.findTurn(interruptedTurn.id()).orElseThrow().status());
        }

        AgentTurn closedTurn = runningTurn("close");
        ApprovalRequest closed = request("approval-7", closedTurn, NOW.plusSeconds(60));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(closed, new CancellationSource()));
            awaitState(closed.id(), ApprovalState.PENDING);
            approvals.close();
            ExecutionException failure = assertThrows(ExecutionException.class, waiting::get);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals(
                    ApprovalState.CANCELLED,
                    approvals
                            .list(Optional.of(closedTurn.id()), true)
                            .getFirst()
                            .state());
        }
    }

    @Test
    void externalCallBoundaryRequiresTheExactCurrentlyApprovedRequest() throws Exception {
        AgentTurn turn = runningTurn("external-boundary");
        ApprovalRequest request = request("approval-external", turn, NOW.plusSeconds(60));
        CoreRpcContracts.ApprovalResolvePayload payload =
                new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "允许外部调用");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(request, new CancellationSource()));
            awaitState(request.id(), ApprovalState.PENDING);
            assertThrows(PersistenceException.class, () -> approvals.markApprovedExternalCall(request));

            approvals.resolve(identity("approval/resolve", "external-boundary", 1, payload), payload);
            assertEquals(ApprovalState.APPROVED, waiting.get().state());
            approvals.markApprovedExternalCall(request);
            approvals.revokeApproved(request.id(), "实时撤权");
            assertThrows(PersistenceException.class, () -> approvals.markApprovedExternalCall(request));
        }
    }

    @Test
    void expiredApprovalRejectsASecondDecisionWithAnotherExpectedRevision() throws Exception {
        AgentTurn turn = runningTurn("expired-revision");
        ApprovalRequest request = request("approval-expired-revision", turn, NOW.plusSeconds(5));
        CoreRpcContracts.ApprovalResolvePayload payload =
                new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "过期决议");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ApprovalRecord> waiting = executor.submit(() -> approvals.await(request, new CancellationSource()));
            awaitState(request.id(), ApprovalState.PENDING);
            clock.advance(Duration.ofSeconds(10));
            assertEquals(ApprovalState.EXPIRED, waiting.get().state());
            assertThrows(
                    PersistenceException.class,
                    () -> approvals.resolve(identity("approval/resolve", "expired-stale", 2, payload), payload));
        }
    }

    @Test
    void listenerFailuresAreAggregatedAfterTheDurableStateCommit() throws Exception {
        AgentTurn turn = runningTurn("listener-failure");
        ApprovalRequest request = request("approval-listener", turn, NOW.plusSeconds(60));
        IllegalStateException first = new IllegalStateException("first listener failed");
        approvals.onChanged(record -> {
            throw first;
        });
        approvals.onChanged(record -> {
            throw new IllegalArgumentException("second listener failed");
        });

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> approvals.await(request, new CancellationSource()));

        assertEquals(first, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(
                ApprovalState.PENDING,
                approvals.list(Optional.of(turn.id()), true).getFirst().state());
    }

    @Test
    void closedServiceRejectsNewWaitsAndRepeatedCloseIsSafe() {
        AgentTurn turn = runningTurn("closed-before-wait");
        ApprovalRequest request = request("approval-closed", turn, NOW.plusSeconds(60));

        approvals.close();
        approvals.close();

        assertThrows(IllegalStateException.class, () -> approvals.await(request, new CancellationSource()));
    }

    private AgentTurn runningTurn(String suffix) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("审批测试", temporaryDirectory.resolve("workspace"));
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
        CorePayloads.Message message =
                new CorePayloads.Message(com.javaclaw.api.MessageRole.USER, "执行工具", List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload turnPayload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), message.text());
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, turnPayload),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget(), message));
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
                NOW,
                expiresAt);
        if (core.findTurn(turn.id()).orElseThrow().status() == TurnStatus.RUNNING
                && journal.readRecovery(turn.id()).phase() == TurnExecutionPhase.READY_FOR_MODEL) {
            prepareToolIntent(turn, request, arguments);
        }
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
        ToolCatalogSnapshot catalog = catalog(turn.id());
        journal.recordToolIntent(
                turn.id(),
                0,
                new ToolCallRequest(
                        turn.id(), call.callId(), call.tool(), arguments, turn.id() + ":" + call.callId(), 1),
                1,
                "2".repeat(64));
    }

    private TurnExecutionCommand command(AgentTurn turn) {
        ToolCatalogSnapshot catalog = catalog(turn.id());
        return new TurnExecutionCommand(turn, turn.provider(), "system", "user", catalog.permissionCeiling(), catalog);
    }

    private ToolCatalogSnapshot catalog(com.javaclaw.api.TurnId turnId) {
        return json.decode(core.toolCatalogSnapshot(turnId), ToolCatalogSnapshot.class);
    }

    private void assertRequestConflict(ApprovalRequest original, ApprovalRequest changed) {
        assertThrows(PersistenceException.class, () -> approvals.await(changed, new CancellationSource()));
        assertEquals(
                ApprovalState.PENDING,
                approvals.list(Optional.of(original.turnId()), true).getFirst().state());
    }

    private ApprovalRequest copy(
            ApprovalRequest source, com.javaclaw.api.TurnId turnId, ToolIdentity tool, ToolRisk risk, String digest) {
        return new ApprovalRequest(
                source.id(),
                turnId,
                source.callId(),
                tool,
                risk,
                source.explanation(),
                digest,
                source.createdAt(),
                source.expiresAt());
    }

    private void awaitState(String id, ApprovalState state) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<ApprovalRecord> records = approvals.list(Optional.empty(), true);
            if (records.stream().anyMatch(record -> record.request().id().equals(id) && record.state() == state)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("审批未进入预期状态: " + state);
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
