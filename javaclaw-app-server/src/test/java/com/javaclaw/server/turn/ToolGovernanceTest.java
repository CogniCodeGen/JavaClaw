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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.ToolRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.BuiltinManagementFixtures;
import com.javaclaw.server.ProviderProfileRpcFixtures;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGovernanceTest {
    private static final String PROFILE_ID = "tool-test";
    private static final String AGENT_PROFILE_ID = "tool-agent";
    private static final String PROVIDER_ID = "tool-provider";
    private static final String PLAN_READ = "plan_read";

    @TempDir
    Path temporaryDirectory;

    @Test
    void searchRevealsOnlyFrozenAuthorizedToolBeforeExecution() throws Exception {
        ProgressiveModel model = new ProgressiveModel();
        try (AppServerBootstrap.Components components = create(model)) {
            Fixture fixture = fixture(components);
            installProfile(fixture.session(), components, 1, Set.of(CoreTools.SEARCH_NAME, PLAN_READ), model.id());
            createPlan(fixture, components);
            assertCatalogSearch(fixture, components);

            AgentTurn terminal = awaitTerminal(
                    fixture.session(),
                    components,
                    startTurn(fixture, components).id());
            List<ItemEnvelope> items = items(fixture, components);

            assertEquals(TurnStatus.COMPLETED, terminal.status());
            assertEquals(
                    List.of(
                            List.of(CoreTools.SEARCH_NAME),
                            List.of(PLAN_READ, CoreTools.SEARCH_NAME),
                            List.of(PLAN_READ, CoreTools.SEARCH_NAME)),
                    model.visibleTools);
            assertEquals(2, count(items, CoreSchemas.TOOL_CALL));
            assertEquals(2, count(items, CoreSchemas.TOOL_RESULT));
            assertTrue(model.toolOutputs.stream().anyMatch(output -> output.contains("release-5")));
        }
    }

    @Test
    void toolCannotExecuteBeforeSearchRevealsIt() throws Exception {
        DirectModel model = new DirectModel();
        try (AppServerBootstrap.Components components = create(model)) {
            Fixture fixture = fixture(components);
            installProfile(fixture.session(), components, 1, Set.of(CoreTools.SEARCH_NAME, PLAN_READ), model.id());

            AgentTurn terminal = awaitTerminal(
                    fixture.session(),
                    components,
                    startTurn(fixture, components).id());

            assertEquals(TurnStatus.FAILED, terminal.status());
            assertEquals(Optional.of("TOOL_NOT_DISCOVERED"), terminal.errorCode());
            assertEquals(0, count(items(fixture, components), CoreSchemas.TOOL_RESULT));
        }
    }

    @Test
    void latestPermissionRevocationBlocksToolFromFrozenCatalog() throws Exception {
        RevocationModel model = new RevocationModel();
        try (AppServerBootstrap.Components components = create(model)) {
            Fixture fixture = fixture(components);
            installProfile(fixture.session(), components, 1, Set.of(CoreTools.SEARCH_NAME, PLAN_READ), model.id());
            createPlan(fixture, components);
            AgentTurn accepted = startTurn(fixture, components);
            assertTrue(model.readyToCall.await(3, TimeUnit.SECONDS));

            AppServerSession administrator = components.newSession();
            initialize(administrator, components);
            installProfile(administrator, components, 2, Set.of(CoreTools.SEARCH_NAME), model.id());
            model.continueAfterRevocation.countDown();
            AgentTurn terminal = awaitTerminal(fixture.session(), components, accepted.id());

            assertEquals(TurnStatus.FAILED, terminal.status());
            assertEquals(Optional.of("TOOL_PERMISSION_REVOKED"), terminal.errorCode());
            assertEquals(List.of(PLAN_READ, CoreTools.SEARCH_NAME), model.secondInvocationTools);
            assertEquals(1, count(items(fixture, components), CoreSchemas.TOOL_RESULT));
        } finally {
            model.continueAfterRevocation.countDown();
        }
    }

    private AppServerBootstrap.Components create(ModelGateway model) {
        return AppServerBootstrap.create(temporaryDirectory.resolve("data-v5"), Clock.systemUTC(), model);
    }

    private Fixture fixture(AppServerBootstrap.Components components) {
        AppServerSession session = components.newSession();
        initialize(session, components);
        Workspace workspace = createWorkspace(session, components);
        ConversationThread thread = createThread(session, components, workspace);
        return new Fixture(session, workspace, thread);
    }

    private void assertCatalogSearch(Fixture fixture, AppServerBootstrap.Components components) {
        ToolRpcContracts.CatalogQuery query =
                new ToolRpcContracts.CatalogQuery(fixture.workspace().id(), PROFILE_ID, 2, "plan", 10);
        ToolRpcContracts.SearchResult result = decode(
                fixture.session().handle(request(components, "tool-search", "tool/search", query)),
                components,
                ToolRpcContracts.SearchResult.class);
        assertEquals(
                List.of(PLAN_READ),
                result.tools().stream().map(tool -> tool.identity().name()).toList());
    }

    private void installProfile(
            AppServerSession session,
            AppServerBootstrap.Components components,
            long version,
            Set<String> allowedTools,
            String modelId) {
        if (version == 1) {
            cloneStandardProfile(session, components);
        }
        long persistedVersion = version + 1;
        PermissionProfile profile = new PermissionProfile(
                PROFILE_ID,
                persistedVersion,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(allowedTools, ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(64L * 1024 * 1024, 4L * 1024 * 1024, 1, 16));
        PermissionProfileRpcContracts.UpdatePayload payload = new PermissionProfileRpcContracts.UpdatePayload(profile);
        decode(
                session.handle(request(
                        components,
                        "profile-" + version,
                        "permissionProfile/update",
                        new WriteCommand(
                                "profile-key-" + version,
                                persistedVersion - 1,
                                components.json().encode(payload)))),
                components,
                PermissionProfile.class);
        if (version == 1) {
            ProviderProfileRpcFixtures.install(
                    session,
                    components,
                    new ProviderProfileRpcFixtures.Installation(
                            PROVIDER_ID,
                            modelId,
                            AGENT_PROFILE_ID,
                            new PermissionProfileRef(PROFILE_ID, 2),
                            allowedTools,
                            new TurnBudget(4_000, 1_000, 3, 0, Duration.ofSeconds(30))));
        }
    }

    private void cloneStandardProfile(AppServerSession session, AppServerBootstrap.Components components) {
        PermissionProfileRpcContracts.ClonePayload payload =
                new PermissionProfileRpcContracts.ClonePayload(new PermissionProfileRef("standard", 1), PROFILE_ID);
        decode(
                session.handle(request(
                        components,
                        "profile-clone",
                        "permissionProfile/clone",
                        new WriteCommand(
                                "profile-clone-key", 0, components.json().encode(payload)))),
                components,
                PermissionProfile.class);
    }

    private void createPlan(Fixture fixture, AppServerBootstrap.Components components) {
        PlanContracts.Definition plan = new PlanContracts.Definition(
                "release-5",
                1,
                "发布 5.0",
                "完成验收",
                "core",
                List.of(),
                List.of(),
                List.of(new PlanContracts.Step("verify", "验证", "verify", "验收通过", List.of())),
                Instant.parse("2026-08-31T12:00:00Z"));
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.PLAN,
                fixture.workspace().id(),
                Optional.of(fixture.thread().id()),
                Optional.empty(),
                "definition/create",
                components.json().encode(BuiltinManagementFixtures.plan(plan)));
        decode(
                fixture.session()
                        .handle(request(
                                components,
                                "plan-create",
                                "extension/command",
                                new WriteCommand(
                                        "plan-create-key", 0, components.json().encode(call)))),
                components,
                ExtensionRpcContracts.CallResult.class);
    }

    private AgentTurn startTurn(Fixture fixture, AppServerBootstrap.Components components) {
        CoreRpcContracts.TurnStartPayload payload = new CoreRpcContracts.TurnStartPayload(
                fixture.thread().id(), Optional.of(new AgentProfileRef(AGENT_PROFILE_ID, 1)), "读取发布计划");
        return decode(
                fixture.session()
                        .handle(request(
                                components,
                                "turn-start",
                                "turn/start",
                                new WriteCommand(
                                        "turn-key", 0, components.json().encode(payload)))),
                components,
                AgentTurn.class);
    }

    private Workspace createWorkspace(AppServerSession session, AppServerBootstrap.Components components) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("工具治理", temporaryDirectory.resolve("workspace"));
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
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, "工具治理");
        return decode(
                session.handle(request(
                        components,
                        "thread",
                        "thread/create",
                        new WriteCommand("thread-key", 0, components.json().encode(payload)))),
                components,
                ConversationThread.class);
    }

    private AgentTurn awaitTerminal(AppServerSession session, AppServerBootstrap.Components components, TurnId turnId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            AgentTurn turn = decode(
                    session.handle(request(
                            components, "turn-read-" + attempt, "turn/read", new CoreRpcContracts.TurnQuery(turnId))),
                    components,
                    AgentTurn.class);
            if (turn.status() == TurnStatus.COMPLETED || turn.status() == TurnStatus.FAILED) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Turn did not reach a terminal state");
    }

    private List<ItemEnvelope> items(Fixture fixture, AppServerBootstrap.Components components) {
        CoreRpcContracts.ItemList query =
                new CoreRpcContracts.ItemList(fixture.thread().id(), 0, 100);
        return decode(
                        fixture.session().handle(request(components, "items", "item/list", query)),
                        components,
                        CoreRpcContracts.ItemListResult.class)
                .items();
    }

    private void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("tool-governance-test", "5.0"),
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

    private static long count(List<ItemEnvelope> items, String schemaId) {
        return items.stream().filter(item -> schemaId.equals(item.schemaId())).count();
    }

    private record Fixture(AppServerSession session, Workspace workspace, ConversationThread thread) {}

    private abstract static class ToolModel implements ModelGateway {
        abstract String id();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (!new ProviderRef(PROVIDER_ID, 1, id()).routeKey().equals(modelId)) {
                throw new IllegalArgumentException("unknown model endpoint");
            }
            return new ModelCapabilities(false, true, false, false, false, false, false);
        }

        ModelInvocationResult result(List<ModelToolCall> calls, String text) {
            return new ModelInvocationResult(
                    text,
                    calls,
                    new ModelUsage(10, 2, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    calls.isEmpty() ? ModelFinishReason.COMPLETE : ModelFinishReason.TOOL_CALLS);
        }

        ModelToolCall searchCall() {
            return new ModelToolCall(
                    "search-call",
                    CoreTools.search().identity(),
                    new CanonicalPayload("{\"limit\":10,\"query\":\"plan\"}"));
        }

        ModelToolCall readCall() {
            return new ModelToolCall(
                    "read-call",
                    new ToolIdentity(BuiltinExtensionIds.PLAN, PLAN_READ, 1),
                    new CanonicalPayload("{\"id\":\"release-5\"}"));
        }
    }

    private static final class ProgressiveModel extends ToolModel {
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<List<String>> visibleTools = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> toolOutputs = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        String id() {
            return "progressive-model";
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            int number = invocations.incrementAndGet();
            visibleTools.add(invocation.tools().stream()
                    .map(tool -> tool.identity().name())
                    .toList());
            invocation.messages().stream()
                    .filter(message -> message.role() == MessageRole.TOOL)
                    .map(com.javaclaw.runtime.ModelMessage::text)
                    .forEach(toolOutputs::add);
            return switch (number) {
                case 1 -> result(List.of(searchCall()), "");
                case 2 -> result(List.of(readCall()), "");
                default -> result(List.of(), "已读取计划");
            };
        }
    }

    private static final class DirectModel extends ToolModel {
        @Override
        String id() {
            return "direct-model";
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            return result(List.of(readCall()), "");
        }
    }

    private static final class RevocationModel extends ToolModel {
        private final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch readyToCall = new CountDownLatch(1);
        private final CountDownLatch continueAfterRevocation = new CountDownLatch(1);
        private volatile List<String> secondInvocationTools = List.of();

        @Override
        String id() {
            return "revocation-model";
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation)
                throws InterruptedException {
            int number = invocations.incrementAndGet();
            if (number == 1) {
                return result(List.of(searchCall()), "");
            }
            secondInvocationTools = invocation.tools().stream()
                    .map(tool -> tool.identity().name())
                    .toList();
            readyToCall.countDown();
            if (!continueAfterRevocation.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("permission update did not complete");
            }
            return result(List.of(readCall()), "");
        }
    }
}
