package com.javaclaw.desktop.settings;

import java.nio.file.Path;
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

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.PromptOptimizationProvenance;
import com.javaclaw.api.PromptOptimizationRef;
import com.javaclaw.api.PromptOptimizationResult;
import com.javaclaw.api.PromptOptimizationState;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadId;
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
    void 固定Workspace可在独立目录尚未加载时直接绑定() {
        FakeGateway gateway = new FakeGateway();
        PromptOptimizationSettingsPresenter presenter = new PromptOptimizationSettingsPresenter(gateway, () -> {});

        presenter.selectWorkspace(gateway.workspace);

        assertEquals(
                gateway.workspace, presenter.state().selection().workspace().orElseThrow());
        assertEquals(List.of(gateway.workspace), presenter.state().selection().workspaces());
    }

    @Test
    void explicitConfirmationsGateStartAndAdoptionConflictPreservesReadyDraft() {
        FakeGateway gateway = new FakeGateway();
        AtomicBoolean adoptedCallback = new AtomicBoolean();
        PromptOptimizationSettingsPresenter presenter =
                new PromptOptimizationSettingsPresenter(gateway, () -> adoptedCallback.set(true));
        AtomicReference<PromptOptimizationSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.activate();
        presenter.selectRole(Optional.of(gateway.role));

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
        assertTrue(
                latest.get().selection().selected().orElseThrow().adoptedRole().isEmpty());

        gateway.conflict = false;
        presenter.adopt(true, PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION);
        assertTrue(adoptedCallback.get());
        assertEquals(
                2,
                latest.get()
                        .selection()
                        .selected()
                        .orElseThrow()
                        .adoptedRole()
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
        private final AgentRole role = role();
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
                AgentRoleRef profileRef,
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
                        ProtocolErrorCode.REVISION_CONFLICT, "Agent Role revision 已改变", Optional.empty())));
            }
            AgentRole updated = new AgentRole(
                    role.id(), 2, RoleLifecycle.ACTIVE, role.spec(), false, role.createdAt(), NOW.plusSeconds(1));
            PromptOptimizationDraft adopted =
                    draft(PromptOptimizationState.READY, Optional.of(new AgentRoleRef(role.id(), 2)));
            return CompletableFuture.completedFuture(new PromptOptimizationAdoption(adopted, updated));
        }

        private PromptOptimizationDraft draft(PromptOptimizationState state, Optional<AgentRoleRef> adoptedRole) {
            Optional<String> content =
                    state == PromptOptimizationState.READY ? Optional.of("优化后的说明") : Optional.empty();
            Optional<String> digest = state == PromptOptimizationState.READY ? Optional.of(DIGEST) : Optional.empty();
            PromptOptimizationRef ref = new PromptOptimizationRef(
                    PromptOptimizationId.parse("00000000-0000-0000-0000-000000000002"),
                    workspace.id(),
                    new AgentRoleRef(role.id(), role.revision()),
                    ThreadId.parse("00000000-0000-0000-0000-000000000003"),
                    TurnId.parse("00000000-0000-0000-0000-000000000004"));
            return new PromptOptimizationDraft(
                    ref,
                    new PromptOptimizationResult(state, 2, content, digest, Optional.empty()),
                    new PromptOptimizationProvenance("role-optimization-v1", DIGEST, NOW, NOW),
                    adoptedRole);
        }

        private static AgentRole role() {
            AgentRoleSpec spec = new AgentRoleSpec(
                    "Profile",
                    "角色测试",
                    "原始说明",
                    Optional.of(new com.javaclaw.api.ModelPreference(new ProviderRef("provider", 1, "model"))),
                    Optional.empty(),
                    new com.javaclaw.api.CapabilityNarrowing(Optional.of(Set.of()), Optional.empty()),
                    com.javaclaw.api.PermissionConstraint.INHERIT,
                    java.util.Map.of());
            return new AgentRole("role", 1, RoleLifecycle.ACTIVE, spec, false, NOW, NOW);
        }
    }
}
