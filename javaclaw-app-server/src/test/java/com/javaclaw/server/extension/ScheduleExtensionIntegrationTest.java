package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
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
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.BuiltinManagementFixtures;
import com.javaclaw.server.ProviderRoleRpcFixtures;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleExtensionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROVIDER_ID = "schedule-provider";
    private static final AgentRoleRef PROFILE = new AgentRoleRef("schedule-agent", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(4, 20_000, 20_000, 20);

    @TempDir
    Path temporaryDirectory;

    @Test
    void schedulePersistsFiveFirePreviewAndCreatesH2OccurrenceBeforeAsyncExecution() throws Exception {
        RecordingModel model = new RecordingModel();
        Path dataRoot = temporaryDirectory.resolve("data-v6");
        List<Boolean> loginStartupChanges = new ArrayList<>();
        try (AppServerBootstrap.Components components =
                AppServerBootstrap.create(dataRoot, CLOCK, model, loginStartupChanges::add)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            Workspace workspace = createWorkspace(session, components);
            installProfile(session, components);
            createDefinition(
                    session,
                    components,
                    workspace,
                    BuiltinExtensionIds.PLAN,
                    BuiltinManagementFixtures.plan(plan()),
                    "plan-create");
            createDefinition(
                    session, components, workspace, BuiltinExtensionIds.SCHEDULE, schedule(), "schedule-create");

            ScheduleContracts.Preview preview = query(
                    session,
                    components,
                    workspace,
                    "preview",
                    new ScheduleContracts.PreviewRequest("daily-plan", 1, NOW),
                    ScheduleContracts.Preview.class);
            ScheduleContracts.Occurrence occurrence = command(
                    session,
                    components,
                    workspace,
                    scheduleCommand("occurrence/run", new ScheduleContracts.ManualRun("daily-plan"), "schedule-run", 1),
                    ScheduleContracts.Occurrence.class);
            ScheduleContracts.OccurrencePage page = query(
                    session,
                    components,
                    workspace,
                    "occurrence/list",
                    new ScheduleContracts.OccurrenceQuery(Optional.of("daily-plan"), "", 20),
                    ScheduleContracts.OccurrencePage.class);

            assertEquals(5, preview.instants().size());
            assertEquals(
                    ScheduleContracts.OccurrenceState.DISPATCHED,
                    occurrence.status().state());
            assertEquals(
                    List.of(occurrence.identity()),
                    page.occurrences().stream()
                            .map(ScheduleContracts.Occurrence::identity)
                            .toList());
            assertTrue(loginStartupChanges.contains(true));
        }
    }

    @Test
    void enabledScheduleAndProjectionRecoverFromH2WithoutCatchingUpMissedRuns() throws Exception {
        Path dataRoot = temporaryDirectory.resolve("data-v6");
        Workspace workspace;
        List<Boolean> initialStartupChanges = new ArrayList<>();
        try (AppServerBootstrap.Components components =
                AppServerBootstrap.create(dataRoot, CLOCK, new RecordingModel(), initialStartupChanges::add)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            workspace = createWorkspace(session, components);
            installProfile(session, components);
            createDefinition(
                    session,
                    components,
                    workspace,
                    BuiltinExtensionIds.PLAN,
                    BuiltinManagementFixtures.plan(plan()),
                    "restore-plan-create");
            createDefinition(
                    session,
                    components,
                    workspace,
                    BuiltinExtensionIds.SCHEDULE,
                    schedule(),
                    "restore-schedule-create");
            assertTrue(initialStartupChanges.contains(true));
        }

        List<Boolean> restoredStartupChanges = new ArrayList<>();
        try (AppServerBootstrap.Components components =
                AppServerBootstrap.create(dataRoot, CLOCK, new RecordingModel(), restoredStartupChanges::add)) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            ScheduleContracts.Preview preview = query(
                    session,
                    components,
                    workspace,
                    "preview",
                    new ScheduleContracts.PreviewRequest("daily-plan", 1, NOW),
                    ScheduleContracts.Preview.class);
            ScheduleContracts.OccurrencePage occurrences = query(
                    session,
                    components,
                    workspace,
                    "occurrence/list",
                    new ScheduleContracts.OccurrenceQuery(Optional.empty(), "", 20),
                    ScheduleContracts.OccurrencePage.class);

            assertEquals(5, preview.instants().size());
            assertTrue(occurrences.occurrences().isEmpty());
            assertTrue(restoredStartupChanges.contains(true));
        }
    }

    private ScheduleManagementContracts.SaveRequest schedule() {
        return new ScheduleManagementContracts.SaveRequest(
                "daily-plan",
                "执行计划",
                true,
                ScheduleContracts.TimingKind.FIXED_INTERVAL,
                ScheduleContracts.TargetKind.DEFINITION,
                BuiltinExtensionIds.PLAN,
                "scheduled-plan",
                1,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.of(5L),
                Optional.of(NOW.plus(Duration.ofHours(1))),
                com.javaclaw.server.TurnContractFixtures.select(PROFILE),
                "执行计划",
                "execute",
                BUDGET.maximumTurns(),
                BUDGET.inputTokens(),
                BUDGET.outputTokens(),
                BUDGET.toolCalls(),
                List.of());
    }

    private PlanContracts.Definition plan() {
        return new PlanContracts.Definition(
                "scheduled-plan",
                1,
                "定时计划",
                "完成定时步骤",
                "core",
                List.of(),
                List.of(),
                List.of(new PlanContracts.Step("execute", "执行", "execute", "步骤完成", List.of())),
                NOW);
    }

    private void installProfile(AppServerSession session, AppServerBootstrap.Components components) {
        ProviderRoleRpcFixtures.install(
                session,
                components,
                new ProviderRoleRpcFixtures.Installation(
                        PROVIDER_ID,
                        RecordingModel.ID,
                        PROFILE.id(),
                        new PermissionProfileRef("standard", 1),
                        Set.of(),
                        new TurnBudget(4_000, 1_000, 2, 0, Duration.ofSeconds(30))));
    }

    private void createDefinition(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            String extensionId,
            Object document,
            String key) {
        command(
                session,
                components,
                workspace,
                new ExtensionCommand(extensionId, "definition/create", document, key, 0),
                Object.class);
    }

    private <T> T command(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            ExtensionCommand command,
            Class<T> type) {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                command.extensionId(),
                workspace.id(),
                Optional.empty(),
                Optional.empty(),
                command.operation(),
                components.json().encode(command.payload()));
        ExtensionRpcContracts.CallResult result = decode(
                session.handle(request(
                        components,
                        command.key(),
                        "extension/command",
                        new WriteCommand(
                                command.key(),
                                command.expectedRevision(),
                                components.json().encode(call)))),
                components,
                ExtensionRpcContracts.CallResult.class);
        return type == Object.class ? null : components.json().decode(result.payload(), type);
    }

    private static ExtensionCommand scheduleCommand(
            String operation, Object payload, String key, long expectedRevision) {
        return new ExtensionCommand(BuiltinExtensionIds.SCHEDULE, operation, payload, key, expectedRevision);
    }

    private <T> T query(
            AppServerSession session,
            AppServerBootstrap.Components components,
            Workspace workspace,
            String operation,
            Object payload,
            Class<T> type) {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                BuiltinExtensionIds.SCHEDULE,
                workspace.id(),
                Optional.empty(),
                Optional.empty(),
                operation,
                components.json().encode(payload));
        ExtensionRpcContracts.CallResult result = decode(
                session.handle(request(components, operation, "extension/query", call)),
                components,
                ExtensionRpcContracts.CallResult.class);
        return components.json().decode(result.payload(), type);
    }

    private Workspace createWorkspace(AppServerSession session, AppServerBootstrap.Components components) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("Schedule", temporaryDirectory.resolve("workspace"));
        return decode(
                session.handle(request(
                        components,
                        "workspace",
                        "workspace/create",
                        new WriteCommand("workspace-key", 0, components.json().encode(payload)))),
                components,
                Workspace.class);
    }

    private void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("schedule-test", "5.0"),
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

    private record ExtensionCommand(
            String extensionId, String operation, Object payload, String key, long expectedRevision) {}

    private static final class RecordingModel implements ModelGateway {
        private static final String ID = "schedule-model";
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (!new ProviderRef(PROVIDER_ID, 1, ID).routeKey().equals(modelId)) {
                throw new IllegalArgumentException("unknown model endpoint");
            }
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            invocations.incrementAndGet();
            return new ModelInvocationResult(
                    "定时步骤完成",
                    List.of(),
                    new ModelUsage(10, 2, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }
    }
}
