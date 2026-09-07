package com.javaclaw.server.turn;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.BudgetAccount;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ToolCatalogPort;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.ChildTurnService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.persistence.LiveTurnBudgets;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProviderService;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真实 H2、共同预算账户及可控子 Harness 的协作测试环境。 */
abstract class AgentCollaborationTestFixture {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC);
    static final TurnBudget BUDGET = new TurnBudget(8000, 2000, 8, 3, Duration.ofMinutes(10));
    static final Set<String> TOOLS = Set.of(CollaborationTools.SPAWN, CollaborationTools.WAIT);

    @TempDir
    Path directory;

    final CanonicalJson json = new CanonicalJson();
    final CountDownLatch childStarted = new CountDownLatch(1);
    final CountDownLatch finishChild = new CountDownLatch(1);
    CoreCommandService core;
    AgentRoleService roles;
    ProviderService providers;
    ExecutionConfigurationService defaults;
    H2TurnJournal journal;
    HarnessTurnDispatcher dispatcher;
    LifecycleCoordinator lifecycle;
    TurnCommandFactory factory;
    AgentCollaborationService service;
    AgentTurn parent;
    BudgetAccount account;
    WorkspaceId workspace;
    boolean spawnCatalog = true;
    TurnPlatformServices services;
    ChildTurnService children;
    PermissionProfileService permissions;
    ManagedWorktreeService worktrees;

    @BeforeEach
    void createH2AndSharedHarnessBoundary() throws Exception {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, CLOCK);
        LiveTurnBudgets budgets = new LiveTurnBudgets();
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, CLOCK, budgets);
        permissions = new PermissionProfileService(database, json, CLOCK);
        installPermissions(permissions);
        providers = new ProviderService(database, ignored -> true, json, CLOCK);
        providers.create(
                identity("provider/create", "provider", 0, Map.of()),
                "provider",
                ProviderEndpointTestFixtures.chat("Test", ProviderAdapter.OPENAI_COMPATIBLE, "model"),
                ProviderLifecycle.ACTIVE);
        roles = new AgentRoleService(database, providers, json, CLOCK);
        defaults = new ExecutionConfigurationService(database, core, roles, json, CLOCK);
        TurnV6Fixtures.defaults(
                defaults,
                roles.requireLatest("default").ref(),
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef("collaboration", 2),
                BUDGET,
                TOOLS);
        worktrees = new ManagedWorktreeService(
                database, new AttachmentService(database, json, CLOCK), json, CLOCK, sandbox());
        services = new TurnPlatformServices(
                core,
                roles,
                defaults,
                permissions,
                new ProjectInstructionResolver(directory.resolve("instructions"), CLOCK),
                worktrees);
        FakeModel models = new FakeModel();
        ToolCatalogPort catalogs = catalog(database);
        factory = new TurnCommandFactory(services, models, catalogs, "平台指令", json);
        lifecycle = new LifecycleCoordinator(new LifecycleLeaseRepository(database, CLOCK), Duration.ofSeconds(1));
        dispatcher = new HarnessTurnDispatcher(
                services,
                new HarnessTurnDispatcher.RuntimeResources(models, this::runChild, journal, lifecycle, catalogs),
                "平台指令",
                CLOCK,
                json);
        children = new ChildTurnService(database, budgets, json, CLOCK);
        service = new AgentCollaborationService(services, children, dispatcher, json, CLOCK);
        var created = core.createWorkspace(
                identity("workspace/create", "workspace", 0, Map.of()),
                "Test",
                Files.createDirectories(directory.resolve("workspace")));
        workspace = created.id();
    }

    SandboxExecutor sandbox() {
        return new PlatformSandboxExecutor();
    }

    ToolCatalogPort catalog(H2Database database) {
        return new Catalog();
    }

    void installPermissions(PermissionProfileService permissions) {
        permissions.installStandardProfile();
        PermissionProfile copied = permissions.cloneProfile(
                identity("permissionProfile/clone", "clone", 0, Map.of()),
                new PermissionProfileRef("standard", 1),
                "collaboration");
        PermissionProfile allowed = new PermissionProfile(
                copied.id(),
                2,
                initialFilePermission(copied),
                copied.network(),
                copied.processes(),
                new ToolPermission(TOOLS, ToolRisk.EXTERNAL_EFFECT, com.javaclaw.api.ApprovalRequirement.NONE),
                copied.resources());
        permissions.update(identity("permissionProfile/update", "permissions", 1, allowed), allowed);
    }

    FilePermission initialFilePermission(PermissionProfile source) {
        return source.files();
    }

    @AfterEach
    void closeActiveAccountsAndDispatcher() throws Exception {
        if (parent != null && account != null) {
            journal.deactivateBudget(parent.id(), account);
        }
        if (dispatcher != null) {
            dispatcher.close();
        }
        if (lifecycle != null) {
            lifecycle.close();
        }
    }

    com.javaclaw.server.persistence.TurnStartRequest childRequest(
            AgentTurn child, com.javaclaw.api.ResolvedTurnConfig configuration) {
        return new com.javaclaw.server.persistence.TurnStartRequest(
                child.threadId(),
                configuration,
                child.executionRoot(),
                core.promptSnapshot(child.id()),
                json.decode(core.toolCatalogSnapshot(child.id()), ToolCatalogSnapshot.class),
                new CorePayloads.Message(MessageRole.USER, "不能追加任务", List.of(), Optional.empty()),
                Optional.empty());
    }

    com.javaclaw.api.ToolCallRequest waitRequest(TurnId owner, TurnId child, long timeoutMillis) {
        return new com.javaclaw.api.ToolCallRequest(
                owner,
                "wait",
                new com.javaclaw.api.ToolIdentity("core", CollaborationTools.WAIT, 1),
                json.encode(Map.of("turnId", child, "timeoutMillis", timeoutMillis)),
                "wait-key",
                1);
    }

    void createParent(boolean toolPhase) {
        var thread = core.createThread(
                identity("thread/create", "parent-thread", 0, Map.of()),
                workspace,
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Parent");
        var request = new CoreRpcContracts.TurnStartPayload(thread.id(), ExecutionOverrides.empty(), "分析任务");
        var message = new CorePayloads.Message(MessageRole.USER, request.message(), List.of(), Optional.empty());
        AgentTurn queued =
                core.startTurn(identity("turn/start", "parent-turn", 0, request), factory.resolve(request, message));
        journal.beginOrRecover(factory.create(queued, request));
        parent = core.findTurn(queued.id()).orElseThrow();
        account = new BudgetAccount(BUDGET, CLOCK);
        journal.activateBudget(parent.id(), account);
        journal.recordModelIntent(parent.id(), 1, "a".repeat(64));
        if (toolPhase) {
            var tool = CollaborationTools.all().getFirst();
            journal.commitModelResult(
                    parent.id(),
                    1,
                    new ModelInvocationResult(
                            "",
                            List.of(new ModelToolCall("spawn-call", tool.identity(), json.parse("{}"))),
                            ModelUsage.zero(),
                            Optional.empty(),
                            Optional.empty(),
                            ModelFinishReason.TOOL_CALLS),
                    ModelUsage.zero());
        }
    }

    CollaborationRpcContracts.SpawnPayload request(String agentType) {
        ExecutionOverrides overrides = new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TurnBudget(1000, 100, 0, 0, Duration.ofMinutes(1))),
                Optional.empty(),
                Optional.empty());
        return new CollaborationRpcContracts.SpawnPayload(parent.id(), agentType, "检查代码", overrides, "Child");
    }

    TurnExecutionResult runChild(TurnExecutionCommand command, CancellationToken cancellation) throws Exception {
        journal.beginOrRecover(command);
        childStarted.countDown();
        while (!cancellation.isCancelled() && finishChild.getCount() > 0) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        TurnStatus status = cancellation.isCancelled() ? TurnStatus.CANCELLED : TurnStatus.COMPLETED;
        journal.transition(command.turn().id(), TurnStatus.RUNNING, status, Optional.empty());
        return new TurnExecutionResult(
                command.turn().id(), status, "", ModelUsage.zero(), 0, Optional.empty(), Optional.empty());
    }

    void awaitCancelled(TurnId id) throws Exception {
        awaitStatus(id, TurnStatus.CANCELLED);
    }

    void awaitStatus(TurnId id, TurnStatus expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (service.read(id).status() != expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertEquals(expected, service.read(id).status());
    }

    CommandIdentity identity(String method, String key, long revision, Object payload) {
        return new CommandIdentity(method, key, revision, json.encode(payload).sha256());
    }

    final class Catalog implements ToolCatalogPort {
        @Override
        public ToolCatalogSnapshot freeze(TurnId turn, PermissionProfile permission, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            List<ToolDescriptor> tools = CollaborationTools.all().stream()
                    .filter(tool -> spawnCatalog || !tool.identity().name().equals(CollaborationTools.SPAWN))
                    .filter(tool -> permission
                            .tools()
                            .allowedTools()
                            .contains(tool.identity().name()))
                    .filter(tool -> tool.risk().ordinal()
                            <= permission.tools().maximumRisk().ordinal())
                    .toList();
            return new ToolCatalogSnapshot(turn, 1, tools, permission, CLOCK.instant());
        }

        @Override
        public ToolCatalogSnapshot bindFrozen(
                TurnId turn,
                WorkspaceId owner,
                ToolCatalogSnapshot frozen,
                PermissionProfile permission,
                CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            return new ToolCatalogSnapshot(
                    turn, frozen.catalogRevision(), frozen.tools(), frozen.permissionCeiling(), frozen.capturedAt());
        }

        @Override
        public List<ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot) {
            return snapshot.tools();
        }
    }

    static final class FakeModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String model) {
            return new ModelCapabilities(false, true, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            throw new AssertionError("测试不调用真实或付费模型");
        }
    }
}
