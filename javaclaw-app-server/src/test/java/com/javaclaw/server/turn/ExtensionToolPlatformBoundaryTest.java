package com.javaclaw.server.turn;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ToolRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.TurnStartRequest;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionToolPlatformBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-09-02T02:00:00Z");
    private static final ToolIdentity TOOL_ID = new ToolIdentity("com.javaclaw.test", "workflow_write", 1);

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private H2Database database;
    private CoreCommandService core;
    private PermissionProfileService profiles;
    private ApprovalService approvals;
    private ManagedWorktreeService worktrees;
    private ExtensionCatalogRepository catalog;
    private RecordingHost host;
    private PermissionProfile permission;
    private AgentTurn turn;

    @BeforeEach
    void initializeDataV6() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        profiles = new PermissionProfileService(database, json, clock);
        profiles.installStandardProfile();
        permission = installToolPermission();
        approvals = new ApprovalService(database, json, clock);
        worktrees = new ManagedWorktreeService(
                database, new AttachmentService(database, json, clock), json, clock, unavailableSandbox());
        catalog = new ExtensionCatalogRepository(database, json, clock);
        host = new RecordingHost(descriptor());
        turn = createTurn(clock);
    }

    @AfterEach
    void closeApprovalWaiters() throws Exception {
        approvals.close();
    }

    @Test
    void frozenCatalog只初始暴露Core搜索并从冻结快照渐进展开() throws Exception {
        ExtensionToolPlatform platform = platform(Clock.fixed(NOW, ZoneOffset.UTC));
        ToolCatalogSnapshot snapshot =
                platform.freeze(turn.id(), turnWorkspace(), permission, new CancellationSource());
        ToolCatalogSnapshot withoutWorkspace = platform.freeze(turn.id(), permission, new CancellationSource());

        assertEquals(
                List.of(CoreTools.SEARCH_NAME, TOOL_ID.name()),
                snapshot.tools().stream().map(tool -> tool.identity().name()).toList());
        assertEquals(snapshot.tools(), withoutWorkspace.tools());
        assertEquals(
                List.of(CoreTools.SEARCH_NAME),
                platform.initialTools(snapshot).stream()
                        .map(tool -> tool.identity().name())
                        .toList());
        assertEquals(
                List.of(TOOL_ID.name()),
                platform.search(permission, "WORKFLOW", 1).stream()
                        .map(tool -> tool.identity().name())
                        .toList());

        ToolCallRequest request = request(
                CoreTools.search(),
                snapshot,
                json.encode(new ToolRpcContracts.SearchArguments("write", 10)),
                "search-call");
        ToolExecutionOutcome outcome =
                platform.execute(request, CoreTools.search(), snapshot, permission, new CancellationSource());
        ToolRpcContracts.SearchResult result =
                json.decode(outcome.result().output(), ToolRpcContracts.SearchResult.class);

        assertTrue(outcome.result().success());
        assertEquals(
                List.of(TOOL_ID),
                result.tools().stream().map(ToolDescriptor::identity).toList());
        assertEquals(result.tools(), outcome.revealedTools());
        assertFalse(outcome.result().receipt().isPresent());
    }

    @Test
    void extension写工具生成EffectReceipt且实时收窄阻止执行() throws Exception {
        ExtensionToolPlatform platform = platform(Clock.fixed(NOW, ZoneOffset.UTC));
        ToolCatalogSnapshot snapshot =
                platform.freeze(turn.id(), turnWorkspace(), permission, new CancellationSource());
        ToolDescriptor descriptor = snapshot.require(TOOL_ID);
        ToolCallRequest request = request(descriptor, snapshot, json.parse("{\"value\":1}"), "write-call");

        ToolExecutionOutcome outcome =
                platform.execute(request, descriptor, snapshot, permission, new CancellationSource());
        PermissionProfile revoked = permissionWith(
                new ToolPermission(Set.of(CoreTools.SEARCH_NAME), ToolRisk.WORKSPACE_WRITE, ApprovalRequirement.NONE),
                permission.network());

        assertTrue(outcome.result().success());
        assertEquals(json.parse("{\"stored\":true}"), outcome.result().output());
        assertEquals("write-effect", outcome.result().receipt().orElseThrow().idempotencyKey());
        assertEquals(1, host.executions.get());
        TurnFailureException failure = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(request, descriptor, snapshot, revoked, new CancellationSource()));
        assertEquals("TOOL_PERMISSION_REVOKED", failure.code());
        assertEquals(1, host.executions.get());
    }

    @Test
    void frozen目录在工具消失或非文件权限漂移时FailClosed() {
        ExtensionToolPlatform platform = platform(Clock.fixed(NOW, ZoneOffset.UTC));
        ToolCatalogSnapshot snapshot =
                platform.freeze(turn.id(), turnWorkspace(), permission, new CancellationSource());
        ToolCatalogSnapshot rebound =
                platform.bindFrozen(turn.id(), turnWorkspace(), snapshot, permission, new CancellationSource());
        assertEquals(snapshot.tools(), rebound.tools());
        assertEquals(snapshot.catalogRevision(), rebound.catalogRevision());

        host.tools = List.of();
        TurnFailureException removed = assertThrows(
                TurnFailureException.class,
                () -> platform.bindFrozen(turn.id(), turnWorkspace(), snapshot, permission, new CancellationSource()));
        assertEquals("TOOL_CATALOG_CHANGED", removed.code());

        host.tools = List.of(descriptor());
        PermissionProfile networkDrift =
                permissionWith(permission.tools(), new NetworkPermission(Set.of("example.com"), Set.of(443), false));
        TurnFailureException drifted = assertThrows(
                TurnFailureException.class,
                () -> platform.bindFrozen(
                        turn.id(), turnWorkspace(), snapshot, networkDrift, new CancellationSource()));
        assertEquals("TOOL_CATALOG_CHANGED", drifted.code());
    }

    @Test
    void 过期Turn预算在发起审批前拒绝且不会创建悬挂审批() {
        Clock afterBudget = Clock.fixed(NOW.plus(Duration.ofMinutes(2)), ZoneOffset.UTC);
        ExtensionToolPlatform platform = platform(afterBudget);
        PermissionProfile approvalEveryCall = permissionWith(
                new ToolPermission(Set.of(TOOL_ID.name()), ToolRisk.WORKSPACE_WRITE, ApprovalRequirement.EVERY_CALL),
                permission.network());
        ToolCatalogSnapshot snapshot =
                platform.freeze(turn.id(), turnWorkspace(), approvalEveryCall, new CancellationSource());
        ToolDescriptor descriptor = snapshot.require(TOOL_ID);
        ToolCallRequest request = request(descriptor, snapshot, json.parse("{}"), "late-call");

        TurnFailureException failure = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(request, descriptor, snapshot, approvalEveryCall, new CancellationSource()));

        assertEquals("BUDGET_EXCEEDED", failure.code());
        assertTrue(approvals.list(Optional.of(turn.id()), true).isEmpty());
        assertEquals(0, host.executions.get());
    }

    @Test
    void 请求与冻结Turn或Revision不一致时不会进入扩展Host() {
        ExtensionToolPlatform platform = platform(Clock.fixed(NOW, ZoneOffset.UTC));
        ToolCatalogSnapshot snapshot =
                platform.freeze(turn.id(), turnWorkspace(), permission, new CancellationSource());
        ToolDescriptor descriptor = snapshot.require(TOOL_ID);
        ToolCallRequest wrongRevision = new ToolCallRequest(
                turn.id(), "wrong-revision", TOOL_ID, json.parse("{}"), "wrong-effect", snapshot.catalogRevision() + 1);

        TurnFailureException failure = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(wrongRevision, descriptor, snapshot, permission, new CancellationSource()));

        assertEquals("TOOL_CATALOG_CHANGED", failure.code());
        assertEquals(0, host.executions.get());
    }

    @Test
    void Schedule工具要求唯一授权并持久消费且同一调用不能重放() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UnattendedExecutionScope scope =
                new UnattendedExecutionScope(turnWorkspace(), "nightly", 4, "occurrence-authorized");
        AgentTurn scheduled = createScopedTurn(clock, scope, "authorized");
        ExtensionToolPlatform platform = platform(clock);
        ToolCatalogSnapshot snapshot =
                platform.freeze(scheduled.id(), scope.workspaceId(), permission, new CancellationSource());
        ToolDescriptor descriptor = snapshot.require(TOOL_ID);
        ToolCallRequest request =
                request(scheduled, descriptor, snapshot, json.parse("{\"value\":1}"), "scheduled-call");

        TurnFailureException denied = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(request, descriptor, snapshot, permission, new CancellationSource()));
        assertEquals("UNATTENDED_GRANT_REQUIRED", denied.code());
        assertEquals(0, host.executions.get());

        UnattendedToolGrantService grants = new UnattendedToolGrantService(database, json, clock);
        createGrant(grants, scope, descriptor, snapshot, request.arguments(), 2, "grant-authorized");
        ToolExecutionOutcome outcome =
                platform.execute(request, descriptor, snapshot, permission, new CancellationSource());

        assertTrue(outcome.result().success());
        assertEquals(1, grants.listLatest(scope.workspaceId()).getFirst().consumedUses());
        TurnFailureException replay = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(request, descriptor, snapshot, permission, new CancellationSource()));
        assertEquals("UNATTENDED_GRANT_REQUIRED", replay.code());
        assertEquals(1, host.executions.get());
        assertEquals(
                scope,
                new CoreCommandService(database, json, clock)
                        .unattendedExecutionScope(scheduled.id())
                        .orElseThrow());
    }

    @Test
    void Schedule工具异常记录UnknownOutcome并消费额度且禁止自动重试() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UnattendedExecutionScope scope =
                new UnattendedExecutionScope(turnWorkspace(), "nightly", 4, "occurrence-unknown");
        AgentTurn scheduled = createScopedTurn(clock, scope, "unknown");
        ExtensionToolPlatform platform = platform(clock);
        ToolCatalogSnapshot snapshot =
                platform.freeze(scheduled.id(), scope.workspaceId(), permission, new CancellationSource());
        ToolDescriptor descriptor = snapshot.require(TOOL_ID);
        ToolCallRequest request = request(scheduled, descriptor, snapshot, json.parse("{\"value\":1}"), "unknown-call");
        UnattendedToolGrantService grants = new UnattendedToolGrantService(database, json, clock);
        createGrant(grants, scope, descriptor, snapshot, request.arguments(), 2, "grant-unknown");
        host.failure = new IllegalStateException("external result is unknown");

        TurnFailureException unknown = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(request, descriptor, snapshot, permission, new CancellationSource()));
        assertEquals("UNKNOWN_OUTCOME", unknown.code());
        assertEquals(1, grants.listLatest(scope.workspaceId()).getFirst().consumedUses());

        host.failure = null;
        TurnFailureException replay = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(request, descriptor, snapshot, permission, new CancellationSource()));
        assertEquals("UNATTENDED_GRANT_REQUIRED", replay.code());
        assertEquals(1, host.executions.get());
    }

    @Test
    void Schedule授权不能绕过实时工具撤权且拒绝时不消费额度() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UnattendedExecutionScope scope =
                new UnattendedExecutionScope(turnWorkspace(), "nightly", 4, "occurrence-revoked");
        AgentTurn scheduled = createScopedTurn(clock, scope, "revoked");
        ExtensionToolPlatform platform = platform(clock);
        ToolCatalogSnapshot snapshot =
                platform.freeze(scheduled.id(), scope.workspaceId(), permission, new CancellationSource());
        ToolDescriptor descriptor = snapshot.require(TOOL_ID);
        ToolCallRequest request = request(scheduled, descriptor, snapshot, json.parse("{\"value\":1}"), "revoked-call");
        UnattendedToolGrantService grants = new UnattendedToolGrantService(database, json, clock);
        createGrant(grants, scope, descriptor, snapshot, request.arguments(), 2, "grant-revoked");
        PermissionProfile revoked = permissionWith(
                new ToolPermission(Set.of(CoreTools.SEARCH_NAME), ToolRisk.WORKSPACE_WRITE, ApprovalRequirement.NONE),
                permission.network());

        TurnFailureException failure = assertThrows(
                TurnFailureException.class,
                () -> platform.execute(request, descriptor, snapshot, revoked, new CancellationSource()));

        assertEquals("TOOL_PERMISSION_REVOKED", failure.code());
        assertEquals(0, grants.listLatest(scope.workspaceId()).getFirst().consumedUses());
        assertEquals(0, host.executions.get());
    }

    private ExtensionToolPlatform platform(Clock clock) {
        return new ExtensionToolPlatform(new ExtensionToolPlatform.Dependencies(
                host,
                core,
                approvals,
                profiles,
                worktrees,
                catalog,
                json,
                clock,
                new UnattendedToolGrantService(database, json, clock),
                Optional.empty()));
    }

    private PermissionProfile installToolPermission() {
        PermissionProfile cloned = profiles.cloneProfile(
                identity("permissionProfile/clone", "clone-tool-profile", Map.of()),
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                "tool-profile");
        PermissionProfile updated = new PermissionProfile(
                cloned.id(),
                2,
                cloned.files(),
                cloned.network(),
                cloned.processes(),
                new ToolPermission(
                        Set.of(CoreTools.SEARCH_NAME, TOOL_ID.name()),
                        ToolRisk.WORKSPACE_WRITE,
                        ApprovalRequirement.NONE),
                cloned.resources());
        return profiles.update(identity("permissionProfile/update", "update-tool-profile", 1, updated), updated);
    }

    private AgentTurn createTurn(Clock clock) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        CoreRpcContracts.WorkspaceCreatePayload workspacePayload =
                new CoreRpcContracts.WorkspaceCreatePayload("Tools", root);
        Workspace workspace = core.createWorkspace(
                identity("workspace/create", "workspace", workspacePayload),
                workspacePayload.name(),
                workspacePayload.root());
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Tools");
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread", threadPayload),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Tools");
        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.USER, "execute", List.of(), Optional.empty());
        ToolCatalogSnapshot initial =
                new ToolCatalogSnapshot(TurnId.random(), 1, List.of(), permission, clock.instant());
        TurnStartRequest request = new TurnStartRequest(
                thread.id(),
                com.javaclaw.server.TurnContractFixtures.configuration(
                        new com.javaclaw.server.TurnContractFixtures.Selection(
                                budget(),
                                new com.javaclaw.api.AgentRoleRef("tool-agent", 1),
                                new ProviderRef("provider", 1, "model"),
                                new PermissionProfileRef(permission.id(), permission.version())),
                        json.parse("{\"prompt\":\"test\"}"),
                        initial),
                root,
                json.parse("{\"prompt\":\"test\"}"),
                initial,
                message,
                Optional.empty());
        return core.startTurn(identity("turn/start", "turn", request), request);
    }

    private AgentTurn createScopedTurn(Clock clock, UnattendedExecutionScope scope, String suffix) throws Exception {
        Workspace workspace = core.findWorkspace(scope.workspaceId()).orElseThrow();
        ConversationThread thread = core.createThread(
                identity("thread/create", "scheduled-thread-" + suffix, scope),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Scheduled " + suffix);
        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.USER, "execute", List.of(), Optional.empty());
        ToolCatalogSnapshot initial =
                new ToolCatalogSnapshot(TurnId.random(), 1, List.of(), permission, clock.instant());
        TurnStartRequest request = new TurnStartRequest(
                thread.id(),
                com.javaclaw.server.TurnContractFixtures.configuration(
                        new com.javaclaw.server.TurnContractFixtures.Selection(
                                budget(),
                                new com.javaclaw.api.AgentRoleRef("tool-agent", 1),
                                new ProviderRef("provider", 1, "model"),
                                new PermissionProfileRef(permission.id(), permission.version())),
                        json.parse("{\"prompt\":\"scheduled\"}"),
                        initial),
                workspace.root(),
                json.parse("{\"prompt\":\"scheduled\"}"),
                initial,
                message,
                Optional.of(scope));
        return core.startTurn(identity("turn/start", "scheduled-turn-" + suffix, request), request);
    }

    private void createGrant(
            UnattendedToolGrantService grants,
            UnattendedExecutionScope scope,
            ToolDescriptor descriptor,
            ToolCatalogSnapshot snapshot,
            com.javaclaw.api.CanonicalPayload arguments,
            int maximumUses,
            String key) {
        UnattendedToolGrantDraft draft = new UnattendedToolGrantDraft(
                scope.workspaceId(),
                scope.scheduleId(),
                scope.scheduleRevision(),
                descriptor.identity(),
                snapshot.catalogRevision(),
                descriptor.inputSchema().sha256(),
                arguments,
                Set.of(),
                maximumUses,
                Duration.ofHours(1));
        grants.create(identity("unattendedToolGrant/create", key, draft), draft);
    }

    private com.javaclaw.api.WorkspaceId turnWorkspace() {
        return core.workspaceForThread(turn.threadId()).id();
    }

    private ToolCallRequest request(
            ToolDescriptor descriptor,
            ToolCatalogSnapshot snapshot,
            com.javaclaw.api.CanonicalPayload arguments,
            String callId) {
        String effectKey = callId.equals("write-call") ? "write-effect" : callId;
        return new ToolCallRequest(
                turn.id(), callId, descriptor.identity(), arguments, effectKey, snapshot.catalogRevision());
    }

    private ToolCallRequest request(
            AgentTurn owner,
            ToolDescriptor descriptor,
            ToolCatalogSnapshot snapshot,
            com.javaclaw.api.CanonicalPayload arguments,
            String callId) {
        return new ToolCallRequest(
                owner.id(), callId, descriptor.identity(), arguments, callId + "-effect", snapshot.catalogRevision());
    }

    private PermissionProfile permissionWith(ToolPermission tools, NetworkPermission network) {
        return new PermissionProfile(
                permission.id(),
                permission.version(),
                permission.files(),
                network,
                permission.processes(),
                tools,
                permission.resources());
    }

    private ToolDescriptor descriptor() {
        return new ToolDescriptor(
                TOOL_ID,
                "写入 Workflow 结果",
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"),
                ToolRisk.WORKSPACE_WRITE,
                Set.of("workflow", "write"));
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return identity(method, key, 0, payload);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private static SandboxExecutor unavailableSandbox() {
        return (SandboxExecutor) Proxy.newProxyInstance(
                ExtensionToolPlatformBoundaryTest.class.getClassLoader(),
                new Class<?>[] {SandboxExecutor.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("sandbox should not be called: " + method.getName());
                });
    }

    private final class RecordingHost implements ExtensionHost {
        private final AtomicInteger executions = new AtomicInteger();
        private List<ToolDescriptor> tools;
        private RuntimeException failure;

        private RecordingHost(ToolDescriptor tool) {
            tools = List.of(tool);
        }

        @Override
        public List<com.javaclaw.protocol.ExtensionRpcContracts.Summary> list() {
            return List.of();
        }

        @Override
        public List<ToolDescriptor> tools() {
            return tools;
        }

        @Override
        public ExtensionResponse executeTool(
                ToolCallRequest request,
                ToolDescriptor frozenDescriptor,
                PermissionProfile callerPermissions,
                CancellationToken cancellation) {
            executions.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new ExtensionResponse(json.parse("{\"stored\":true}"), 1);
        }

        @Override
        public ExtensionResponse query(com.javaclaw.protocol.ExtensionRpcContracts.CallPayload call) {
            throw new AssertionError("query should not be called");
        }

        @Override
        public ExtensionResponse command(
                com.javaclaw.protocol.ExtensionRpcContracts.CallPayload call,
                String idempotencyKey,
                long expectedRevision) {
            throw new AssertionError("command should not be called");
        }

        @Override
        public com.javaclaw.extension.spi.ExtensionSchema schema(String extensionId, String schemaId) {
            throw new AssertionError("schema should not be called");
        }

        @Override
        public List<com.javaclaw.protocol.ExtensionRpcContracts.ViewDocument> views(Optional<String> extensionId) {
            return List.of();
        }

        @Override
        public void close() {}
    }
}
