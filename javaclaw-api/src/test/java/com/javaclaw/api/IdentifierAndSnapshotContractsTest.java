package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifierAndSnapshotContractsTest {
    @Test
    void identifiersRoundTripAndRejectNullValues() {
        UUID value = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertIdentifier(
                value,
                WorkspaceId.parse(value.toString()).toString(),
                WorkspaceId.random().toString());
        assertIdentifier(
                value,
                ThreadId.parse(value.toString()).toString(),
                ThreadId.random().toString());
        assertIdentifier(
                value,
                TurnId.parse(value.toString()).toString(),
                TurnId.random().toString());
        assertIdentifier(
                value,
                ItemId.parse(value.toString()).toString(),
                ItemId.random().toString());
        assertEquals(value.toString(), WorktreeId.parse(value.toString()).toString());
        assertThrows(NullPointerException.class, () -> new WorkspaceId(null));
        assertThrows(NullPointerException.class, () -> new ThreadId(null));
        assertThrows(NullPointerException.class, () -> new TurnId(null));
        assertThrows(NullPointerException.class, () -> new ItemId(null));
        assertThrows(NullPointerException.class, () -> new WorktreeId(null));
    }

    @Test
    void workspaceAndThreadEnforceTemporalAndParentInvariants() {
        WorkspaceId workspaceId = WorkspaceId.random();
        ThreadId threadId = ThreadId.random();
        Instant later = ApiFixtures.NOW.plusSeconds(1);
        Workspace workspace = new Workspace(
                workspaceId, " Project ", Path.of("."), WorkspaceLifecycle.ACTIVE, 1, ApiFixtures.NOW, later);
        assertEquals(WorkspaceLifecycle.ACTIVE, workspace.lifecycle());
        ConversationThread root = new ConversationThread(
                threadId,
                workspaceId,
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                " Thread ",
                ThreadStatus.ACTIVE,
                1,
                ApiFixtures.NOW,
                later);
        ConversationThread child = new ConversationThread(
                ThreadId.random(),
                workspaceId,
                Optional.of(threadId),
                ThreadExecutionIntent.READ_ONLY,
                "Child",
                ThreadStatus.ARCHIVED,
                2,
                ApiFixtures.NOW,
                later);
        assertTrue(workspace.root().isAbsolute());
        assertEquals("Project", workspace.name());
        assertTrue(root.parentThreadId().isEmpty());
        assertEquals(threadId, child.parentThreadId().orElseThrow());
    }

    @Test
    void agentTurnNormalizesOptionalErrorAndExecutionRoot() {
        ThreadId threadId = ThreadId.random();
        Instant later = ApiFixtures.NOW.plusSeconds(1);
        AgentTurn turn = new AgentTurn(
                TurnId.random(),
                threadId,
                TurnStatus.RUNNING,
                1,
                ApiFixtures.budget(),
                profileRef(),
                providerRef(),
                permissionRef(),
                Path.of("."),
                ApiFixtures.DIGEST,
                "b".repeat(64),
                Optional.of("  "),
                ApiFixtures.NOW,
                later,
                ApiFixtures.config("b".repeat(64)).summary());

        assertTrue(turn.errorCode().isEmpty());
        assertEquals(
                "failed",
                new AgentTurn(
                                TurnId.random(),
                                threadId,
                                TurnStatus.FAILED,
                                2,
                                ApiFixtures.budget(),
                                profileRef(),
                                providerRef(),
                                permissionRef(),
                                Path.of("."),
                                ApiFixtures.DIGEST,
                                "b".repeat(64),
                                Optional.of(" failed "),
                                ApiFixtures.NOW,
                                later,
                                ApiFixtures.config("b".repeat(64)).summary())
                        .errorCode()
                        .orElseThrow());
    }

    @Test
    void workspaceThreadAndTurnRejectInvalidTemporalAndParentState() {
        WorkspaceId workspaceId = WorkspaceId.random();
        ThreadId threadId = ThreadId.random();
        Instant later = ApiFixtures.NOW.plusSeconds(1);

        assertThrows(
                IllegalArgumentException.class,
                () -> new Workspace(
                        workspaceId, "x", Path.of("."), WorkspaceLifecycle.ACTIVE, 1, later, ApiFixtures.NOW));
        assertThrows(
                NullPointerException.class,
                () -> new Workspace(workspaceId, "x", Path.of("."), null, 1, ApiFixtures.NOW, later));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationThread(
                        threadId,
                        workspaceId,
                        Optional.of(threadId),
                        ThreadExecutionIntent.READ_ONLY,
                        "x",
                        ThreadStatus.ACTIVE,
                        1,
                        ApiFixtures.NOW,
                        later));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationThread(
                        threadId,
                        workspaceId,
                        Optional.empty(),
                        ThreadExecutionIntent.WORKSPACE,
                        "x",
                        ThreadStatus.ACTIVE,
                        1,
                        later,
                        ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTurn(
                        TurnId.random(),
                        threadId,
                        TurnStatus.RUNNING,
                        1,
                        ApiFixtures.budget(),
                        profileRef(),
                        providerRef(),
                        permissionRef(),
                        Path.of("."),
                        ApiFixtures.DIGEST,
                        "b".repeat(64),
                        Optional.empty(),
                        later,
                        ApiFixtures.NOW,
                        ApiFixtures.config("b".repeat(64)).summary()));
    }

    @Test
    void attachmentContractsOwnBytesAndValidateContentAddress() {
        AttachmentRef reference = new AttachmentRef(ApiFixtures.DIGEST.toUpperCase(), "text/plain", "a.txt", 2);
        AttachmentMetadata metadata =
                new AttachmentMetadata(ApiFixtures.DIGEST.toUpperCase(), "text/plain", 2, ApiFixtures.NOW);
        byte[] bytes = {1, 2};
        AttachmentContent content = new AttachmentContent(metadata, bytes);
        bytes[0] = 9;

        assertEquals(ApiFixtures.DIGEST, reference.digest());
        assertEquals(ApiFixtures.DIGEST, metadata.digest());
        assertArrayEquals(new byte[] {1, 2}, content.content());
        byte[] returned = content.content();
        returned[0] = 8;
        assertArrayEquals(new byte[] {1, 2}, content.content());
        assertThrows(IllegalArgumentException.class, () -> new AttachmentRef("bad", "text/plain", "a.txt", 0));
        assertThrows(
                IllegalArgumentException.class, () -> new AttachmentMetadata("bad", "text/plain", 0, ApiFixtures.NOW));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentContent(metadata, new byte[] {1}));
    }

    @Test
    void configurationManagedWorktreeRolloutAndDiagnosticsValidateSnapshots() {
        WorkspaceId workspaceId = WorkspaceId.random();
        ProviderEndpoint provider = new ProviderEndpoint(
                "provider.openai", 1, ProviderLifecycle.ACTIVE, providerSpec(), ApiFixtures.NOW, ApiFixtures.NOW);
        ManagedWorktree worktree = managedWorktree(workspaceId);
        RolloutManifest manifest = new RolloutManifest(
                ThreadId.random(), 1, 0, 0, "0".repeat(64), ApiFixtures.DIGEST.toUpperCase(), ApiFixtures.NOW);
        RolloutSnapshot rollout = new RolloutSnapshot(manifest, List.of());
        DiagnosticsSnapshot.BuildIdentity build = new DiagnosticsSnapshot.BuildIdentity("5.0.0", 2, 1);
        DiagnosticsSnapshot.RuntimeHealth health =
                new DiagnosticsSnapshot.RuntimeHealth(true, 1, 2, 3, 4, "macOS", "25");
        DiagnosticsSnapshot.SubsystemHealth subsystems = diagnosticSubsystems();
        DiagnosticsSnapshot diagnostics =
                new DiagnosticsSnapshot(build, health, subsystems, ApiFixtures.NOW, ApiFixtures.NOW.plusSeconds(1));

        assertEquals("provider.openai", provider.id());
        assertTrue(worktree.executionRoot().isAbsolute());
        assertEquals(ManagedWorktreeState.READY, worktree.state());
        assertEquals(ApiFixtures.DIGEST, rollout.manifest().totalSha256());
        assertTrue(rollout.items().isEmpty());
        assertTrue(diagnostics.health().databaseHealthy());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderEndpoint(
                        "bad id", 1, ProviderLifecycle.ACTIVE, providerSpec(), ApiFixtures.NOW, ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RolloutManifest(
                        ThreadId.random(), 1, -1, 0, ApiFixtures.DIGEST, ApiFixtures.DIGEST, ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RolloutManifest(
                        ThreadId.random(), 1, 0, -1, ApiFixtures.DIGEST, ApiFixtures.DIGEST, ApiFixtures.NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RolloutManifest(ThreadId.random(), 1, 0, 0, "bad", ApiFixtures.DIGEST, ApiFixtures.NOW));
    }

    @Test
    void diagnosticsRejectInvalidCountsStateCombinationsAndTimeOrder() {
        DiagnosticsSnapshot.BuildIdentity build = new DiagnosticsSnapshot.BuildIdentity("5.0.0", 2, 1);
        DiagnosticsSnapshot.RuntimeHealth health =
                new DiagnosticsSnapshot.RuntimeHealth(true, 1, 2, 3, 4, "macOS", "25");
        DiagnosticsSnapshot.SubsystemHealth subsystems = diagnosticSubsystems();

        assertThrows(IllegalArgumentException.class, () -> new DiagnosticsSnapshot.BuildIdentity("5", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticsSnapshot.BuildIdentity("5", 1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSnapshot.RuntimeHealth(true, -1, 0, 0, 0, "macOS", "25"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSnapshot.RuntimeHealth(true, 0, -1, 0, 0, "macOS", "25"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSnapshot.RuntimeHealth(true, 0, 0, -1, 0, "macOS", "25"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSnapshot.RuntimeHealth(true, 0, 0, 0, -1, "macOS", "25"));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticsSnapshot.ExtensionHealth(1, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticsSnapshot.ExtensionHealth(1, 1, 0, 0, 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSnapshot.LauncherHealth(true, false, true, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSnapshot.LauncherHealth(false, true, false, Optional.of("配置缺失")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosticsSnapshot(
                        build, health, subsystems, ApiFixtures.NOW, ApiFixtures.NOW.minusSeconds(1)));
    }

    private static DiagnosticsSnapshot.SubsystemHealth diagnosticSubsystems() {
        return new DiagnosticsSnapshot.SubsystemHealth(
                new DiagnosticsSnapshot.ProviderVaultHealth(2, 1, VaultState.READY, 3),
                new DiagnosticsSnapshot.ExtensionHealth(2, 1, 1, 0, 1),
                new DiagnosticsSnapshot.IntegrationHealth(2, 1, 0, true, true, true),
                new DiagnosticsSnapshot.JobHealth(1, 2, 3, 4),
                new DiagnosticsSnapshot.ScheduleHealth(false, false, 1, true, false, Optional.empty()),
                new DiagnosticsSnapshot.LauncherHealth(false, false, false, Optional.of("IDEA 直接运行")));
    }

    private static ManagedWorktree managedWorktree(WorkspaceId workspaceId) {
        return new ManagedWorktree(
                WorktreeId.parse("00000000-0000-0000-0000-000000000002"),
                workspaceId,
                ThreadId.random(),
                ThreadId.random(),
                Path.of("worktree"),
                "a".repeat(40),
                ManagedWorktreeState.READY,
                1,
                Optional.empty(),
                ApiFixtures.NOW,
                ApiFixtures.NOW);
    }

    @Test
    void allLifecycleEnumsExposeTheirDeclaredStates() {
        assertNotEquals(0, MessageRole.values().length);
        assertNotEquals(0, ThreadStatus.values().length);
        assertNotEquals(0, TurnStatus.values().length);
        assertNotEquals(0, ItemStatus.values().length);
        assertNotEquals(0, ApprovalState.values().length);
        assertNotEquals(0, ApprovalDecision.values().length);
        assertNotEquals(0, ApprovalRequirement.values().length);
        assertNotEquals(0, ToolRisk.values().length);
        assertNotEquals(0, SandboxMode.values().length);
        assertNotEquals(0, SandboxSignal.values().length);
        assertFalse(CoreSchemas.MESSAGE.isBlank());
        assertFalse(CoreSchemas.ERROR.isBlank());
    }

    private static void assertIdentifier(UUID expected, String parsed, String random) {
        assertEquals(expected.toString(), parsed);
        assertFalse(random.isBlank());
    }

    private static AgentRoleRef profileRef() {
        return new AgentRoleRef("default", 1);
    }

    private static ProviderRef providerRef() {
        return new ProviderRef("provider", 1, "model");
    }

    private static PermissionProfileRef permissionRef() {
        return new PermissionProfileRef("standard", 1);
    }

    private static ProviderEndpointSpec providerSpec() {
        return new ProviderEndpointSpec(
                "OpenAI",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "model", "model", Set.of(ProviderModelPurpose.CHAT), java.util.OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }
}
