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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.runtime.TurnHarness;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProfileBindingService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;

import static com.javaclaw.server.ProviderEndpointTestFixtures.chat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAutomationStepPortTest {
    private static final Instant NOW = Instant.parse("2026-09-02T04:00:00Z");
    private static final ToolIdentity TOOL_ID = new ToolIdentity("com.javaclaw.workflow", "workflow_store", 1);

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private ApprovalService approvals;
    private LifecycleCoordinator lifecycle;
    private HarnessTurnDispatcher dispatcher;
    private RecordingHost host;
    private ServerAutomationStepPort steps;
    private Workspace workspace;
    private AutomationExecutionSnapshot snapshot;

    @BeforeEach
    void initializeDataV5() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        PermissionProfile permission = installPermission(permissions);
        AgentProfileService profiles = installProfile(database, permissions, permission, clock);
        ProfileBindingService bindings = new ProfileBindingService(database, core, profiles, json, clock);
        AttachmentService attachments = new AttachmentService(database, json, clock);
        ManagedWorktreeService worktrees =
                new ManagedWorktreeService(database, attachments, json, clock, unavailableSandbox());
        approvals = new ApprovalService(database, json, clock);
        host = new RecordingHost(descriptor());
        ExtensionToolPlatform tools = new ExtensionToolPlatform(new ExtensionToolPlatform.Dependencies(
                host,
                core,
                approvals,
                permissions,
                worktrees,
                new ExtensionCatalogRepository(database, json, clock),
                json,
                clock,
                new UnattendedToolGrantService(database, json, clock),
                Optional.empty()));
        lifecycle = new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock), Duration.ofSeconds(1));
        dispatcher = dispatcher(profiles, bindings, permissions, worktrees, tools, clock);
        InputRequestService inputs = new InputRequestService(database, json, clock);
        steps = new ServerAutomationStepPort(new ServerAutomationStepPort.Dependencies(
                core, worktrees, dispatcher, journal, tools, inputs, json, clock));
        workspace = createWorkspace();
        snapshot =
                dispatcher.freeze(workspace.id(), new AgentProfileRef("workflow-agent", 1), new CancellationSource());
    }

    @AfterEach
    void closeRuntime() throws Exception {
        dispatcher.close();
        approvals.close();
        lifecycle.close();
    }

    @Test
    void tool步骤提交ToolCallResult和EffectReceipt且重试不重复副作用() throws Exception {
        AutomationStepPort.StepContext context = context("tool-step");
        AutomationStepPort.ToolCommand command =
                new AutomationStepPort.ToolCommand(context, TOOL_ID.name(), json.parse("{\"value\":1}"));

        AutomationStepPort.ToolResult first = steps.executeTool(command, new CancellationSource());
        AutomationStepPort.ToolResult retried = steps.executeTool(command, new CancellationSource());

        assertEquals(first, retried);
        assertTrue(first.successful());
        assertEquals(json.parse("{\"stored\":true}"), first.output());
        assertEquals(Optional.of("tool-step:effect"), first.effectReceiptKey());
        assertEquals(1, host.executions.get());
        assertEquals(
                TurnStatus.COMPLETED,
                core.findTurn(first.turnId()).orElseThrow().status());
        assertEquals(1, count(first.threadId(), CoreSchemas.TOOL_CALL));
        assertEquals(1, count(first.threadId(), CoreSchemas.TOOL_RESULT));
        assertEquals(1, count(first.threadId(), CoreSchemas.EFFECT_RECEIPT));
    }

    @Test
    void input步骤进入Waiting并在重试时复用同一请求() {
        AutomationStepPort.InputCommand command = new AutomationStepPort.InputCommand(
                context("input-step"),
                "请选择下一步",
                json.parse("{\"properties\":{\"choice\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                NOW.plusSeconds(60));

        AutomationStepPort.InputResult first = steps.openInput(command, new CancellationSource());
        AutomationStepPort.InputResult retried = steps.openInput(command, new CancellationSource());

        assertEquals(first, retried);
        assertEquals(
                TurnStatus.WAITING, core.findTurn(first.turnId()).orElseThrow().status());
        assertEquals(1, count(first.threadId(), CoreSchemas.INPUT));
    }

    @Test
    void extension失败写入稳定Error并把Turn收口为Failed() {
        host.fail.set(true);
        AutomationStepPort.ToolCommand command =
                new AutomationStepPort.ToolCommand(context("failing-step"), TOOL_ID.name(), json.parse("{}"));

        assertThrows(IllegalStateException.class, () -> steps.executeTool(command, new CancellationSource()));

        AgentTurnView failed = onlyTurn("执行固定工具 " + TOOL_ID.name());
        assertEquals(TurnStatus.FAILED, failed.status());
        assertEquals(1, count(failed.threadId(), CoreSchemas.ERROR));
        assertEquals(1, host.executions.get());
    }

    @Test
    void 已取消步骤在创建任何Thread前FailClosed() {
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("workflow cancelled");
        long before = core.listThreads(workspace.id()).size();

        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> steps.executeTool(
                        new AutomationStepPort.ToolCommand(context("cancelled-step"), TOOL_ID.name(), json.parse("{}")),
                        cancelled));

        assertEquals(before, core.listThreads(workspace.id()).size());
        assertEquals(0, host.executions.get());
    }

    private HarnessTurnDispatcher dispatcher(
            AgentProfileService profiles,
            ProfileBindingService bindings,
            PermissionProfileService permissions,
            ManagedWorktreeService worktrees,
            ExtensionToolPlatform tools,
            Clock clock) {
        TurnHarness unusedHarness = (command, cancellation) -> new TurnExecutionResult(
                command.turn().id(),
                TurnStatus.COMPLETED,
                "",
                com.javaclaw.runtime.ModelUsage.zero(),
                0,
                Optional.empty(),
                Optional.empty());
        return new HarnessTurnDispatcher(
                new TurnPlatformServices(
                        core,
                        profiles,
                        bindings,
                        permissions,
                        new ProjectInstructionResolver(temporaryDirectory.resolve("state"), clock),
                        worktrees),
                new HarnessTurnDispatcher.RuntimeResources(
                        new CapabilityModel(), unusedHarness, journal, lifecycle, tools),
                "system",
                clock,
                json);
    }

    private AgentProfileService installProfile(
            H2Database database, PermissionProfileService permissions, PermissionProfile permission, Clock clock) {
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        providers.create(
                identity("provider/create", "provider", Map.of()),
                "provider",
                providerSpec(),
                ProviderLifecycle.ACTIVE);
        AgentProfileService profiles = new AgentProfileService(database, providers, permissions, json, clock);
        AgentProfileSpec spec = new AgentProfileSpec(
                "Workflow",
                "",
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef(permission.id(), permission.version()),
                Set.of(TOOL_ID.name()),
                budget());
        AgentProfile created = profiles.create(identity("profile/create", "profile", Map.of()), "workflow-agent", spec);
        assertEquals(1, created.revision());
        return profiles;
    }

    private PermissionProfile installPermission(PermissionProfileService permissions) {
        PermissionProfile cloned = permissions.cloneProfile(
                identity("permissionProfile/clone", "clone-permission", Map.of()),
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                "workflow-permission");
        PermissionProfile updated = new PermissionProfile(
                cloned.id(),
                2,
                cloned.files(),
                cloned.network(),
                cloned.processes(),
                new ToolPermission(Set.of(TOOL_ID.name()), ToolRisk.WORKSPACE_WRITE, ApprovalRequirement.NONE),
                cloned.resources());
        return permissions.update(identity("permissionProfile/update", "update-permission", 1, updated), updated);
    }

    private Workspace createWorkspace() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        CoreRpcContracts.WorkspaceCreatePayload payload = new CoreRpcContracts.WorkspaceCreatePayload("Workflow", root);
        return core.createWorkspace(identity("workspace/create", "workspace", payload), payload.name(), root);
    }

    private AutomationStepPort.StepContext context(String key) {
        return new AutomationStepPort.StepContext(workspace.id(), Optional.empty(), "Workflow " + key, snapshot, key);
    }

    private long count(com.javaclaw.api.ThreadId threadId, String schemaId) {
        return core.listItems(threadId).stream()
                .filter(item -> schemaId.equals(item.schemaId()))
                .count();
    }

    private AgentTurnView onlyTurn(String expectedMessage) {
        var thread = core.listThreads(workspace.id()).stream()
                .filter(candidate -> candidate.title().startsWith("Workflow failing-step"))
                .findFirst()
                .orElseThrow();
        var turn = core.listRecoverableTurns().stream()
                .filter(candidate -> candidate.threadId().equals(thread.id()))
                .findFirst()
                .orElseGet(() -> core.listItems(thread.id()).stream()
                        .findFirst()
                        .flatMap(item -> core.findTurn(item.turnId()))
                        .orElseThrow());
        assertEquals(expectedMessage, core.turnUserMessage(turn.id()).text());
        return new AgentTurnView(thread.id(), turn.status());
    }

    private ToolDescriptor descriptor() {
        return new ToolDescriptor(
                TOOL_ID,
                "保存 Workflow 输出",
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"),
                ToolRisk.WORKSPACE_WRITE,
                Set.of("workflow"));
    }

    private static ProviderEndpointSpec providerSpec() {
        return chat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "model");
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
                ServerAutomationStepPortTest.class.getClassLoader(),
                new Class<?>[] {SandboxExecutor.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("sandbox should not be called: " + method.getName());
                });
    }

    private record AgentTurnView(com.javaclaw.api.ThreadId threadId, TurnStatus status) {}

    private final class RecordingHost implements ExtensionHost {
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicBoolean fail = new AtomicBoolean();
        private final ToolDescriptor tool;

        private RecordingHost(ToolDescriptor tool) {
            this.tool = tool;
        }

        @Override
        public List<com.javaclaw.protocol.ExtensionRpcContracts.Summary> list() {
            return List.of();
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(tool);
        }

        @Override
        public ExtensionResponse executeTool(
                ToolCallRequest request,
                ToolDescriptor frozenDescriptor,
                PermissionProfile callerPermissions,
                CancellationToken cancellation) {
            executions.incrementAndGet();
            if (fail.get()) {
                throw new IllegalStateException("extension failed");
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

    private static final class CapabilityModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                com.javaclaw.api.TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                CancellationToken cancellation) {
            throw new AssertionError("model should not be invoked by fixed Workflow tool steps");
        }
    }
}
