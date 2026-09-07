package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputRequestServiceBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private MutableClock clock;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private InputRequestService inputs;

    @BeforeEach
    void initializeDataV6() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        clock = new MutableClock(NOW);
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        inputs = new InputRequestService(database, json, clock);
    }

    @Test
    void await在父Turn已取消时持久化取消并返回空响应() throws Exception {
        AgentTurn turn = runningTurn("await-cancel");
        InputRequest request = request("await-cancel", turn.id(), "workflow", NOW.plusSeconds(60));
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("用户停止");

        assertTrue(inputs.await(request, cancellation).isEmpty());
        assertEquals(
                InputRequestState.CANCELLED,
                inputs.find(request.id()).orElseThrow().state());
        assertEquals(TurnStatus.RUNNING, core.findTurn(turn.id()).orElseThrow().status());
    }

    @Test
    void await轮询权威状态并返回另一个线程提交的响应() throws Exception {
        AgentTurn turn = runningTurn("await-resolve");
        InputRequest request = request("await-resolve", turn.id(), "workflow", NOW.plusSeconds(60));
        CountDownLatch opened = new CountDownLatch(1);
        inputs.onChanged(record -> {
            if (record.state() == InputRequestState.PENDING) {
                opened.countDown();
            }
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = executor.submit(() -> inputs.await(request, new CancellationSource()));
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            var payload = new InputJobRpcContracts.InputResolvePayload(request.id(), json.parse("{\"choice\":\"ok\"}"));
            inputs.resolve(identity("turn/input/resolve", "await-response", 1, payload), payload);

            assertEquals(
                    json.parse("{\"choice\":\"ok\"}"),
                    waiting.get(2, TimeUnit.SECONDS).orElseThrow());
        }
    }

    @Test
    void await观察到绝对期限后将请求与Turn原子收口() throws Exception {
        AgentTurn turn = runningTurn("await-expire");
        InputRequest request = request("await-expire", turn.id(), "workflow", NOW.plusSeconds(1));
        CountDownLatch opened = new CountDownLatch(1);
        inputs.onChanged(record -> {
            if (record.state() == InputRequestState.PENDING) {
                opened.countDown();
            }
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = executor.submit(() -> inputs.await(request, new CancellationSource()));
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            clock.advance(Duration.ofSeconds(2));

            assertTrue(waiting.get(2, TimeUnit.SECONDS).isEmpty());
        }
        assertEquals(
                InputRequestState.EXPIRED,
                inputs.find(request.id()).orElseThrow().state());
        assertEquals(TurnStatus.RUNNING, core.findTurn(turn.id()).orElseThrow().status());
    }

    @Test
    void completeResolved只允许所属生产者关闭已决议输入() {
        AgentTurn turn = runningTurn("complete");
        InputRequest pending = request("complete", turn.id(), "workflow", NOW.plusSeconds(60));
        inputs.open(pending);

        assertThrows(PersistenceException.class, () -> inputs.completeResolved(pending.id(), "workflow"));
        assertThrows(PersistenceException.class, () -> inputs.completeResolved(pending.id(), "other"));
        var payload = new InputJobRpcContracts.InputResolvePayload(pending.id(), json.parse("{\"ok\":true}"));
        InputRequestRecord resolved = inputs.resolve(identity("turn/input/resolve", "complete", 1, payload), payload);
        assertThrows(PersistenceException.class, () -> inputs.completeResolved(pending.id(), "other"));

        assertEquals(resolved, inputs.completeResolved(pending.id(), "workflow"));
        assertEquals(resolved, inputs.completeResolved(pending.id(), "workflow"));
        assertEquals(
                TurnStatus.COMPLETED, core.findTurn(turn.id()).orElseThrow().status());
        assertThrows(PersistenceException.class, () -> inputs.completeResolved("missing", "workflow"));
    }

    @Test
    void cancel校验生产者原因并对终态保持幂等() {
        AgentTurn turn = runningTurn("cancel");
        InputRequest request = request("cancel", turn.id(), "workflow", NOW.plusSeconds(60));
        inputs.open(request);

        assertThrows(IllegalArgumentException.class, () -> inputs.cancel(request.id(), "workflow", " "));
        assertThrows(PersistenceException.class, () -> inputs.cancel(request.id(), "other", "用户取消"));
        InputRequestRecord cancelled = inputs.cancel(request.id(), "workflow", "用户取消");

        assertEquals(InputRequestState.CANCELLED, cancelled.state());
        assertEquals(cancelled, inputs.cancel(request.id(), "workflow", "再次取消"));
        assertThrows(PersistenceException.class, () -> inputs.cancel("missing", "workflow", "用户取消"));
    }

    @Test
    void open拒绝不同内容重用Id非运行Turn和SecretSchema() {
        AgentTurn running = runningTurn("open-conflict");
        InputRequest first = request("same-id", running.id(), "workflow", NOW.plusSeconds(60));
        inputs.open(first);

        InputRequest changed = new InputRequest(
                first.id(),
                first.turnId(),
                first.producerId(),
                "不同问题",
                first.responseSchema(),
                first.createdAt(),
                first.expiresAt());
        assertThrows(PersistenceException.class, () -> inputs.open(changed));

        AgentTurn queued = queuedTurn("queued");
        assertThrows(
                PersistenceException.class,
                () -> inputs.open(request("queued", queued.id(), "workflow", NOW.plusSeconds(60))));
        assertThrows(
                PersistenceException.class,
                () -> inputs.open(request("missing-turn", TurnId.random(), "workflow", NOW.plusSeconds(60))));
        InputRequest secretSchema = new InputRequest(
                "secret-schema",
                running.id(),
                "workflow",
                "输入",
                json.parse("{\"properties\":{\"password\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                NOW,
                NOW.plusSeconds(60));
        assertThrows(PersistenceException.class, () -> inputs.open(secretSchema));
    }

    @Test
    void 状态监听器失败不回滚已提交记录并聚合后续异常() {
        AgentTurn turn = runningTurn("listener");
        InputRequest request = request("listener", turn.id(), "workflow", NOW.plusSeconds(60));
        inputs.onChanged(ignored -> {
            throw new IllegalStateException("first listener");
        });
        inputs.onChanged(ignored -> {
            throw new IllegalArgumentException("second listener");
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> inputs.open(request));

        assertEquals(1, failure.getSuppressed().length);
        assertEquals(
                InputRequestState.PENDING,
                inputs.find(request.id()).orElseThrow().state());
    }

    @Test
    void 关联过期只收口同Turn且已到期的等待请求() throws Exception {
        AgentTurn turn = runningTurn("linked-expire");
        InputRequest request = request("linked-expire", turn.id(), "workflow", NOW.plusSeconds(30));
        inputs.open(request);

        assertThrows(
                PersistenceException.class,
                () -> transaction(connection ->
                        inputs.expireLinked(connection, request.id(), TurnId.random(), NOW.plusSeconds(31))));
        assertTrue(transaction(connection -> inputs.expireLinked(connection, request.id(), turn.id(), NOW))
                .isEmpty());

        InputRequestRecord expired = transaction(
                        connection -> inputs.expireLinked(connection, request.id(), turn.id(), NOW.plusSeconds(31)))
                .orElseThrow();
        assertEquals(InputRequestState.EXPIRED, expired.state());
        assertTrue(
                transaction(connection -> inputs.expireLinked(connection, request.id(), turn.id(), NOW.plusSeconds(32)))
                        .isEmpty());
    }

    @Test
    void 关联取消与完成校验Turn归属和终态() throws Exception {
        AgentTurn turn = runningTurn("linked-cancel");
        InputRequest request = request("linked-cancel", turn.id(), "workflow", NOW.plusSeconds(30));
        inputs.open(request);

        assertThrows(
                PersistenceException.class,
                () -> transaction(
                        connection -> inputs.cancelLinked(connection, request.id(), TurnId.random(), "用户取消")));
        InputRequestRecord cancelled = transaction(
                        connection -> inputs.cancelLinked(connection, request.id(), turn.id(), "用户取消"))
                .orElseThrow();
        assertEquals(InputRequestState.CANCELLED, cancelled.state());
        assertTrue(transaction(connection -> inputs.cancelLinked(connection, request.id(), turn.id(), "重复取消"))
                .isEmpty());
        assertThrows(
                PersistenceException.class,
                () -> transaction(connection -> {
                    inputs.completeLinked(connection, request.id(), TurnId.random());
                    return null;
                }));
        transaction(connection -> {
            inputs.completeLinked(connection, request.id(), turn.id());
            return null;
        });

        AgentTurn pendingTurn = runningTurn("linked-pending");
        InputRequest pending = request("linked-pending", pendingTurn.id(), "workflow", NOW.plusSeconds(30));
        inputs.open(pending);
        assertThrows(
                PersistenceException.class,
                () -> transaction(connection -> {
                    inputs.completeLinked(connection, pending.id(), pendingTurn.id());
                    return null;
                }));
    }

    @Test
    void 非法标识和底层Sql故障均以明确边界失败() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> inputs.find("bad/id"));
        assertFalse(inputs.find("missing").isPresent());
        try (var connection = database.open();
                var statement = connection.prepareStatement("DROP TABLE CORE.INPUT_REQUEST CASCADE")) {
            statement.executeUpdate();
        }

        PersistenceException failure = assertThrows(PersistenceException.class, () -> inputs.find("missing"));
        assertTrue(failure.getMessage().contains("InputRequest"));
    }

    private AgentTurn runningTurn(String suffix) {
        AgentTurn turn = queuedTurn(suffix);
        journal.transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return core.findTurn(turn.id()).orElseThrow();
    }

    private AgentTurn queuedTurn(String suffix) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("输入分支测试", temporaryDirectory.resolve("workspace"));
            return core.createWorkspace(
                    identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
        });
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload turnPayload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), suffix);
        return core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, turnPayload),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget(), message));
    }

    private InputRequest request(String id, TurnId turnId, String producer, Instant expiresAt) {
        return new InputRequest(
                id,
                turnId,
                producer,
                "请选择下一步",
                json.parse("{\"properties\":{\"choice\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                NOW,
                expiresAt);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private <T> T transaction(H2Transactions.SqlWork<T> work) throws Exception {
        return new H2Transactions(database).execute(work);
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
