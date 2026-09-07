package com.javaclaw.server.rpc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.ProviderRoleRpcFixtures;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStartIdempotencyRpcTest {
    @TempDir
    Path directory;

    private final RecordingModel model = new RecordingModel();
    private AppServerBootstrap.Components components;
    private AppServerSession session;
    private AgentRoleRef role;
    private ConversationThread thread;
    private CoreRpcContracts.TurnStartPayload payload;
    private WriteCommand command;

    @BeforeEach
    void prepareRealRpcChain() throws Exception {
        components = AppServerBootstrap.create(directory.resolve("data-v6"), Clock.systemUTC(), model);
        session = components.newSession();
        decode(
                invoke(
                        "initialize/session",
                        new InitializeParams(
                                ProtocolVersion.CURRENT,
                                new ClientInfo("turn-replay-test", "6.0"),
                                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()))),
                InitializeResult.class);
        Workspace workspace = write(
                "workspace/create",
                "workspace",
                0,
                new CoreRpcContracts.WorkspaceCreatePayload(
                        "重试测试", Files.createDirectories(directory.resolve("workspace"))),
                Workspace.class);
        thread = write(
                "thread/create",
                "thread",
                0,
                new CoreRpcContracts.ThreadCreatePayload(
                        workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "重试"),
                ConversationThread.class);
        role = ProviderRoleRpcFixtures.install(
                session,
                components,
                new ProviderRoleRpcFixtures.Installation(
                        "replay-provider",
                        "replay-model",
                        "replay-role",
                        new PermissionProfileRef("standard", 1),
                        Set.of(),
                        new TurnBudget(4000, 1000, 0, 0, Duration.ofSeconds(30))));
        payload = new CoreRpcContracts.TurnStartPayload(thread.id(), ExecutionOverrides.empty(), "只执行一次");
        command = new WriteCommand("turn-start", 0, components.json().encode(payload));
    }

    @AfterEach
    void closeServer() throws Exception {
        model.finish.countDown();
        components.close();
    }

    @Test
    void 已完成Turn在Role归档后仍按原始请求幂等恢复() throws Exception {
        assertFrozenRetryAfter(Revocation.ARCHIVE_ROLE);
    }

    @Test
    void 已完成Turn在Role禁用后仍按原始请求幂等恢复() throws Exception {
        assertFrozenRetryAfter(Revocation.DISABLE_ROLE);
    }

    @Test
    void 已完成Turn在Provider归档后仍按原始请求幂等恢复() throws Exception {
        assertFrozenRetryAfter(Revocation.ARCHIVE_PROVIDER);
    }

    private void assertFrozenRetryAfter(Revocation revocation) throws Exception {
        CoreRpcContracts.TurnStartResult first = start();
        assertEquals(TurnStatus.COMPLETED, awaitCompleted(first.turn().id()).status());
        CoreRpcContracts.ItemListResult originalItems = items();
        revoke(revocation);
        int capabilityReads = model.capabilityReads.get();

        assertEquals(first, start());
        assertEquals(originalItems, items());
        assertEquals(1, model.invocations.get());
        assertEquals(capabilityReads, model.capabilityReads.get());
        assertEquals(
                ProtocolErrorCode.IDEMPOTENCY_CONFLICT,
                invoke(
                                "turn/start",
                                new WriteCommand(
                                        command.idempotencyKey(),
                                        0,
                                        components
                                                .json()
                                                .encode(new CoreRpcContracts.TurnStartPayload(
                                                        thread.id(), payload.execution(), "不同任务"))))
                        .error()
                        .orElseThrow()
                        .code());
        assertEquals(
                ProtocolErrorCode.IDEMPOTENCY_CONFLICT,
                invoke("turn/start", new WriteCommand(command.idempotencyKey(), 1, command.payload()))
                        .error()
                        .orElseThrow()
                        .code());
        assertEquals(
                ProtocolErrorCode.IDEMPOTENCY_CONFLICT,
                invoke("turn/start", new WriteCommand("workspace", 0, command.payload()))
                        .error()
                        .orElseThrow()
                        .code());
    }

    @Test
    void 活动Turn重试不因Provider撤销重新绑定或提前终结执行() throws Exception {
        model.blocking = true;
        CoreRpcContracts.TurnStartResult first = start();
        assertTrue(model.started.await(5, TimeUnit.SECONDS));
        revoke(Revocation.ARCHIVE_PROVIDER);
        model.rejectCapabilities = true;
        int capabilityReads = model.capabilityReads.get();

        assertEquals(first, start());
        assertEquals(TurnStatus.RUNNING, read(first.turn().id()).status());
        assertEquals(1, model.invocations.get());
        assertEquals(capabilityReads, model.capabilityReads.get());
        model.finish.countDown();
        assertEquals(TurnStatus.COMPLETED, awaitCompleted(first.turn().id()).status());
    }

    @Test
    void 首次提交后派发失败的重试从冻结快照恢复排队Turn() throws Exception {
        model.failCapabilityAt = model.capabilityReads.get() + 2;
        assertTrue(invoke("turn/start", command).error().isPresent());
        CoreCommandService core = new CoreCommandService(
                new H2Database(directory.resolve("data-v6")), components.json(), Clock.systemUTC());
        List<AgentTurn> queued = core.listRecoverableTurns();
        assertEquals(1, queued.size());
        assertEquals(TurnStatus.QUEUED, queued.getFirst().status());
        assertEquals(0, model.invocations.get());

        CoreRpcContracts.TurnStartResult recovered = start();
        assertEquals(queued.getFirst(), recovered.turn());
        assertEquals(queued.getFirst().resolvedConfig(), recovered.configuration());
        assertEquals(TurnStatus.COMPLETED, awaitCompleted(recovered.turn().id()).status());
        assertEquals(1, model.invocations.get());
        assertEquals(recovered, start());
        assertEquals(1, model.invocations.get());
    }

    private void revoke(Revocation revocation) {
        switch (revocation) {
            case ARCHIVE_ROLE ->
                write(
                        "agent/role/archive",
                        "archive-role",
                        role.revision(),
                        new AgentRoleRpcContracts.ArchivePayload(role.id()),
                        AgentRole.class);
            case DISABLE_ROLE -> {
                AgentRole current =
                        decode(invoke("agent/role/read", new AgentRoleRpcContracts.ReadPayload(role)), AgentRole.class);
                write(
                        "agent/role/update",
                        "disable-role",
                        role.revision(),
                        new AgentRoleRpcContracts.UpdatePayload(role.id(), current.spec(), RoleLifecycle.DISABLED),
                        AgentRole.class);
            }
            case ARCHIVE_PROVIDER ->
                write(
                        "provider/archive",
                        "archive-provider",
                        1,
                        new ProviderRpcContracts.ProviderArchivePayload("replay-provider"),
                        ProviderEndpoint.class);
        }
    }

    private CoreRpcContracts.TurnStartResult start() {
        return decode(invoke("turn/start", command), CoreRpcContracts.TurnStartResult.class);
    }

    private AgentTurn awaitCompleted(TurnId turnId) throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            AgentTurn current = read(turnId);
            if (current.status() != TurnStatus.QUEUED && current.status() != TurnStatus.RUNNING) {
                return current;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Turn 未在限定时间内完成");
    }

    private AgentTurn read(TurnId turnId) {
        return decode(invoke("turn/read", new CoreRpcContracts.TurnQuery(turnId)), AgentTurn.class);
    }

    private CoreRpcContracts.ItemListResult items() {
        return decode(
                invoke("item/list", new CoreRpcContracts.ItemList(thread.id(), 0, 100)),
                CoreRpcContracts.ItemListResult.class);
    }

    private <T> T write(String method, String key, long revision, Object value, Class<T> type) {
        return decode(
                invoke(method, new WriteCommand(key, revision, components.json().encode(value))), type);
    }

    private JsonRpcResponse invoke(String method, Object value) {
        return session.handle(
                new JsonRpcRequest(new RpcId(method), method, components.json().encode(value)));
    }

    private <T> T decode(JsonRpcResponse response, Class<T> type) {
        return components
                .json()
                .decode(
                        response.result()
                                .orElseThrow(() ->
                                        new AssertionError(response.error().orElseThrow())),
                        type);
    }

    private enum Revocation {
        ARCHIVE_ROLE,
        DISABLE_ROLE,
        ARCHIVE_PROVIDER
    }

    private static final class RecordingModel implements ModelGateway {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger capabilityReads = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finish = new CountDownLatch(1);
        private volatile boolean blocking;
        private volatile boolean rejectCapabilities;
        private int failCapabilityAt = -1;

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (capabilityReads.incrementAndGet() == failCapabilityAt || rejectCapabilities) {
                throw new IllegalStateException("测试注入：模型绑定暂不可用");
            }
            return new ModelCapabilities(true, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
                throws Exception {
            invocations.incrementAndGet();
            started.countDown();
            while (blocking && !finish.await(10, TimeUnit.MILLISECONDS)) {
                cancellation.throwIfCancelled();
            }
            return new ModelInvocationResult(
                    "已完成",
                    List.of(),
                    new ModelUsage(20, 4, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }
    }
}
