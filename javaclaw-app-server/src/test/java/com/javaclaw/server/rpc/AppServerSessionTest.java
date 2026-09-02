package com.javaclaw.server.rpc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RolloutManifest;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolVersion;
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
import com.javaclaw.server.BuiltinManagementFixtures;
import com.javaclaw.server.ProviderProfileRpcFixtures;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppServerSessionTest {
    @TempDir
    Path temporaryDirectory;

    private RecordingModel model;
    private AppServerBootstrap.Components components;
    private AppServerSession session;

    @BeforeEach
    void createSession() {
        model = new RecordingModel();
        components = AppServerBootstrap.create(temporaryDirectory.resolve("data-v5"), Clock.systemUTC(), model);
        session = components.newSession();
    }

    @AfterEach
    void closeComponents() throws Exception {
        components.close();
    }

    @Test
    void initializeIsRequiredAndVersionOneIsRejected() {
        JsonRpcResponse beforeInitialize = session.handle(request("1", "workspace/list", new Empty()));
        assertEquals(
                ProtocolErrorCode.INVALID_REQUEST,
                beforeInitialize.error().orElseThrow().code());

        AppServerSession oldSession = components.newSession();
        InitializeParams oldParams =
                new InitializeParams(1, new ClientInfo("test", "1"), new CapabilityAdvertisement(Set.of(), Set.of()));
        JsonRpcResponse oldVersion = oldSession.handle(request("2", "initialize/session", oldParams));
        assertEquals(
                ProtocolErrorCode.UNSUPPORTED_PROTOCOL,
                oldVersion.error().orElseThrow().code());

        initialize();
        assertTrue(session.handle(request("3", "workspace/list", new Empty()))
                .result()
                .isPresent());
        assertEquals(
                ProtocolErrorCode.INVALID_REQUEST,
                session.handle(request("4", "initialize/session", currentInitialization()))
                        .error()
                        .orElseThrow()
                        .code());
        assertEquals(
                ProtocolErrorCode.METHOD_NOT_FOUND,
                session.handle(request("5", "extension/event", new Empty()))
                        .error()
                        .orElseThrow()
                        .code());
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                session.handle(request("6", "workspace/create", new Empty()))
                        .error()
                        .orElseThrow()
                        .code());
    }

    @Test
    void sessionMapsAllFailureKindsAndBoundsUntrustedMessages() {
        RpcRouter.Builder routes = RpcRouter.builder();
        routes.register("workspace/list", ignored -> {
            throw new SecurityException((String) null);
        });
        routes.register("thread/read", ignored -> {
            throw new IllegalArgumentException(" ");
        });
        routes.register("turn/read", ignored -> {
            throw new IllegalArgumentException("x".repeat(600));
        });
        routes.register("item/list", ignored -> {
            throw PersistenceException.invalidRequest("invalid");
        });
        routes.register("attachment/read", ignored -> {
            throw PersistenceException.revisionConflict("revision");
        });
        routes.register("profile/list", ignored -> {
            throw PersistenceException.idempotencyConflict("idempotency");
        });
        routes.register("provider/list", ignored -> {
            throw new PersistenceException("database failed");
        });
        routes.register("diagnostics/read", ignored -> {
            throw new IOException("unexpected");
        });
        AppServerSession failures = customSession(routes.build());
        initialize(failures);

        assertError(failures, "workspace/list", ProtocolErrorCode.PERMISSION_DENIED, "request failed");
        assertError(failures, "thread/read", ProtocolErrorCode.INVALID_PARAMS, "request failed");
        JsonRpcResponse bounded = failures.handle(request("bounded", "turn/read", new Empty()));
        assertEquals(500, bounded.error().orElseThrow().message().length());
        assertError(failures, "item/list", ProtocolErrorCode.INVALID_PARAMS, "invalid");
        assertError(failures, "attachment/read", ProtocolErrorCode.REVISION_CONFLICT, "revision");
        assertError(failures, "profile/list", ProtocolErrorCode.IDEMPOTENCY_CONFLICT, "idempotency");
        assertError(failures, "provider/list", ProtocolErrorCode.INTERNAL_ERROR, "internal persistence error");
        assertError(failures, "diagnostics/read", ProtocolErrorCode.INTERNAL_ERROR, "internal server error");
    }

    @Test
    void serveRoutesRequestsAndRejectsClientNotificationsAndResponses() throws Exception {
        initialize();
        TestRpcConnection requests = new TestRpcConnection(request("serve", "workspace/list", new Empty()));
        session.serve(requests);
        assertEquals(1, requests.sent().size());

        TestRpcConnection notification = new TestRpcConnection(new JsonRpcNotification("extension/event", json("{}")));
        assertThrows(IOException.class, () -> session.serve(notification));
        TestRpcConnection response = new TestRpcConnection(JsonRpcResponse.success(new RpcId("response"), json("{}")));
        assertThrows(IOException.class, () -> session.serve(response));
        assertThrows(IOException.class, () -> session.serve(TestRpcConnection.failing()));
        assertThrows(NullPointerException.class, () -> session.serve(null));
    }

    @Test
    void coreChatChainUsesDurableIdempotencyAndSharedApiTypes() throws Exception {
        initialize();
        CoreRpcContracts.WorkspaceCreatePayload workspacePayload =
                new CoreRpcContracts.WorkspaceCreatePayload("RPC 工作区", temporaryDirectory.resolve("workspace"));
        WriteCommand workspaceCommand = command("workspace-key", workspacePayload);
        Workspace workspace =
                decodeSuccess(session.handle(request("w1", "workspace/create", workspaceCommand)), Workspace.class);
        Workspace retried =
                decodeSuccess(session.handle(request("w2", "workspace/create", workspaceCommand)), Workspace.class);
        assertEquals(workspace.id(), retried.id());

        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, "对话");
        ConversationThread thread = decodeSuccess(
                session.handle(request("t1", "thread/create", command("thread-key", threadPayload))),
                ConversationThread.class);
        com.javaclaw.api.TurnBudget budget = new com.javaclaw.api.TurnBudget(4_000, 1_000, 4, 2, Duration.ofMinutes(1));
        AgentProfileRef profile = ProviderProfileRpcFixtures.install(
                session,
                components,
                new ProviderProfileRpcFixtures.Installation(
                        "test-provider",
                        "test-model",
                        "test-profile",
                        new PermissionProfileRef("standard", 1),
                        Set.of(),
                        budget));
        CoreRpcContracts.TurnStartPayload turnPayload =
                new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.of(profile), "请开始");
        AgentTurn turn = decodeSuccess(
                session.handle(request("r1", "turn/start", command("turn-key", turnPayload))), AgentTurn.class);
        CoreRpcContracts.ItemListResult items = decodeSuccess(
                session.handle(request("i1", "item/list", new CoreRpcContracts.ItemList(thread.id(), 0, 100))),
                CoreRpcContracts.ItemListResult.class);

        assertEquals(1, turn.revision());
        assertEquals(profile, turn.profile());
        assertEquals(new ProviderRef("test-provider", 1, "test-model"), turn.provider());
        assertEquals(new PermissionProfileRef("standard", 1), turn.permissionProfile());
        assertEquals(64, turn.promptManifestDigest().length());
        assertEquals(64, turn.toolCatalogDigest().length());
        assertEquals(
                "请开始",
                components
                        .json()
                        .decode(items.items().getFirst().payload(), com.javaclaw.api.CorePayloads.Message.class)
                        .text());
        assertTrue(model.invoked.await(3, TimeUnit.SECONDS));
        assertEquals(TurnStatus.COMPLETED, awaitTerminal(turn.id()).status());
        assertEquals(List.of(turn.id()), model.turns);
    }

    @Test
    void reusingIdempotencyKeyForDifferentPayloadIsRejected() {
        initialize();
        WriteCommand first = command(
                "same-key", new CoreRpcContracts.WorkspaceCreatePayload("一", temporaryDirectory.resolve("one")));
        WriteCommand second = command(
                "same-key", new CoreRpcContracts.WorkspaceCreatePayload("二", temporaryDirectory.resolve("two")));

        assertTrue(
                session.handle(request("1", "workspace/create", first)).result().isPresent());
        JsonRpcResponse conflict = session.handle(request("2", "workspace/create", second));
        assertEquals(
                ProtocolErrorCode.IDEMPOTENCY_CONFLICT,
                conflict.error().orElseThrow().code());
    }

    @Test
    void rolloutCommandUsesThreadRevisionAndRecoversExistingFile() {
        initialize();
        Workspace workspace = decodeSuccess(
                session.handle(request(
                        "workspace",
                        "workspace/create",
                        command(
                                "rollout-workspace",
                                new CoreRpcContracts.WorkspaceCreatePayload(
                                        "导出", temporaryDirectory.resolve("rollout-workspace"))))),
                Workspace.class);
        ConversationThread thread = decodeSuccess(
                session.handle(request(
                        "thread",
                        "thread/create",
                        command(
                                "rollout-thread",
                                new CoreRpcContracts.ThreadCreatePayload(
                                        workspace.id(),
                                        Optional.empty(),
                                        com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                                        "空快照")))),
                ConversationThread.class);
        Path output = temporaryDirectory.resolve("exports/thread.jsonl");
        CoreRpcContracts.RolloutExportPayload payload = new CoreRpcContracts.RolloutExportPayload(thread.id(), output);
        WriteCommand export = new WriteCommand(
                "rollout-key", thread.revision(), components.json().encode(payload));

        RolloutManifest first = decodeSuccess(
                session.handle(request("export-1", "thread/rollout/export", export)), RolloutManifest.class);
        RolloutManifest retried = decodeSuccess(
                session.handle(request("export-2", "thread/rollout/export", export)), RolloutManifest.class);

        assertTrue(Files.isRegularFile(output));
        assertEquals(thread.revision(), first.sourceRevision());
        assertEquals(0, first.itemCount());
        assertEquals(first, retried);
    }

    @Test
    void permissionProfilesAreVersionedAndUpdateIsIdempotent() {
        initialize();
        PermissionProfileRpcContracts.ListResult initial = decodeSuccess(
                session.handle(request("list-1", "permissionProfile/list", new Empty())),
                PermissionProfileRpcContracts.ListResult.class);
        PermissionProfile standard = initial.profiles().getFirst();
        WriteCommand clone = new WriteCommand(
                "profile-clone",
                0,
                components
                        .json()
                        .encode(new PermissionProfileRpcContracts.ClonePayload(
                                new PermissionProfileRef(standard.id(), standard.version()), "developer")));
        PermissionProfile first = decodeSuccess(
                session.handle(request("clone-1", "permissionProfile/clone", clone)), PermissionProfile.class);
        assertEquals(
                first,
                decodeSuccess(
                        session.handle(request("clone-2", "permissionProfile/clone", clone)), PermissionProfile.class));
        PermissionProfile second = new PermissionProfile(
                first.id(), 2, first.files(), first.network(), first.processes(), first.tools(), first.resources());
        WriteCommand update = new WriteCommand(
                "profile-update",
                first.version(),
                components.json().encode(new PermissionProfileRpcContracts.UpdatePayload(second)));

        PermissionProfile updated = decodeSuccess(
                session.handle(request("update-1", "permissionProfile/update", update)), PermissionProfile.class);
        PermissionProfile retried = decodeSuccess(
                session.handle(request("update-2", "permissionProfile/update", update)), PermissionProfile.class);
        PermissionProfileRpcContracts.ListResult latest = decodeSuccess(
                session.handle(request("list-2", "permissionProfile/list", new Empty())),
                PermissionProfileRpcContracts.ListResult.class);
        PermissionProfileRpcContracts.HistoryResult history = decodeSuccess(
                session.handle(request(
                        "history",
                        "permissionProfile/history",
                        new PermissionProfileRpcContracts.HistoryPayload("developer"))),
                PermissionProfileRpcContracts.HistoryResult.class);

        assertEquals(2, updated.version());
        assertEquals(updated, retried);
        assertEquals(List.of(first, updated), history.profiles());
        assertEquals(2, latest.profiles().size());
    }

    @Test
    void builtInExtensionsUseGenericRpcAndIndependentManagedSchema() {
        initialize();
        Workspace workspace = createExtensionWorkspace();
        ExtensionRpcContracts.ListResult catalog = decodeSuccess(
                session.handle(request("extension-list", "extension/list", new Empty())),
                ExtensionRpcContracts.ListResult.class);
        assertEquals(9, catalog.extensions().size());

        assertPlanManagementRoundTrip(workspace);
        assertPlanWorkspaceIsolation(workspace);
        assertPlanView();
    }

    private void assertPlanWorkspaceIsolation(Workspace source) {
        Workspace other = decodeSuccess(
                session.handle(request(
                        "other-workspace",
                        "workspace/create",
                        command(
                                "other-workspace-key",
                                new CoreRpcContracts.WorkspaceCreatePayload(
                                        "其他扩展", temporaryDirectory.resolve("other-extension-workspace"))))),
                Workspace.class);
        JsonRpcResponse hidden = session.handle(request(
                "other-plan-read",
                "extension/query",
                extensionCall(other, "read", components.json().encode(new DocumentContracts.Key("release-plan")))));
        ExtensionRpcContracts.CallResult page = decodeSuccess(
                session.handle(request(
                        "other-plan-list",
                        "extension/query",
                        extensionCall(
                                other, "list", components.json().encode(new DocumentContracts.PageRequest("", 20))))),
                ExtensionRpcContracts.CallResult.class);

        assertTrue(hidden.error().isPresent());
        assertTrue(components
                .json()
                .decode(page.payload(), DocumentContracts.Page.class)
                .documents()
                .isEmpty());
        assertTrue(!source.id().equals(other.id()));
    }

    private Workspace createExtensionWorkspace() {
        return decodeSuccess(
                session.handle(request(
                        "extension-workspace",
                        "workspace/create",
                        command(
                                "extension-workspace-key",
                                new CoreRpcContracts.WorkspaceCreatePayload(
                                        "扩展", temporaryDirectory.resolve("extension-workspace"))))),
                Workspace.class);
    }

    private void assertPlanManagementRoundTrip(Workspace workspace) {
        PlanContracts.Definition plan = new PlanContracts.Definition(
                "release-plan",
                1,
                "发布 5.0",
                "全部门禁通过",
                "core",
                List.of("rollback"),
                List.of(),
                List.of(new PlanContracts.Step("verify", "运行验证", "verify", "全部门禁通过", List.of())),
                Instant.parse("2026-08-31T12:00:00Z"));
        ExtensionRpcContracts.CallPayload create = extensionCall(
                workspace, "definition/create", components.json().encode(BuiltinManagementFixtures.plan(plan)));
        WriteCommand createCommand =
                new WriteCommand("plan-create", 0, components.json().encode(create));

        ExtensionRpcContracts.CallResult created = decodeSuccess(
                session.handle(request("plan-create-1", "extension/command", createCommand)),
                ExtensionRpcContracts.CallResult.class);
        ExtensionRpcContracts.CallResult retried = decodeSuccess(
                session.handle(request("plan-create-2", "extension/command", createCommand)),
                ExtensionRpcContracts.CallResult.class);
        ExtensionRpcContracts.CallResult read = decodeSuccess(
                session.handle(request(
                        "plan-read",
                        "extension/query",
                        extensionCall(
                                workspace, "read", components.json().encode(new DocumentContracts.Key(plan.id()))))),
                ExtensionRpcContracts.CallResult.class);
        ExtensionRpcContracts.CallResult page = decodeSuccess(
                session.handle(request(
                        "plan-list",
                        "extension/query",
                        extensionCall(
                                workspace,
                                "list",
                                components.json().encode(new DocumentContracts.PageRequest("", 20))))),
                ExtensionRpcContracts.CallResult.class);

        assertEquals(1, created.revision());
        assertEquals(created, retried);
        PlanContracts.Definition createdPlan =
                components.json().decode(created.payload(), PlanContracts.Definition.class);
        assertEquals(createdPlan, components.json().decode(read.payload(), PlanContracts.Definition.class));
        DocumentContracts.Page decodedPage = components.json().decode(page.payload(), DocumentContracts.Page.class);
        assertEquals(
                createdPlan,
                components.json().decode(decodedPage.documents().getFirst(), PlanContracts.Definition.class));
    }

    private void assertPlanView() {
        ExtensionRpcContracts.ViewListResult views = decodeSuccess(
                session.handle(request(
                        "plan-views",
                        "extension/view/list",
                        new ExtensionRpcContracts.ViewListPayload(Optional.of(BuiltinExtensionIds.PLAN)))),
                ExtensionRpcContracts.ViewListResult.class);
        assertEquals(3, views.views().size());
        assertEquals(
                Set.of("com.javaclaw.plan.management", "com.javaclaw.plan.executions", "com.javaclaw.plan.proposals"),
                views.views().stream()
                        .map(view -> components
                                .json()
                                .textField(view.schema(), "viewId")
                                .orElseThrow())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private ExtensionRpcContracts.CallPayload extensionCall(
            Workspace workspace, String operation, com.javaclaw.api.CanonicalPayload payload) {
        return new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.PLAN, workspace.id(), Optional.empty(), Optional.empty(), operation, payload);
    }

    private void initialize() {
        initialize(session);
    }

    private void initialize(AppServerSession target) {
        JsonRpcResponse response = target.handle(request("init", "initialize/session", currentInitialization()));
        assertTrue(response.result().isPresent());
    }

    private InitializeParams currentInitialization() {
        return new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("test-client", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
    }

    private AppServerSession customSession(RpcRouter router) {
        return new AppServerSession(
                components.negotiator(), router, components.json(), components.lifecycle(), components.events());
    }

    private void assertError(AppServerSession target, String method, int code, String message) {
        JsonRpcResponse response = target.handle(request("error-" + method, method, new Empty()));
        assertEquals(code, response.error().orElseThrow().code());
        assertEquals(message, response.error().orElseThrow().message());
    }

    private CanonicalPayload json(String value) {
        return components.json().parse(value);
    }

    private WriteCommand command(String key, Object payload) {
        return new WriteCommand(key, 0, components.json().encode(payload));
    }

    private JsonRpcRequest request(String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private <T> T decodeSuccess(JsonRpcResponse response, Class<T> type) {
        return components.json().decode(response.result().orElseThrow(), type);
    }

    private AgentTurn awaitTerminal(TurnId turnId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            AgentTurn current = decodeSuccess(
                    session.handle(request("turn-status", "turn/read", new CoreRpcContracts.TurnQuery(turnId))),
                    AgentTurn.class);
            if (current.status() == TurnStatus.COMPLETED
                    || current.status() == TurnStatus.CANCELLED
                    || current.status() == TurnStatus.FAILED) {
                return current;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Turn did not reach a terminal state");
    }

    private record Empty() {}

    private static final class RecordingModel implements ModelGateway {
        private final List<com.javaclaw.api.TurnId> turns = new CopyOnWriteArrayList<>();
        private final CountDownLatch invoked = new CountDownLatch(1);

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                com.javaclaw.api.TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            turns.add(turnId);
            invoked.countDown();
            return new ModelInvocationResult(
                    "完成", List.of(), ModelUsage.zero(), Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        }
    }
}
