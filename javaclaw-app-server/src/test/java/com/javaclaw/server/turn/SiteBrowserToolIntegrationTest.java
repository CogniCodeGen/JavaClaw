package com.javaclaw.server.turn;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.FilePermission;
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
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.IsolatedServicePort;
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
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.BuiltinManagementFixtures;
import com.javaclaw.server.ProviderRoleRpcFixtures;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteBrowserToolIntegrationTest {
    private static final String PROFILE_ID = "browser-test";
    private static final String AGENT_ROLE_ID = "browser-agent";
    private static final String PROVIDER_ID = "browser-provider";
    private static final String SNAPSHOT_TOOL = "site_snapshot";
    private static final URI PAGE = URI.create("https://docs.example.com/start");

    @TempDir
    Path temporaryDirectory;

    @Test
    void siteSnapshotRechecksRevisionNetworkPermissionAndWorkerBoundary() throws Exception {
        SnapshotService service = new SnapshotService();
        SnapshotModel model = new SnapshotModel();
        try (AppServerBootstrap.Components components = AppServerBootstrap.create(
                temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), model, enabled -> {}, service)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace workspace = createWorkspace(session, components);
            ConversationThread thread = createThread(session, components, workspace);
            installExecutionSettings(session, components, ApprovalRequirement.NONE);
            createSite(session, components, workspace, thread);

            AgentTurn accepted = startTurn(session, components, thread);
            AgentTurn terminal = awaitTerminal(session, components, accepted.id());

            assertEquals(TurnStatus.COMPLETED, terminal.status());
            assertEquals(1, service.invocations.get());
            assertEquals(new ExtensionId(BuiltinExtensionIds.SITE), service.caller);
            assertEquals(SiteContracts.BROWSER_SNAPSHOT_SERVICE, service.serviceId);
            assertEquals(
                    Set.of(URI.create("https://docs.example.com")),
                    service.task.site().allowedOrigins());
            assertTrue(model.toolOutputs.stream().anyMatch(text -> text.contains("isolated worker content")));
        }
    }

    @Test
    void approvedSnapshotResumesWaitingTurnAndInvokesWorkerOnce() throws Exception {
        SnapshotService service = new SnapshotService();
        SnapshotModel model = new SnapshotModel();
        try (AppServerBootstrap.Components components = AppServerBootstrap.create(
                temporaryDirectory.resolve("approved/data-v6"), Clock.systemUTC(), model, enabled -> {}, service)) {
            Fixture fixture = prepare(components, ApprovalRequirement.RISKY);
            AgentTurn accepted = startTurn(fixture.session(), components, fixture.thread());

            ApprovalRecord pending = awaitPendingApproval(fixture.session(), components, accepted.id());
            AgentTurn waiting = readTurn(fixture.session(), components, accepted.id());
            assertEquals(TurnStatus.WAITING, waiting.status());
            assertEquals(ApprovalState.PENDING, pending.state());
            assertEquals(64, pending.request().requestDigest().length());
            assertFalse(pending.request().explanation().contains(PAGE.toString()));
            assertEquals(0, service.invocations.get());

            ApprovalRecord approved =
                    resolve(fixture.session(), components, pending, ApprovalDecision.APPROVED, "允许读取公开文档");
            AgentTurn terminal = awaitTerminal(fixture.session(), components, accepted.id());

            assertEquals(ApprovalState.APPROVED, approved.state());
            assertEquals(2, approved.revision());
            assertEquals(TurnStatus.COMPLETED, terminal.status());
            assertEquals(1, service.invocations.get());
            assertEquals(2, countItems(fixture.session(), components, fixture.thread(), CoreSchemas.APPROVAL));
        }
    }

    @Test
    void deniedSnapshotReturnsGovernedToolFailureWithoutInvokingWorker() throws Exception {
        SnapshotService service = new SnapshotService();
        SnapshotModel model = new SnapshotModel();
        try (AppServerBootstrap.Components components = AppServerBootstrap.create(
                temporaryDirectory.resolve("denied/data-v6"), Clock.systemUTC(), model, enabled -> {}, service)) {
            Fixture fixture = prepare(components, ApprovalRequirement.RISKY);
            AgentTurn accepted = startTurn(fixture.session(), components, fixture.thread());
            ApprovalRecord pending = awaitPendingApproval(fixture.session(), components, accepted.id());

            ApprovalRecord denied = resolve(fixture.session(), components, pending, ApprovalDecision.DENIED, "不允许访问网络");
            AgentTurn terminal = awaitTerminal(fixture.session(), components, accepted.id());

            assertEquals(ApprovalState.DENIED, denied.state());
            assertEquals(TurnStatus.COMPLETED, terminal.status());
            assertEquals(0, service.invocations.get());
            assertTrue(model.toolOutputs.stream().anyMatch(text -> text.contains("TOOL_APPROVAL_DENIED")));
        }
    }

    @Test
    void cancellingWaitingTurnCancelsApprovalAndNeverInvokesWorker() throws Exception {
        SnapshotService service = new SnapshotService();
        SnapshotModel model = new SnapshotModel();
        try (AppServerBootstrap.Components components = AppServerBootstrap.create(
                temporaryDirectory.resolve("cancelled/data-v6"), Clock.systemUTC(), model, enabled -> {}, service)) {
            Fixture fixture = prepare(components, ApprovalRequirement.RISKY);
            AgentTurn accepted = startTurn(fixture.session(), components, fixture.thread());
            ApprovalRecord pending = awaitPendingApproval(fixture.session(), components, accepted.id());
            AgentTurn waiting = readTurn(fixture.session(), components, accepted.id());

            CoreRpcContracts.TurnCancelPayload payload =
                    new CoreRpcContracts.TurnCancelPayload(accepted.id(), "用户取消等待中的工具调用");
            decode(
                    fixture.session()
                            .handle(request(
                                    components,
                                    "cancel",
                                    "turn/cancel",
                                    new WriteCommand(
                                            "cancel-key",
                                            waiting.revision(),
                                            components.json().encode(payload)))),
                    components,
                    AgentTurn.class);
            AgentTurn terminal = awaitTerminal(fixture.session(), components, accepted.id());
            ApprovalRecord cancelled = listApprovals(fixture.session(), components, accepted.id(), true)
                    .getFirst();

            assertEquals(pending.request().id(), cancelled.request().id());
            assertEquals(ApprovalState.CANCELLED, cancelled.state());
            assertEquals(TurnStatus.CANCELLED, terminal.status());
            assertEquals(0, service.invocations.get());
        }
    }

    private Fixture prepare(AppServerBootstrap.Components components, ApprovalRequirement approval) {
        AppServerSession session = components.newSession();
        initialize(session, components);
        Workspace workspace = createWorkspace(session, components);
        ConversationThread thread = createThread(session, components, workspace);
        installExecutionSettings(session, components, approval);
        createSite(session, components, workspace, thread);
        return new Fixture(session, thread);
    }

    private void installExecutionSettings(
            AppServerSession session, AppServerBootstrap.Components components, ApprovalRequirement approval) {
        PermissionProfileRpcContracts.ClonePayload clone =
                new PermissionProfileRpcContracts.ClonePayload(new PermissionProfileRef("standard", 1), PROFILE_ID);
        decode(
                session.handle(request(
                        components,
                        "profile-clone",
                        "permissionProfile/clone",
                        new WriteCommand(
                                "profile-clone-key", 0, components.json().encode(clone)))),
                components,
                PermissionProfile.class);
        PermissionProfile profile = new PermissionProfile(
                PROFILE_ID,
                2,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of("docs.example.com"), Set.of(443), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(Set.of("tool_search", SNAPSHOT_TOOL), ToolRisk.NETWORK, approval),
                new ResourceLimits(128L * 1024 * 1024, 4L * 1024 * 1024, 1, 16));
        PermissionProfileRpcContracts.UpdatePayload payload = new PermissionProfileRpcContracts.UpdatePayload(profile);
        decode(
                session.handle(request(
                        components,
                        "profile",
                        "permissionProfile/update",
                        new WriteCommand("profile-key", 1, components.json().encode(payload)))),
                components,
                PermissionProfile.class);
        ProviderRoleRpcFixtures.install(
                session,
                components,
                new ProviderRoleRpcFixtures.Installation(
                        PROVIDER_ID,
                        SnapshotModel.ID,
                        AGENT_ROLE_ID,
                        new PermissionProfileRef(PROFILE_ID, 2),
                        Set.of(CoreTools.SEARCH_NAME, SNAPSHOT_TOOL),
                        new TurnBudget(4_000, 1_000, 3, 0, Duration.ofSeconds(30))));
    }

    private void createSite(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            ConversationThread thread) {
        SiteContracts.Site site = new SiteContracts.Site(
                "docs",
                1,
                1,
                "JavaClaw Docs",
                URI.create("https://docs.example.com"),
                Set.of(URI.create("https://docs.example.com")),
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                true,
                Instant.now());
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.SITE,
                workspace.id(),
                Optional.of(thread.id()),
                Optional.empty(),
                "site/create",
                components.json().encode(BuiltinManagementFixtures.site(site)));
        decode(
                session.handle(request(
                        components,
                        "site-create",
                        "extension/command",
                        new WriteCommand("site-create-key", 0, components.json().encode(call)))),
                components,
                ExtensionRpcContracts.CallResult.class);
    }

    private AgentTurn startTurn(
            AppServerSession session, AppServerBootstrap.Components components, ConversationThread thread) {
        CoreRpcContracts.TurnStartPayload payload = new CoreRpcContracts.TurnStartPayload(
                thread.id(), TurnV6Fixtures.selection(new AgentRoleRef(AGENT_ROLE_ID, 1)), "读取站点页面");
        return decode(
                        session.handle(request(
                                components,
                                "turn",
                                "turn/start",
                                new WriteCommand(
                                        "turn-key", 0, components.json().encode(payload)))),
                        components,
                        CoreRpcContracts.TurnStartResult.class)
                .turn();
    }

    private Workspace createWorkspace(AppServerSession session, AppServerBootstrap.Components components) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("Browser", temporaryDirectory.resolve("workspace"));
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
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, "Browser");
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
        AgentTurn last = null;
        for (int attempt = 0; attempt < 300; attempt++) {
            last = decode(
                    session.handle(request(
                            components, "read-" + attempt, "turn/read", new CoreRpcContracts.TurnQuery(turnId))),
                    components,
                    AgentTurn.class);
            if (last.status() == TurnStatus.COMPLETED
                    || last.status() == TurnStatus.CANCELLED
                    || last.status() == TurnStatus.FAILED) {
                return last;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Turn did not reach a terminal state: " + last);
    }

    private ApprovalRecord awaitPendingApproval(
            AppServerSession session, AppServerBootstrap.Components components, TurnId turnId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            List<ApprovalRecord> approvals = listApprovals(session, components, turnId, false);
            if (!approvals.isEmpty()) {
                return approvals.getFirst();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Approval did not enter pending state");
    }

    private List<ApprovalRecord> listApprovals(
            AppServerSession session,
            AppServerBootstrap.Components components,
            TurnId turnId,
            boolean includeResolved) {
        CoreRpcContracts.ApprovalListPayload payload =
                new CoreRpcContracts.ApprovalListPayload(Optional.of(turnId), includeResolved);
        return decode(
                        session.handle(request(components, "approval-list", "approval/list", payload)),
                        components,
                        CoreRpcContracts.ApprovalListResult.class)
                .approvals();
    }

    private ApprovalRecord resolve(
            AppServerSession session,
            AppServerBootstrap.Components components,
            ApprovalRecord approval,
            ApprovalDecision decision,
            String reason) {
        CoreRpcContracts.ApprovalResolvePayload payload =
                new CoreRpcContracts.ApprovalResolvePayload(approval.request().id(), decision, reason);
        return decode(
                session.handle(request(
                        components,
                        "approval-resolve",
                        "approval/resolve",
                        new WriteCommand(
                                "approval-key-" + decision,
                                approval.revision(),
                                components.json().encode(payload)))),
                components,
                ApprovalRecord.class);
    }

    private AgentTurn readTurn(AppServerSession session, AppServerBootstrap.Components components, TurnId turnId) {
        return decode(
                session.handle(request(components, "turn-read", "turn/read", new CoreRpcContracts.TurnQuery(turnId))),
                components,
                AgentTurn.class);
    }

    private long countItems(
            AppServerSession session,
            AppServerBootstrap.Components components,
            ConversationThread thread,
            String schemaId) {
        CoreRpcContracts.ItemListResult result = decode(
                session.handle(request(
                        components, "item-list", "item/list", new CoreRpcContracts.ItemList(thread.id(), 0, 100))),
                components,
                CoreRpcContracts.ItemListResult.class);
        return result.items().stream()
                .filter(item -> schemaId.equals(item.schemaId()))
                .count();
    }

    private void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("browser-tool-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(session.handle(request(components, "init", "initialize/session", params))
                .result()
                .isPresent());
    }

    private JsonRpcRequest request(AppServerBootstrap.Components components, String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private <T> T decode(JsonRpcResponse response, AppServerBootstrap.Components components, Class<T> type) {
        CanonicalPayload payload = response.result()
                .orElseThrow(() -> new AssertionError(response.error().orElseThrow()));
        return components.json().decode(payload, type);
    }

    private record Fixture(AppServerSession session, ConversationThread thread) {}

    private static final class SnapshotService implements IsolatedServicePort {
        private final AtomicInteger invocations = new AtomicInteger();
        private final com.javaclaw.protocol.CanonicalJson json = new com.javaclaw.protocol.CanonicalJson();
        private volatile ExtensionId caller;
        private volatile String serviceId;
        private volatile SiteContracts.SnapshotTask task;

        @Override
        public CanonicalPayload invoke(IsolatedServiceInvocation invocation) {
            invocation.cancellation().throwIfCancelled();
            if (SiteContracts.BROWSER_INVALIDATE_SERVICE.equals(invocation.serviceId())) {
                json.decode(invocation.request(), SiteContracts.AuthorityInvalidation.class);
                return json.encode(java.util.Map.of());
            }
            caller = invocation.caller();
            serviceId = invocation.serviceId();
            task = json.decode(invocation.request(), SiteContracts.SnapshotTask.class);
            invocations.incrementAndGet();
            return json.encode(
                    new SiteContracts.PageSnapshot(task.uri(), "JavaClaw", "isolated worker content", Instant.now()));
        }
    }

    private static final class SnapshotModel implements ModelGateway {
        private static final String ID = "browser-model";
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<String> toolOutputs = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (!new ProviderRef(PROVIDER_ID, 1, ID).routeKey().equals(modelId)) {
                throw new IllegalArgumentException("unknown model endpoint");
            }
            return new ModelCapabilities(false, true, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            invocation.messages().stream()
                    .filter(message -> message.role() == com.javaclaw.api.MessageRole.TOOL)
                    .map(ModelMessage::text)
                    .forEach(toolOutputs::add);
            return switch (invocations.incrementAndGet()) {
                case 1 -> result(new ModelToolCall("search", CoreTools.search().identity(), searchArguments()));
                case 2 ->
                    result(new ModelToolCall(
                            "snapshot",
                            new ToolIdentity(BuiltinExtensionIds.SITE, SNAPSHOT_TOOL, 1),
                            snapshotArguments()));
                default -> complete();
            };
        }

        private ModelInvocationResult result(ModelToolCall call) {
            return new ModelInvocationResult(
                    "",
                    List.of(call),
                    new ModelUsage(10, 2, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.TOOL_CALLS);
        }

        private ModelInvocationResult complete() {
            return new ModelInvocationResult(
                    "已读取页面",
                    List.of(),
                    new ModelUsage(10, 2, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }

        private CanonicalPayload searchArguments() {
            return new CanonicalPayload("{\"limit\":10,\"query\":\"snapshot\"}");
        }

        private CanonicalPayload snapshotArguments() {
            return new CanonicalPayload("{\"expectedAuthorityRevision\":1,\"expectedRevision\":1,"
                    + "\"maxCharacters\":1000,\"siteId\":\"docs\","
                    + "\"uri\":\"https://docs.example.com/start\"}");
        }
    }
}
