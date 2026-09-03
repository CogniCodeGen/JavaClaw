package com.javaclaw.server.turn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ToolCatalogPort;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.runtime.TurnHarness;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProfileBindingService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.persistence.TurnStartRequest;

import static com.javaclaw.server.ProviderEndpointTestFixtures.chat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessTurnDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private PermissionProfileService profiles;
    private AgentProfileService agentProfiles;
    private ProfileBindingService bindings;
    private ProviderRef provider;
    private AgentProfileRef profile;
    private ToolCatalogPort catalogs;
    private ManagedWorktreeService worktrees;

    @BeforeEach
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        profiles = new PermissionProfileService(database, json, clock);
        profiles.installStandardProfile();
        initializeProviderAndProfile();
        bindings = new ProfileBindingService(database, core, agentProfiles, json, clock);
        worktrees = new ManagedWorktreeService(
                database, new AttachmentService(database, json, clock), json, clock, new PlatformSandboxExecutor());
        catalogs = emptyCatalogs();
    }

    private void initializeProviderAndProfile() {
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        ProviderEndpointSpec providerSpec = chat("Test Provider", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
        providers.create(
                identity(
                        "provider/create",
                        "test-provider",
                        new ProviderProfileRpcContracts.ProviderCreatePayload(
                                "test-provider", providerSpec, ProviderLifecycle.ACTIVE)),
                "test-provider",
                providerSpec,
                ProviderLifecycle.ACTIVE);
        provider = new ProviderRef("test-provider", 1, "test-model");
        agentProfiles = new AgentProfileService(database, providers, profiles, json, clock);
        AgentProfileSpec profileSpec = new AgentProfileSpec(
                "Test Profile",
                "测试说明",
                provider,
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                Set.of(),
                budget());
        agentProfiles.create(
                identity(
                        "profile/create",
                        "test-profile",
                        new ProviderProfileRpcContracts.AgentProfileCreatePayload("test-profile", profileSpec)),
                "test-profile",
                profileSpec);
        profile = new AgentProfileRef("test-profile", 1);
    }

    private ToolCatalogPort emptyCatalogs() {
        return new ToolCatalogPort() {
            @Override
            public ToolCatalogSnapshot freeze(
                    TurnId turnId,
                    com.javaclaw.api.PermissionProfile permissionProfile,
                    com.javaclaw.api.CancellationToken cancellation) {
                return new ToolCatalogSnapshot(turnId, 1, List.of(), permissionProfile, NOW);
            }

            @Override
            public ToolCatalogSnapshot bindFrozen(
                    TurnId turnId,
                    com.javaclaw.api.WorkspaceId workspaceId,
                    ToolCatalogSnapshot frozen,
                    com.javaclaw.api.PermissionProfile currentPermissions,
                    com.javaclaw.api.CancellationToken cancellation) {
                cancellation.throwIfCancelled();
                return new ToolCatalogSnapshot(
                        turnId, frozen.catalogRevision(), frozen.tools(), currentPermissions, NOW);
            }

            @Override
            public List<com.javaclaw.api.ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot) {
                return List.of();
            }
        };
    }

    @Test
    void escapedHarnessFailureIsPersistedAndTerminalRetryIsRecovered() throws Exception {
        ClosingModel model = new ClosingModel();
        TurnHarness failing = (command, cancellation) -> {
            throw new Exception("provider escaped");
        };
        try (HarnessTurnDispatcher dispatcher = dispatcher(model, failing)) {
            Fixture fixture = fixture("failure", dispatcher);
            TurnExecutionResult result =
                    dispatcher.dispatchAndAwait(fixture.turn(), fixture.request(), new CancellationSource());
            TurnExecutionResult recovered = dispatcher.dispatchAndAwait(
                    core.findTurn(fixture.turn().id()).orElseThrow(), fixture.request(), new CancellationSource());

            assertEquals(TurnStatus.FAILED, result.status());
            assertEquals("TURN_DISPATCH_FAILED", result.errorCode().orElseThrow());
            assertEquals(result.status(), recovered.status());
            assertTrue(core.listItems(fixture.thread().id()).stream()
                    .anyMatch(item -> CoreSchemas.ERROR.equals(item.schemaId())));
        }
        assertTrue(model.closed.get());
    }

    @Test
    void activeDispatchIsReusedAndCancellationPropagatesToHarness() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        TurnHarness blocking = (command, cancellation) -> {
            journal.beginOrRecover(command);
            started.countDown();
            while (!cancellation.isCancelled()) {
                Thread.sleep(5);
            }
            journal.transition(command.turn().id(), TurnStatus.RUNNING, TurnStatus.CANCELLED, Optional.empty());
            return new TurnExecutionResult(
                    command.turn().id(),
                    TurnStatus.CANCELLED,
                    "",
                    ModelUsage.zero(),
                    0,
                    Optional.empty(),
                    Optional.empty());
        };
        try (HarnessTurnDispatcher dispatcher = dispatcher(new ClosingModel(), blocking)) {
            Fixture fixture = fixture("blocking", dispatcher);
            dispatcher.dispatch(fixture.turn(), fixture.request());
            dispatcher.dispatch(fixture.turn(), fixture.request());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            dispatcher.cancel(fixture.turn().id(), "user cancelled");
            TurnExecutionResult result = dispatcher.dispatchAndAwait(
                    core.findTurn(fixture.turn().id()).orElseThrow(), fixture.request(), new CancellationSource());

            assertEquals(TurnStatus.CANCELLED, result.status());
            assertEquals(
                    TurnStatus.CANCELLED,
                    core.findTurn(fixture.turn().id()).orElseThrow().status());
        }
    }

    @Test
    void completionDuringConcurrentResumeDoesNotStartSecondHarness() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch completeFirst = new CountDownLatch(1);
        CountDownLatch secondBindingStarted = new CountDownLatch(1);
        CountDownLatch completeSecondBinding = new CountDownLatch(1);
        AtomicInteger harnessInvocations = new AtomicInteger();
        ToolCatalogPort blockingCatalogs = blockingSecondBinding(secondBindingStarted, completeSecondBinding);
        TurnHarness completing = (command, cancellation) -> {
            harnessInvocations.incrementAndGet();
            journal.beginOrRecover(command);
            firstStarted.countDown();
            awaitRequired(completeFirst, "首个 Harness 未获准完成");
            journal.transition(command.turn().id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
            return new TurnExecutionResult(
                    command.turn().id(),
                    TurnStatus.COMPLETED,
                    "done",
                    ModelUsage.zero(),
                    0,
                    Optional.empty(),
                    Optional.empty());
        };
        LifecycleCoordinator turnLifecycle = lifecycle();
        try (turnLifecycle;
                HarnessTurnDispatcher dispatcher =
                        dispatcher(new ClosingModel(), completing, turnLifecycle, blockingCatalogs)) {
            Fixture fixture = fixture("terminal-resume-race", dispatcher);
            dispatcher.dispatch(fixture.turn(), fixture.request());
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            AtomicReference<Throwable> resumeFailure = new AtomicReference<>();
            Thread resumeThread = Thread.ofVirtual().start(() -> {
                try {
                    dispatcher.resume(fixture.turn().id());
                } catch (Throwable failure) {
                    resumeFailure.set(failure);
                }
            });
            try {
                assertTrue(secondBindingStarted.await(2, TimeUnit.SECONDS));
                completeFirst.countDown();
                awaitNoActiveLeases(turnLifecycle);
                completeSecondBinding.countDown();
                resumeThread.join(TimeUnit.SECONDS.toMillis(2));
                awaitNoActiveLeases(turnLifecycle);

                assertFalse(resumeThread.isAlive());
                assertNull(resumeFailure.get());
                assertEquals(1, harnessInvocations.get());
                assertEquals(
                        TurnStatus.COMPLETED,
                        core.findTurn(fixture.turn().id()).orElseThrow().status());
            } finally {
                completeFirst.countDown();
                completeSecondBinding.countDown();
                resumeThread.join(TimeUnit.SECONDS.toMillis(2));
            }
        }
    }

    @Test
    void queuedTurnWithoutExecutionCanBeCancelledDurably() throws Exception {
        AtomicInteger harnessInvocations = new AtomicInteger();
        TurnHarness delegate = successfulHarness();
        TurnHarness counting = (command, cancellation) -> {
            harnessInvocations.incrementAndGet();
            return delegate.execute(command, cancellation);
        };
        try (HarnessTurnDispatcher dispatcher = dispatcher(new ClosingModel(), counting)) {
            Fixture fixture = fixture("queued-cancel", dispatcher);
            dispatcher.cancel(fixture.turn().id(), "cancel before dispatch");
            dispatcher.cancel(fixture.turn().id(), "already terminal");
            AgentTurn cancelled = core.findTurn(fixture.turn().id()).orElseThrow();

            assertTrue(journal.findRecovery(cancelled.id()).isEmpty());
            TurnExecutionResult first =
                    dispatcher.dispatchAndAwait(cancelled, fixture.request(), new CancellationSource());
            TurnExecutionResult second =
                    dispatcher.dispatchAndAwait(cancelled, fixture.request(), new CancellationSource());

            assertEquals(TurnStatus.CANCELLED, cancelled.status());
            assertEquals(TurnStatus.CANCELLED, first.status());
            assertEquals("", first.assistantText());
            assertEquals(ModelUsage.zero(), first.usage());
            assertEquals(0, first.toolCalls());
            assertTrue(first.providerState().isEmpty());
            assertTrue(first.errorCode().isEmpty());
            assertEquals(first, second);
            assertEquals(0, harnessInvocations.get());
        }
    }

    @Test
    void 项目约定在Turn创建时冻结且后续文件变化不影响执行() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace-instructions"));
        Path agents = workspace.resolve("AGENTS.md");
        Files.writeString(agents, "冻结前约定", StandardCharsets.UTF_8);
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        TurnHarness capturing = (command, cancellation) -> {
            systemPrompt.set(command.systemInstruction());
            journal.beginOrRecover(command);
            journal.transition(command.turn().id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
            return new TurnExecutionResult(
                    command.turn().id(),
                    TurnStatus.COMPLETED,
                    "done",
                    ModelUsage.zero(),
                    0,
                    Optional.empty(),
                    Optional.empty());
        };
        try (HarnessTurnDispatcher dispatcher = dispatcher(new ClosingModel(), capturing)) {
            Fixture fixture = fixture("instructions", dispatcher);
            Files.writeString(agents, "冻结后变化", StandardCharsets.UTF_8);

            dispatcher.dispatchAndAwait(fixture.turn(), fixture.request(), new CancellationSource());

            assertTrue(systemPrompt.get().contains("冻结前约定"));
            assertFalse(systemPrompt.get().contains("冻结后变化"));
            assertEquals(
                    fixture.turn().promptManifestDigest(),
                    core.promptSnapshot(fixture.turn().id()).sha256());
        }
    }

    @Test
    void resolutionRejectsUnknownProfileMismatchedRequestAndNonDispatchableState() throws Exception {
        ClosingModel model = new ClosingModel();
        try (HarnessTurnDispatcher dispatcher = dispatcher(model, successfulHarness())) {
            Fixture fixture = fixture("validation", dispatcher);
            CoreRpcContracts.TurnStartPayload unknown = new CoreRpcContracts.TurnStartPayload(
                    fixture.thread().id(),
                    Optional.of(new AgentProfileRef("unknown", 1)),
                    fixture.request().message());
            CorePayloads.Message unknownMessage =
                    new CorePayloads.Message(MessageRole.USER, unknown.message(), List.of(), Optional.empty());
            assertThrows(RuntimeException.class, () -> dispatcher.resolve(unknown, unknownMessage));

            CoreRpcContracts.TurnStartPayload mismatched = new CoreRpcContracts.TurnStartPayload(
                    fixture.thread().id(),
                    Optional.of(new AgentProfileRef(profile.id(), 2)),
                    fixture.request().message());
            assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(fixture.turn(), mismatched));

            journal.transition(fixture.turn().id(), TurnStatus.QUEUED, TurnStatus.WAITING, Optional.empty());
            assertThrows(
                    IllegalStateException.class,
                    () -> dispatcher.dispatch(core.findTurn(fixture.turn().id()).orElseThrow(), fixture.request()));
        }
    }

    @Test
    void runtimeResourcesAndLifecycleArgumentsAreRequired() throws Exception {
        ClosingModel model = new ClosingModel();
        LifecycleCoordinator lifecycle = lifecycle();
        try (lifecycle) {
            assertThrows(
                    NullPointerException.class,
                    () -> new HarnessTurnDispatcher.RuntimeResources(
                            null, successfulHarness(), journal, lifecycle, catalogs));
            assertThrows(
                    NullPointerException.class,
                    () -> new HarnessTurnDispatcher.RuntimeResources(model, null, journal, lifecycle, catalogs));
            assertThrows(
                    NullPointerException.class,
                    () -> new HarnessTurnDispatcher.RuntimeResources(
                            model, successfulHarness(), null, lifecycle, catalogs));
            assertThrows(
                    NullPointerException.class,
                    () -> new HarnessTurnDispatcher.RuntimeResources(
                            model, successfulHarness(), journal, null, catalogs));
            assertThrows(
                    NullPointerException.class,
                    () -> new HarnessTurnDispatcher(
                            null,
                            new HarnessTurnDispatcher.RuntimeResources(
                                    model, successfulHarness(), journal, lifecycle, catalogs),
                            "system",
                            clock,
                            json));
        }
        assertFalse(model.closed.get());
    }

    private HarnessTurnDispatcher dispatcher(ModelGateway model, TurnHarness harness) {
        return dispatcher(model, harness, lifecycle(), catalogs);
    }

    private HarnessTurnDispatcher dispatcher(
            ModelGateway model, TurnHarness harness, LifecycleCoordinator lifecycle, ToolCatalogPort toolCatalogs) {
        return new HarnessTurnDispatcher(
                new TurnPlatformServices(
                        core,
                        agentProfiles,
                        bindings,
                        profiles,
                        new ProjectInstructionResolver(temporaryDirectory, clock),
                        worktrees),
                new HarnessTurnDispatcher.RuntimeResources(model, harness, journal, lifecycle, toolCatalogs),
                "system",
                clock,
                json);
    }

    private ToolCatalogPort blockingSecondBinding(CountDownLatch started, CountDownLatch release) {
        AtomicInteger bindings = new AtomicInteger();
        return new ToolCatalogPort() {
            @Override
            public ToolCatalogSnapshot freeze(
                    TurnId turnId, PermissionProfile permissions, com.javaclaw.api.CancellationToken cancellation) {
                return catalogs.freeze(turnId, permissions, cancellation);
            }

            @Override
            public ToolCatalogSnapshot bindFrozen(
                    TurnId turnId,
                    WorkspaceId workspaceId,
                    ToolCatalogSnapshot frozen,
                    PermissionProfile currentPermissions,
                    com.javaclaw.api.CancellationToken cancellation) {
                if (bindings.incrementAndGet() == 2) {
                    started.countDown();
                    awaitRequired(release, "并发恢复的目录绑定未获准继续");
                }
                return catalogs.bindFrozen(turnId, workspaceId, frozen, currentPermissions, cancellation);
            }

            @Override
            public List<ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot) {
                return catalogs.initialTools(snapshot);
            }
        };
    }

    private static void awaitRequired(CountDownLatch latch, String message) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, failure);
        }
    }

    private static void awaitNoActiveLeases(LifecycleCoordinator lifecycle) throws InterruptedException {
        for (int attempt = 0; attempt < 400 && lifecycle.status().activeLeases() != 0; attempt++) {
            Thread.sleep(5);
        }
        assertEquals(0, lifecycle.status().activeLeases());
    }

    private LifecycleCoordinator lifecycle() {
        return new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock), Duration.ofMillis(20));
    }

    private TurnHarness successfulHarness() {
        return (command, cancellation) -> {
            journal.beginOrRecover(command);
            journal.transition(command.turn().id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
            return new TurnExecutionResult(
                    command.turn().id(),
                    TurnStatus.COMPLETED,
                    "done",
                    ModelUsage.zero(),
                    0,
                    Optional.empty(),
                    Optional.empty());
        };
    }

    private Fixture fixture(String suffix, HarnessTurnDispatcher dispatcher) {
        try {
            java.nio.file.Files.createDirectories(temporaryDirectory.resolve("workspace-" + suffix));
        } catch (java.io.IOException failure) {
            throw new AssertionError("无法创建测试 Workspace", failure);
        }
        CoreRpcContracts.WorkspaceCreatePayload workspacePayload = new CoreRpcContracts.WorkspaceCreatePayload(
                "dispatcher", temporaryDirectory.resolve("workspace-" + suffix));
        Workspace workspace = core.createWorkspace(
                identity("workspace/create", "workspace-" + suffix, workspacePayload),
                workspacePayload.name(),
                workspacePayload.root());
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        CoreRpcContracts.TurnStartPayload request =
                new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.of(profile), suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        TurnStartRequest resolved = dispatcher.resolve(request, message);
        AgentTurn turn = core.startTurn(identity("turn/start", "turn-" + suffix, request), resolved);
        return new Fixture(thread, turn, request);
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private record Fixture(ConversationThread thread, AgentTurn turn, CoreRpcContracts.TurnStartPayload request) {}

    private static final class ClosingModel implements ModelGateway, AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (!new ProviderRef("test-provider", 1, "test-model").routeKey().equals(modelId)) {
                throw new IllegalArgumentException("unknown model");
            }
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
