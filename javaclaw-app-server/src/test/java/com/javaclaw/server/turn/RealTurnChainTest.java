package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelStreamEvent;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.BuiltinManagementFixtures;
import com.javaclaw.server.ProviderRoleRpcFixtures;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealTurnChainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistedTurnRunsThroughHarnessAndRetryDoesNotInvokeModelTwice() throws Exception {
        RecordingModel model = new RecordingModel();
        try (AppServerBootstrap.Components components =
                AppServerBootstrap.create(temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), model)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace workspace = createWorkspace(session, components);
            ConversationThread thread = createThread(session, components, workspace);
            AgentRoleRef role = installRole(session, components, RecordingModel.ID);
            CoreRpcContracts.TurnStartPayload payload = turnPayload(thread, role);
            WriteCommand command =
                    new WriteCommand("real-turn-key", 0, components.json().encode(payload));

            AgentTurn accepted = decode(
                            session.handle(request(components, "start-1", "turn/start", command)),
                            components,
                            CoreRpcContracts.TurnStartResult.class)
                    .turn();
            AgentTurn completed = awaitTerminal(session, components, accepted.id());
            List<ItemEnvelope> items = items(session, components, thread);
            AgentTurn retried = decode(
                            session.handle(request(components, "start-2", "turn/start", command)),
                            components,
                            CoreRpcContracts.TurnStartResult.class)
                    .turn();

            assertEquals(TurnStatus.COMPLETED, completed.status());
            assertEquals(accepted.id(), retried.id());
            assertEquals(1, model.invocations.get());
            assertEquals(List.of("请用一句话回答"), model.userMessages);
            assertEquals(List.of(MessageRole.USER, MessageRole.ASSISTANT), messageRoles(components, items));
            assertEquals("已完成", lastMessage(components, items).text());
        }
    }

    @Test
    void cancellationIsDurableAndConvergesRunningTurnToCancelled() throws Exception {
        BlockingModel model = new BlockingModel();
        try (AppServerBootstrap.Components components =
                AppServerBootstrap.create(temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), model)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace workspace = createWorkspace(session, components);
            ConversationThread thread = createThread(session, components, workspace);
            AgentRoleRef role = installRole(session, components, BlockingModel.ID);
            CoreRpcContracts.TurnStartPayload payload =
                    new CoreRpcContracts.TurnStartPayload(thread.id(), TurnV6Fixtures.selection(role), "等待取消");
            AgentTurn accepted = decode(
                            session.handle(request(
                                    components,
                                    "start",
                                    "turn/start",
                                    new WriteCommand(
                                            "cancel-turn", 0, components.json().encode(payload)))),
                            components,
                            CoreRpcContracts.TurnStartResult.class)
                    .turn();
            assertTrue(model.started.await(2, TimeUnit.SECONDS));
            LifecycleLeaseRepository leases = new LifecycleLeaseRepository(
                    new H2Database(temporaryDirectory.resolve("data-v6")), Clock.systemUTC());
            assertEquals(1, leases.activeCount());
            AgentTurn running = readTurn(session, components, accepted.id(), "running");
            CoreRpcContracts.TurnCancelPayload cancelPayload =
                    new CoreRpcContracts.TurnCancelPayload(accepted.id(), "用户停止");
            AgentTurn requested = decode(
                    session.handle(request(
                            components,
                            "cancel",
                            "turn/cancel",
                            new WriteCommand(
                                    "cancel-key",
                                    running.revision(),
                                    components.json().encode(cancelPayload)))),
                    components,
                    AgentTurn.class);
            AgentTurn terminal = awaitTerminal(session, components, accepted.id());

            assertEquals(TurnStatus.RUNNING, running.status());
            assertEquals(running.revision() + 1, requested.revision());
            assertEquals(TurnStatus.CANCELLED, terminal.status());
            awaitNoLease(leases);
        }
    }

    @Test
    void planExecutionStartReturnsImmediatelyAndRecoversTheSameFrozenJob() throws Exception {
        RecordingModel model = new RecordingModel();
        try (AppServerBootstrap.Components components =
                AppServerBootstrap.create(temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), model)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace workspace = createWorkspace(session, components);
            ConversationThread parent = createThread(session, components, workspace);
            AgentRoleRef role = installRole(session, components, RecordingModel.ID);
            PlanContracts.Definition plan = savePlan(
                    session,
                    components,
                    workspace,
                    parent,
                    "definition/create",
                    0,
                    BuiltinManagementFixtures.plan(plan()));
            OrchestrationContracts.StartRequest run = new OrchestrationContracts.StartRequest(
                    plan.id(), TurnV6Fixtures.selection(role), executionBudget());
            WriteCommand command = new WriteCommand(
                    "plan-run",
                    1,
                    components.json().encode(extensionCall(components, workspace, parent, "execution/start", run)));

            ExtensionExecutionReceipt first = runPlan(session, components, "plan-run-1", command);
            PlanContracts.Definition revised =
                    savePlan(session, components, workspace, parent, "definition/update", 1, revisedPlan(plan));
            ExtensionExecutionReceipt retried = runPlan(session, components, "plan-run-2", command);
            PlanContracts.Definition current = readPlan(session, components, workspace, parent, plan.id());

            assertEquals(first, retried);
            assertEquals(plan.revision(), first.definitionRevision());
            assertEquals(revised, current);
        }
    }

    private void awaitNoLease(LifecycleLeaseRepository leases) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (leases.activeCount() == 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Turn lifecycle lease was not released");
    }

    private PlanContracts.Definition plan() {
        return new PlanContracts.Definition(
                "release-5",
                1,
                "发布 5.0",
                "完成全部验收",
                "core",
                List.of("rollback"),
                List.of(),
                List.of(
                        new PlanContracts.Step("build", "构建", "build", "构建通过", List.of()),
                        new PlanContracts.Step("verify", "验证", "verify", "验证通过", List.of("build"))),
                Instant.parse("2026-08-31T12:00:00Z"));
    }

    private PlanContracts.ManagementSaveRequest revisedPlan(PlanContracts.Definition plan) {
        PlanContracts.ManagementSaveRequest current = BuiltinManagementFixtures.plan(plan);
        return new PlanContracts.ManagementSaveRequest(
                current.id(),
                current.title() + "（修订）",
                current.objective(),
                current.scope(),
                current.risks(),
                current.openQuestions(),
                current.steps());
    }

    private OrchestrationContracts.ExecutionBudget executionBudget() {
        return new OrchestrationContracts.ExecutionBudget(3, 10_000, 10_000, 20);
    }

    private ExtensionRpcContracts.CallResult extensionCommand(
            AppServerSession session,
            AppServerBootstrap.Components components,
            String key,
            long expectedRevision,
            ExtensionRpcContracts.CallPayload call) {
        return decode(
                session.handle(request(
                        components,
                        key,
                        "extension/command",
                        new WriteCommand(
                                key, expectedRevision, components.json().encode(call)))),
                components,
                ExtensionRpcContracts.CallResult.class);
    }

    private PlanContracts.Definition savePlan(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            ConversationThread parent,
            String operation,
            long expectedRevision,
            PlanContracts.ManagementSaveRequest request) {
        String key = "plan-save-" + expectedRevision;
        ExtensionRpcContracts.CallResult result = extensionCommand(
                session,
                components,
                key,
                expectedRevision,
                extensionCall(components, workspace, parent, operation, request));
        return components.json().decode(result.payload(), PlanContracts.Definition.class);
    }

    private ExtensionExecutionReceipt runPlan(
            AppServerSession session,
            AppServerBootstrap.Components components,
            String requestId,
            WriteCommand command) {
        ExtensionRpcContracts.CallResult result = decode(
                session.handle(request(components, requestId, "extension/command", command)),
                components,
                ExtensionRpcContracts.CallResult.class);
        return components.json().decode(result.payload(), ExtensionExecutionReceipt.class);
    }

    private PlanContracts.Definition readPlan(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            ConversationThread parent,
            String planId) {
        ExtensionRpcContracts.CallPayload call =
                extensionCall(components, workspace, parent, "read", new DocumentContracts.Key(planId));
        ExtensionRpcContracts.CallResult result = decode(
                session.handle(request(components, "plan-read", "extension/query", call)),
                components,
                ExtensionRpcContracts.CallResult.class);
        return components.json().decode(result.payload(), PlanContracts.Definition.class);
    }

    private ExtensionRpcContracts.CallPayload extensionCall(
            AppServerBootstrap.Components components,
            Workspace workspace,
            ConversationThread parent,
            String operation,
            Object payload) {
        return new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.PLAN,
                workspace.id(),
                Optional.of(parent.id()),
                Optional.empty(),
                operation,
                components.json().encode(payload));
    }

    private Workspace createWorkspace(AppServerSession session, AppServerBootstrap.Components components) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("真实链测试", temporaryDirectory.resolve("workspace"));
        return decode(
                session.handle(request(
                        components,
                        "workspace",
                        "workspace/create",
                        new WriteCommand("workspace-key", 0, components.json().encode(payload)))),
                components,
                Workspace.class);
    }

    private ConversationThread createThread(
            AppServerSession session, AppServerBootstrap.Components components, Workspace workspace) {
        CoreRpcContracts.ThreadCreatePayload payload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, "真实 Turn");
        return decode(
                session.handle(request(
                        components,
                        "thread",
                        "thread/create",
                        new WriteCommand("thread-key", 0, components.json().encode(payload)))),
                components,
                ConversationThread.class);
    }

    private CoreRpcContracts.TurnStartPayload turnPayload(ConversationThread thread, AgentRoleRef role) {
        return new CoreRpcContracts.TurnStartPayload(thread.id(), TurnV6Fixtures.selection(role), "请用一句话回答");
    }

    private AgentRoleRef installRole(AppServerSession session, AppServerBootstrap.Components components, String model) {
        return ProviderRoleRpcFixtures.install(
                session,
                components,
                new ProviderRoleRpcFixtures.Installation(
                        providerId(model),
                        model,
                        model + "-profile",
                        new PermissionProfileRef("standard", 1),
                        Set.of(),
                        new TurnBudget(4_000, 1_000, 2, 0, Duration.ofSeconds(30))));
    }

    private static String providerId(String model) {
        return model + "-provider";
    }

    private static String route(String model) {
        return new ProviderRef(providerId(model), 1, model).routeKey();
    }

    private AgentTurn awaitTerminal(AppServerSession session, AppServerBootstrap.Components components, TurnId turnId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            AgentTurn turn = readTurn(session, components, turnId, "read-" + attempt);
            if (turn.status() == TurnStatus.COMPLETED
                    || turn.status() == TurnStatus.CANCELLED
                    || turn.status() == TurnStatus.FAILED) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Turn did not reach a terminal state");
    }

    private AgentTurn readTurn(
            AppServerSession session, AppServerBootstrap.Components components, TurnId turnId, String requestId) {
        return decode(
                session.handle(request(components, requestId, "turn/read", new CoreRpcContracts.TurnQuery(turnId))),
                components,
                AgentTurn.class);
    }

    private List<ItemEnvelope> items(
            AppServerSession session, AppServerBootstrap.Components components, ConversationThread thread) {
        CoreRpcContracts.ItemListResult result = decode(
                session.handle(
                        request(components, "items", "item/list", new CoreRpcContracts.ItemList(thread.id(), 0, 100))),
                components,
                CoreRpcContracts.ItemListResult.class);
        return result.items();
    }

    private List<MessageRole> messageRoles(AppServerBootstrap.Components components, List<ItemEnvelope> items) {
        return items.stream()
                .filter(item -> CoreSchemas.MESSAGE.equals(item.schemaId()))
                .map(item -> components.json().decode(item.payload(), CorePayloads.Message.class))
                .map(CorePayloads.Message::role)
                .toList();
    }

    private CorePayloads.Message lastMessage(AppServerBootstrap.Components components, List<ItemEnvelope> items) {
        return items.stream()
                .filter(item -> CoreSchemas.MESSAGE.equals(item.schemaId()))
                .map(item -> components.json().decode(item.payload(), CorePayloads.Message.class))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("real-chain-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(session.handle(request(components, "init", "initialize/session", params))
                .result()
                .isPresent());
    }

    private JsonRpcRequest request(AppServerBootstrap.Components components, String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private <T> T decode(JsonRpcResponse response, AppServerBootstrap.Components components, Class<T> type) {
        return components
                .json()
                .decode(
                        response.result()
                                .orElseThrow(() ->
                                        new AssertionError(response.error().orElseThrow())),
                        type);
    }

    private static final class RecordingModel implements ModelGateway {
        private static final String ID = "recording-model";

        private final AtomicInteger invocations = new AtomicInteger();
        private final List<String> userMessages = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (!route(ID).equals(modelId)) {
                throw new IllegalArgumentException("unknown model endpoint");
            }
            return new ModelCapabilities(true, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation)
                throws InterruptedException {
            invocations.incrementAndGet();
            invocation.messages().stream()
                    .filter(message -> message.role() == MessageRole.USER)
                    .map(com.javaclaw.runtime.ModelMessage::text)
                    .forEach(userMessages::add);
            events.publish(turnId, new ModelStreamEvent.TextDelta("已完成"), cancellation);
            ModelUsage usage = new ModelUsage(20, 4, 0, 0);
            events.publish(turnId, new ModelStreamEvent.Usage(usage), cancellation);
            return new ModelInvocationResult(
                    "已完成", List.of(), usage, Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        }
    }

    private static final class BlockingModel implements ModelGateway {
        private static final String ID = "blocking-model";

        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (!route(ID).equals(modelId)) {
                throw new IllegalArgumentException("unknown model endpoint");
            }
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation)
                throws InterruptedException {
            started.countDown();
            while (true) {
                cancellation.throwIfCancelled();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                if (Thread.interrupted()) {
                    throw new InterruptedException("blocking model interrupted");
                }
            }
        }
    }
}
