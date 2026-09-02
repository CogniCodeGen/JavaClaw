package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.PromptOptimizationProvenance;
import com.javaclaw.api.PromptOptimizationRef;
import com.javaclaw.api.PromptOptimizationResult;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptOptimizationSettingsPresenterTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void explicitConfirmationsGateStartAndAdoptionConflictPreservesReadyDraft() {
        FakeGateway gateway = new FakeGateway();
        AtomicBoolean adoptedCallback = new AtomicBoolean();
        PromptOptimizationSettingsPresenter presenter =
                new PromptOptimizationSettingsPresenter(gateway, () -> adoptedCallback.set(true));
        AtomicReference<PromptOptimizationSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.activate();
        presenter.selectProfile(Optional.of(gateway.profile));

        assertEquals(
                PromptOptimizationState.READY,
                latest.get().selection().selected().orElseThrow().result().state());
        presenter.start(true, "错误确认");
        assertEquals(0, gateway.startCalls.get());
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        presenter.start(true, PromptOptimizationRpcContracts.BILLING_CONFIRMATION);
        assertEquals(1, gateway.startCalls.get());

        presenter.refresh();
        presenter.adopt(true, "错误确认");
        assertEquals(0, gateway.adoptCalls.get());
        gateway.conflict = true;
        presenter.adopt(true, PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION);
        assertTrue(latest.get().revisionConflict());
        assertEquals(
                "优化后的说明",
                latest.get()
                        .selection()
                        .selected()
                        .orElseThrow()
                        .result()
                        .content()
                        .orElseThrow());
        assertTrue(latest.get()
                .selection()
                .selected()
                .orElseThrow()
                .adoptedProfile()
                .isEmpty());

        gateway.conflict = false;
        presenter.adopt(true, PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION);
        assertTrue(adoptedCallback.get());
        assertEquals(
                2,
                latest.get()
                        .selection()
                        .selected()
                        .orElseThrow()
                        .adoptedProfile()
                        .orElseThrow()
                        .revision());
    }

    private static final class FakeGateway implements PromptOptimizationSettingsGateway {
        private final Workspace workspace = new Workspace(
                WorkspaceId.parse("00000000-0000-0000-0000-000000000001"),
                "Workspace",
                Path.of("/tmp/prompt-optimization-workspace"),
                WorkspaceLifecycle.ACTIVE,
                1,
                NOW,
                NOW);
        private final AgentProfile profile = profile();
        private final PromptOptimizationDraft ready = draft(PromptOptimizationState.READY, Optional.empty());
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger adoptCalls = new AtomicInteger();
        private boolean conflict;

        @Override
        public CompletionStage<List<Workspace>> workspaces() {
            return CompletableFuture.completedFuture(List.of(workspace));
        }

        @Override
        public CompletionStage<PromptOptimizationDraft> start(
                WorkspaceId workspaceId,
                AgentProfileRef profileRef,
                boolean billingConfirmed,
                String confirmation,
                CommandOptions options) {
            startCalls.incrementAndGet();
            return CompletableFuture.completedFuture(draft(PromptOptimizationState.QUEUED, Optional.empty()));
        }

        @Override
        public CompletionStage<List<PromptOptimizationDraft>> list(WorkspaceId workspaceId) {
            return CompletableFuture.completedFuture(List.of(ready));
        }

        @Override
        public CompletionStage<PromptOptimizationDraft> read(PromptOptimizationId id) {
            return CompletableFuture.completedFuture(ready);
        }

        @Override
        public CompletionStage<PromptOptimizationDraft> cancel(
                PromptOptimizationId id, String reason, CommandOptions options) {
            return CompletableFuture.completedFuture(draft(PromptOptimizationState.CANCELLED, Optional.empty()));
        }

        @Override
        public CompletionStage<PromptOptimizationAdoption> adopt(
                PromptOptimizationId id, boolean adoptionConfirmed, String confirmation, CommandOptions options) {
            adoptCalls.incrementAndGet();
            if (conflict) {
                return CompletableFuture.failedFuture(new RemoteRpcException(new JsonRpcError(
                        ProtocolErrorCode.REVISION_CONFLICT, "Agent Profile revision 已改变", Optional.empty())));
            }
            AgentProfile updated = new AgentProfile(
                    profile.id(), 2, ProfileLifecycle.ACTIVE, profile.spec(), profile.createdAt(), NOW.plusSeconds(1));
            PromptOptimizationDraft adopted =
                    draft(PromptOptimizationState.READY, Optional.of(new AgentProfileRef(profile.id(), 2)));
            return CompletableFuture.completedFuture(new PromptOptimizationAdoption(adopted, updated));
        }

        private PromptOptimizationDraft draft(PromptOptimizationState state, Optional<AgentProfileRef> adoptedProfile) {
            Optional<String> content =
                    state == PromptOptimizationState.READY ? Optional.of("优化后的说明") : Optional.empty();
            Optional<String> digest = state == PromptOptimizationState.READY ? Optional.of(DIGEST) : Optional.empty();
            PromptOptimizationRef ref = new PromptOptimizationRef(
                    PromptOptimizationId.parse("00000000-0000-0000-0000-000000000002"),
                    workspace.id(),
                    new AgentProfileRef(profile.id(), profile.revision()),
                    ThreadId.parse("00000000-0000-0000-0000-000000000003"),
                    TurnId.parse("00000000-0000-0000-0000-000000000004"));
            return new PromptOptimizationDraft(
                    ref,
                    new PromptOptimizationResult(state, 2, content, digest, Optional.empty()),
                    new PromptOptimizationProvenance("profile-optimization-v1", DIGEST, NOW, NOW),
                    adoptedProfile);
        }

        private static AgentProfile profile() {
            AgentProfileSpec spec = new AgentProfileSpec(
                    "Profile",
                    "原始说明",
                    new ProviderRef("provider", 1, "model"),
                    new PermissionProfileRef("standard", 1),
                    Set.of(),
                    new TurnBudget(4_000, 1_000, 2, 0, Duration.ofSeconds(30)));
            return new AgentProfile("profile", 1, ProfileLifecycle.ACTIVE, spec, NOW, NOW);
        }
    }
}
