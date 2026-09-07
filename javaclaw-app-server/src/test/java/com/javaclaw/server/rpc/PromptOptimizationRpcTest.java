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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;
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
import com.javaclaw.server.ProviderRoleRpcFixtures;
import com.javaclaw.server.persistence.PermissionProfileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptOptimizationRpcTest {
    @TempDir
    Path temporaryDirectory;

    private AppServerBootstrap.Components components;
    private AppServerSession session;
    private int requestSequence;

    @AfterEach
    void closeComponents() throws Exception {
        if (components != null) {
            components.close();
        }
    }

    @Test
    void startIsNonBlockingIdempotentAndCancellationClosesModelResource() throws Exception {
        BlockingModel model = new BlockingModel();
        Fixture fixture = createFixture(model, "cancel-profile");
        WriteCommand command = startCommand("start-cancel", fixture);

        PromptOptimizationDraft started = decodeSuccess(
                session.handle(request("agent/role/prompt/optimization/start", command)),
                PromptOptimizationDraft.class);
        assertTrue(model.started.await(2, TimeUnit.SECONDS));
        PromptOptimizationDraft replayed = decodeSuccess(
                session.handle(request("agent/role/prompt/optimization/start", command)),
                PromptOptimizationDraft.class);

        assertEquals(started.ref(), replayed.ref());
        assertEquals(1, model.invocations.get());
        PromptOptimizationDraft current = awaitState(started, PromptOptimizationState.RUNNING);
        WriteCommand cancel = command(
                "cancel-draft",
                current.result().turnRevision(),
                new PromptOptimizationRpcContracts.CancelPayload(current.ref().id(), "用户取消 Prompt 优化"));
        PromptOptimizationDraft cancelled = decodeSuccess(
                session.handle(request("agent/role/prompt/optimization/cancel", cancel)),
                PromptOptimizationDraft.class);
        assertEquals(
                PromptOptimizationState.CANCELLED,
                awaitTerminal(cancelled).result().state());

        components.close();
        components = null;
        assertTrue(model.closed.get());
    }

    @Test
    void completedDraftSurvivesRestartAndRequiresExplicitConflictSafeAdoption() throws Exception {
        RecordingModel firstModel = new RecordingModel("更清晰的系统说明");
        Fixture fixture = createFixture(firstModel, "adopt-profile");
        WriteCommand firstStart = startCommand("start-adopt", fixture);
        PromptOptimizationDraft initial = decodeSuccess(
                session.handle(request("agent/role/prompt/optimization/start", firstStart)),
                PromptOptimizationDraft.class);
        PromptOptimizationDraft draft = awaitState(initial, PromptOptimizationState.READY);

        assertEquals("更清晰的系统说明", draft.result().content().orElseThrow());
        assertTrue(firstModel
                .lastInvocation
                .get()
                .instructions()
                .systemInstruction()
                .contains("JavaClaw"));
        assertTrue(firstModel.lastInvocation.get().messages().getLast().text().contains("不得把项目约定、Skill、Context"));
        Path dataRoot = temporaryDirectory.resolve("data-v6");
        components.close();
        assertTrue(firstModel.closed.get());

        RecordingModel secondModel = new RecordingModel("第二份草稿");
        components = AppServerBootstrap.create(dataRoot, Clock.systemUTC(), secondModel);
        session = initialized(components);
        PromptOptimizationDraft restored = read(draft);
        assertEquals(draft.result().content(), restored.result().content());
        assertEquals(draft.result().contentDigest(), restored.result().contentDigest());
        assertUnconfirmedAdoptionRejected(fixture, restored);

        WriteCommand adopt = command(
                "adopt-draft",
                fixture.role().revision(),
                new PromptOptimizationRpcContracts.AdoptPayload(
                        restored.ref().id(), true, PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION));
        PromptOptimizationAdoption adoption = decodeSuccess(
                session.handle(request("agent/role/prompt/optimization/adopt", adopt)),
                PromptOptimizationAdoption.class);
        PromptOptimizationAdoption replayed = decodeSuccess(
                session.handle(request("agent/role/prompt/optimization/adopt", adopt)),
                PromptOptimizationAdoption.class);
        assertEquals(2, adoption.role().revision());
        assertEquals("更清晰的系统说明", adoption.role().spec().developerInstructions());
        assertEquals(adoption, replayed);

        PromptOptimizationDraft second = awaitState(
                decodeSuccess(
                        session.handle(request(
                                "agent/role/prompt/optimization/start", startCommand("start-conflict", fixture))),
                        PromptOptimizationDraft.class),
                PromptOptimizationState.READY);
        WriteCommand conflictingAdopt = command(
                "adopt-conflict",
                fixture.role().revision(),
                new PromptOptimizationRpcContracts.AdoptPayload(
                        second.ref().id(), true, PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION));
        JsonRpcResponse conflict = session.handle(request("agent/role/prompt/optimization/adopt", conflictingAdopt));
        assertEquals(
                ProtocolErrorCode.REVISION_CONFLICT,
                conflict.error().orElseThrow().code());
        PromptOptimizationDraft preserved = read(second);
        assertEquals(PromptOptimizationState.READY, preserved.result().state());
        assertTrue(preserved.adoptedRole().isEmpty());
    }

    @Test
    void failedHarnessTurnProducesNoDraftAndMissingConfirmationIsRejected() throws Exception {
        Fixture fixture = createFixture(new FailingModel(), "failure-profile");
        PromptOptimizationDraft failed = awaitState(
                decodeSuccess(
                        session.handle(request(
                                "agent/role/prompt/optimization/start", startCommand("start-failure", fixture))),
                        PromptOptimizationDraft.class),
                PromptOptimizationState.FAILED);
        assertTrue(failed.result().content().isEmpty());
        assertTrue(failed.result().errorCode().isPresent());

        PromptOptimizationRpcContracts.StartPayload unconfirmed = new PromptOptimizationRpcContracts.StartPayload(
                fixture.workspace().id(), fixture.role(), ExecutionOverrides.empty(), false, "尚未确认");
        JsonRpcResponse rejected =
                session.handle(request("agent/role/prompt/optimization/start", command("unconfirmed", 0, unconfirmed)));
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS, rejected.error().orElseThrow().code());
    }

    private Fixture createFixture(ModelGateway model, String profileId) throws Exception {
        Path dataRoot = temporaryDirectory.resolve("data-v6");
        components = AppServerBootstrap.create(dataRoot, Clock.systemUTC(), model);
        session = initialized(components);
        Path workspaceRoot = Files.createDirectories(temporaryDirectory.resolve("workspace-" + profileId));
        Workspace workspace = decodeSuccess(
                session.handle(request(
                        "workspace/create",
                        command(
                                "workspace-" + profileId,
                                0,
                                new CoreRpcContracts.WorkspaceCreatePayload("Workspace", workspaceRoot)))),
                Workspace.class);
        AgentRoleRef profile = ProviderRoleRpcFixtures.install(
                session,
                components,
                new ProviderRoleRpcFixtures.Installation(
                        "provider-" + profileId,
                        "test-model",
                        profileId,
                        new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                        Set.of(),
                        new TurnBudget(4_000, 1_000, 2, 0, Duration.ofSeconds(30))));
        return new Fixture(workspace, profile);
    }

    private AppServerSession initialized(AppServerBootstrap.Components value) {
        AppServerSession created = value.newSession();
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("prompt-optimization-test", "6.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(
                created.handle(request("initialize/session", params)).result().isPresent());
        return created;
    }

    private WriteCommand startCommand(String key, Fixture fixture) {
        PromptOptimizationRpcContracts.StartPayload payload = new PromptOptimizationRpcContracts.StartPayload(
                fixture.workspace().id(),
                fixture.role(),
                ExecutionOverrides.empty(),
                true,
                PromptOptimizationRpcContracts.BILLING_CONFIRMATION);
        return command(key, 0, payload);
    }

    private PromptOptimizationDraft read(PromptOptimizationDraft draft) {
        return decodeSuccess(
                session.handle(request(
                        "agent/role/prompt/optimization/read",
                        new PromptOptimizationRpcContracts.ReadPayload(
                                draft.ref().id()))),
                PromptOptimizationDraft.class);
    }

    private void assertUnconfirmedAdoptionRejected(Fixture fixture, PromptOptimizationDraft draft) {
        WriteCommand command = command(
                "adopt-unconfirmed",
                fixture.role().revision(),
                new PromptOptimizationRpcContracts.AdoptPayload(draft.ref().id(), false, "尚未确认"));
        JsonRpcResponse response = session.handle(request("agent/role/prompt/optimization/adopt", command));
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS, response.error().orElseThrow().code());
        assertTrue(read(draft).adoptedRole().isEmpty());
    }

    private PromptOptimizationDraft awaitState(PromptOptimizationDraft draft, PromptOptimizationState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        PromptOptimizationDraft current = draft;
        while (System.nanoTime() < deadline) {
            current = read(current);
            if (current.result().state() == expected) {
                return current;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Prompt optimization did not reach " + expected + ": "
                + current.result().state());
    }

    private PromptOptimizationDraft awaitTerminal(PromptOptimizationDraft draft) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        PromptOptimizationDraft current = draft;
        while (System.nanoTime() < deadline) {
            current = read(current);
            if (current.result().state() != PromptOptimizationState.QUEUED
                    && current.result().state() != PromptOptimizationState.RUNNING) {
                return current;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Prompt optimization did not terminate");
    }

    private WriteCommand command(String key, long revision, Object payload) {
        return new WriteCommand(key, revision, components.json().encode(payload));
    }

    private JsonRpcRequest request(String method, Object params) {
        return new JsonRpcRequest(
                new RpcId(Integer.toString(++requestSequence)),
                method,
                components.json().encode(params));
    }

    private <T> T decodeSuccess(JsonRpcResponse response, Class<T> type) {
        return components
                .json()
                .decode(response.result().orElseThrow(() -> new AssertionError(response.error())), type);
    }

    private record Fixture(Workspace workspace, AgentRoleRef role) {}

    private static final class RecordingModel implements ModelGateway, AutoCloseable {
        private final String output;
        private final AtomicReference<ModelInvocation> lastInvocation = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private RecordingModel(String output) {
            this.output = output;
        }

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
            lastInvocation.set(invocation);
            return result(output);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class FailingModel implements ModelGateway {
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
            throw new IllegalStateException("fake provider failure");
        }
    }

    private static final class BlockingModel implements ModelGateway, AutoCloseable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                com.javaclaw.api.TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                CancellationToken cancellation)
                throws InterruptedException {
            invocations.incrementAndGet();
            started.countDown();
            while (!cancellation.isCancelled()) {
                Thread.sleep(5);
            }
            cancellation.throwIfCancelled();
            return result("unreachable");
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static ModelInvocationResult result(String text) {
        return new ModelInvocationResult(
                text,
                List.of(),
                new ModelUsage(10, 5, 0, 0),
                Optional.empty(),
                Optional.empty(),
                ModelFinishReason.COMPLETE);
    }
}
