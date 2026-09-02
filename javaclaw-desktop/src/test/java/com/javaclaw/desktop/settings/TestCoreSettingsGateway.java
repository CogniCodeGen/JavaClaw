package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.ProviderVerificationUsage;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.protocol.DiagnosticsRpcContracts;

/** 管理中心 Presenter 测试共享的纯内存 SDK 边界。 */
final class TestCoreSettingsGateway implements CoreSettingsGateway {
    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");

    final List<ProviderEndpoint> providers = new ArrayList<>();
    final List<AgentProfile> profiles = new ArrayList<>();
    private final TestPermissionSettings permissionSettings = new TestPermissionSettings();
    final List<PermissionProfile> permissions = permissionSettings.profiles;
    final List<PrivateNetworkGrant> privateNetworkGrants = permissionSettings.privateNetworkGrants;
    final List<UnattendedToolGrantStatus> unattendedToolGrants = permissionSettings.unattendedToolGrants;
    private Optional<CredentialMetadata> credential = Optional.empty();
    private Optional<ProfileBinding> workspaceBinding = Optional.empty();
    private final TestManagedWorktreeSettings worktreeSettings = new TestManagedWorktreeSettings();
    int providerCredentialSetCalls;
    int providerCredentialClearCalls;
    int providerVerificationCalls;
    char[] lastProviderSecret;
    String lastWorkspaceName = "";
    AgentProfileRef lastWorkspaceProfile;
    boolean workspaceArchived;
    RuntimeException nextFailure;

    TestCoreSettingsGateway() {
        providers.add(provider(1, providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE));
    }

    @Override
    public CompletionStage<List<ProviderEndpoint>> providers() {
        return completed(List.copyOf(providers));
    }

    @Override
    public CompletionStage<ProviderEndpoint> createProvider(
            String id, ProviderEndpointSpec spec, CommandOptions options) {
        ProviderEndpoint created = new ProviderEndpoint(id, 1, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
        providers.add(created);
        return completed(created);
    }

    @Override
    public CompletionStage<ProviderEndpoint> updateProvider(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        ProviderEndpoint current = providers.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        ProviderEndpoint updated =
                new ProviderEndpoint(id, current.revision() + 1, lifecycle, spec, current.createdAt(), NOW);
        providers.remove(current);
        providers.add(updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<ProviderEndpoint> archiveProvider(String id, CommandOptions options) {
        ProviderEndpoint current = providers.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        return updateProvider(id, current.spec(), ProviderLifecycle.ARCHIVED, options);
    }

    @Override
    public CompletionStage<ProviderStatus> probeProvider(ProviderRef provider) {
        ProviderEndpoint endpoint = providers.stream()
                .filter(candidate -> candidate.id().equals(provider.endpointId()))
                .findFirst()
                .orElseThrow();
        ProviderReadiness readiness = endpoint.spec().credential().isPresent()
                ? ProviderReadiness.READY
                : ProviderReadiness.CREDENTIAL_REQUIRED;
        return completed(new ProviderStatus(
                provider,
                readiness,
                new ProviderCapabilities(Set.of(ProviderRole.CHAT), true, true, true, false, false, false, false),
                Optional.empty(),
                NOW));
    }

    @Override
    public CompletionStage<ProviderVerificationResult> verifyProviderRoundTrip(
            ProviderRef provider, boolean billingConfirmed, String confirmation, CommandOptions options) {
        providerVerificationCalls++;
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        return completed(new ProviderVerificationResult(
                provider,
                ProviderVerificationState.SUCCEEDED,
                12,
                Optional.of(new ProviderVerificationUsage(2, 1, 0, 0)),
                new ProviderCapabilities(Set.of(ProviderRole.CHAT), true, true, true, false, false, false, false),
                Optional.empty(),
                NOW));
    }

    @Override
    public CompletionStage<ProviderCredentialBinding> setProviderCredential(
            ProviderEndpoint provider, long credentialExpectedRevision, char[] secret, CommandOptions options) {
        providerCredentialSetCalls++;
        lastProviderSecret = secret;
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        CredentialRef reference =
                provider.spec().credential().orElseGet(() -> new CredentialRef("provider", "credential-1"));
        CredentialMetadata metadata = new CredentialMetadata(reference, credentialExpectedRevision + 1, NOW);
        ProviderEndpointSpec spec = providerSpec(Optional.of(reference));
        ProviderEndpoint updated =
                new ProviderEndpoint(provider.id(), provider.revision() + 1, provider.lifecycle(), spec, NOW, NOW);
        providers.remove(provider);
        providers.add(updated);
        credential = Optional.of(metadata);
        return completed(new ProviderCredentialBinding(updated, metadata));
    }

    @Override
    public CompletionStage<ProviderCredentialClearResult> clearProviderCredential(
            ProviderEndpoint provider, CredentialMetadata metadata, CommandOptions options) {
        providerCredentialClearCalls++;
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        ProviderEndpoint updated = new ProviderEndpoint(
                provider.id(), provider.revision() + 1, provider.lifecycle(), providerSpec(Optional.empty()), NOW, NOW);
        providers.remove(provider);
        providers.add(updated);
        credential = Optional.empty();
        CredentialClearReceipt receipt = new CredentialClearReceipt(metadata.reference(), metadata.revision(), NOW);
        return completed(new ProviderCredentialClearResult(updated, receipt));
    }

    @Override
    public CompletionStage<List<AgentProfile>> profiles() {
        return completed(List.copyOf(profiles));
    }

    @Override
    public CompletionStage<AgentProfile> createProfile(String id, AgentProfileSpec spec, CommandOptions options) {
        AgentProfile created = new AgentProfile(id, 1, ProfileLifecycle.ACTIVE, spec, NOW, NOW);
        profiles.add(created);
        return completed(created);
    }

    @Override
    public CompletionStage<AgentProfile> updateProfile(
            String id, AgentProfileSpec spec, ProfileLifecycle lifecycle, CommandOptions options) {
        AgentProfile current = profiles.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        AgentProfile updated = new AgentProfile(id, current.revision() + 1, lifecycle, spec, current.createdAt(), NOW);
        profiles.remove(current);
        profiles.add(updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<AgentProfile> archiveProfile(String id, CommandOptions options) {
        AgentProfile current = profiles.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
        return updateProfile(id, current.spec(), ProfileLifecycle.ARCHIVED, options);
    }

    @Override
    public CompletionStage<List<PermissionProfile>> permissionProfiles() {
        return completed(List.copyOf(permissions));
    }

    @Override
    public CompletionStage<List<PermissionProfile>> permissionProfileHistory(String id) {
        return completed(permissionSettings.history(id));
    }

    @Override
    public CompletionStage<PermissionProfileDiff> permissionProfileDiff(
            String id, long beforeVersion, long afterVersion) {
        return completed(permissionSettings.diff(id, beforeVersion, afterVersion));
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return completed(List.of(DesktopTestFixtures.workspace()));
    }

    @Override
    public CompletionStage<Workspace> renameWorkspace(Workspace workspace, String name, CommandOptions options) {
        lastWorkspaceName = name;
        return completed(new Workspace(
                workspace.id(),
                name,
                workspace.root(),
                workspace.lifecycle(),
                workspace.revision() + 1,
                workspace.createdAt(),
                NOW));
    }

    @Override
    public CompletionStage<Workspace> archiveWorkspace(Workspace workspace, CommandOptions options) {
        workspaceArchived = true;
        return completed(new Workspace(
                workspace.id(),
                workspace.name(),
                workspace.root(),
                WorkspaceLifecycle.ARCHIVED,
                workspace.revision() + 1,
                workspace.createdAt(),
                NOW));
    }

    @Override
    public CompletionStage<Optional<ProfileBinding>> workspaceProfileBinding(WorkspaceId workspaceId) {
        return completed(workspaceBinding);
    }

    @Override
    public CompletionStage<ProfileBinding> bindWorkspaceProfile(
            WorkspaceId workspaceId, AgentProfileRef profile, CommandOptions options) {
        lastWorkspaceProfile = profile;
        ProfileBinding binding =
                new ProfileBinding(workspaceId, Optional.empty(), profile, options.expectedRevision() + 1, NOW);
        workspaceBinding = Optional.of(binding);
        return completed(binding);
    }

    @Override
    public CompletionStage<List<ManagedWorktree>> managedWorktrees(WorkspaceId workspaceId, boolean includeCleaned) {
        return completed(worktreeSettings.list(workspaceId, includeCleaned));
    }

    @Override
    public CompletionStage<ManagedWorktree> interruptManagedWorktree(
            ManagedWorktree worktree, String reason, CommandOptions options) {
        return completed(worktreeSettings.interrupt(worktree));
    }

    @Override
    public CompletionStage<ManagedWorktreeArtifact> exportManagedWorktreePatch(
            ManagedWorktree worktree, CommandOptions options) {
        return completed(worktreeSettings.exportPatch(worktree));
    }

    @Override
    public CompletionStage<ManagedWorktreeArtifact> backupManagedWorktree(
            ManagedWorktree worktree, CommandOptions options) {
        return completed(worktreeSettings.backup(worktree));
    }

    @Override
    public CompletionStage<ManagedWorktree> cleanupManagedWorktree(
            ManagedWorktree worktree, String confirmation, CommandOptions options) {
        return completed(worktreeSettings.cleanup(worktree, confirmation));
    }

    @Override
    public CompletionStage<ConversationThread> navigateToThread(ThreadId threadId) {
        return completed(worktreeSettings.navigate(threadId));
    }

    @Override
    public CompletionStage<PermissionProfile> clonePermissionProfile(
            PermissionProfileRef source, String newId, CommandOptions options) {
        return completed(permissionSettings.cloneProfile(source, newId));
    }

    @Override
    public CompletionStage<PermissionProfile> updatePermissionProfile(
            PermissionProfile profile, CommandOptions options) {
        return completed(permissionSettings.update(profile));
    }

    @Override
    public CompletionStage<EffectivePermissionPreview> effectivePermissionPreview(
            WorkspaceId workspaceId,
            PermissionProfileRef profile,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        return completed(permissionSettings.preview(profile, turnGrant, toolDeclaration));
    }

    @Override
    public CompletionStage<PrivateNetworkGrantPreview> previewPrivateNetworkGrant(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Optional<Duration> validity) {
        return completed(
                permissionSettings.previewPrivateNetwork(workspaceId, purpose, origin, dnsAddresses, validity));
    }

    @Override
    public CompletionStage<List<PrivateNetworkGrant>> privateNetworkGrants(WorkspaceId workspaceId) {
        return completed(privateNetworkGrants.stream()
                .filter(grant -> grant.workspaceId().equals(workspaceId))
                .toList());
    }

    @Override
    public CompletionStage<PrivateNetworkGrant> createPrivateNetworkGrant(
            PrivateNetworkGrantPreview preview, CommandOptions options) {
        return completed(permissionSettings.createPrivateNetwork(preview));
    }

    @Override
    public CompletionStage<PrivateNetworkGrant> revokePrivateNetworkGrant(
            PrivateNetworkGrant grant, CommandOptions options) {
        return completed(permissionSettings.revokePrivateNetwork(grant));
    }

    @Override
    public CompletionStage<List<UnattendedToolGrantStatus>> unattendedToolGrants(WorkspaceId workspaceId) {
        return completed(unattendedToolGrants.stream()
                .filter(status -> status.grant().workspaceId().equals(workspaceId))
                .toList());
    }

    @Override
    public CompletionStage<UnattendedToolGrant> createUnattendedToolGrant(
            UnattendedToolGrantDraft draft, CommandOptions options) {
        return completed(permissionSettings.createUnattended(draft));
    }

    @Override
    public CompletionStage<UnattendedToolGrant> revokeUnattendedToolGrant(
            UnattendedToolGrant grant, CommandOptions options) {
        return completed(permissionSettings.revokeUnattended(grant));
    }

    @Override
    public CompletionStage<List<PermissionDecisionTrace>> permissionDecisions(
            WorkspaceId workspaceId, SecurityGrantKind kind, Optional<String> grantId, int limit) {
        return completed(List.of());
    }

    @Override
    public CompletionStage<VaultStatus> vaultStatus() {
        return completed(new VaultStatus(
                VaultState.READY, VaultLockReason.NONE, credential.stream().count(), false, NOW));
    }

    @Override
    public CompletionStage<VaultStatus> refreshVault() {
        return vaultStatus();
    }

    @Override
    public CompletionStage<VaultManagementReceipt> rotateVaultMasterKey(CommandOptions options) {
        return completed(new VaultManagementReceipt(
                VaultManagementAction.MASTER_KEY_ROTATED, credential.stream().count(), NOW));
    }

    @Override
    public CompletionStage<VaultManagementReceipt> resetVault(String confirmation, CommandOptions options) {
        long count = credential.stream().count();
        credential = Optional.empty();
        return completed(new VaultManagementReceipt(VaultManagementAction.VAULT_RESET, count, NOW));
    }

    @Override
    public CompletionStage<Optional<CredentialMetadata>> credential(CredentialRef reference) {
        return completed(credential.filter(metadata -> metadata.reference().equals(reference)));
    }

    @Override
    public CompletionStage<List<CredentialMetadata>> credentials(String namespace) {
        return completed(credential.stream()
                .filter(metadata -> metadata.reference().namespace().equals(namespace))
                .toList());
    }

    @Override
    public CompletionStage<CredentialMetadata> createCredential(
            String namespace, char[] secret, CommandOptions options) {
        CredentialMetadata created = new CredentialMetadata(new CredentialRef(namespace, "credential-1"), 1, NOW);
        credential = Optional.of(created);
        return completed(created);
    }

    @Override
    public CompletionStage<CredentialMetadata> rotateCredential(
            CredentialRef reference, char[] secret, CommandOptions options) {
        CredentialMetadata rotated =
                new CredentialMetadata(reference, credential.orElseThrow().revision() + 1, NOW);
        credential = Optional.of(rotated);
        return completed(rotated);
    }

    @Override
    public CompletionStage<CredentialClearReceipt> clearCredential(CredentialRef reference, CommandOptions options) {
        long revision = credential.orElseThrow().revision();
        credential = Optional.empty();
        return completed(new CredentialClearReceipt(reference, revision, NOW));
    }

    @Override
    public CompletionStage<ConnectionSummary> connection() {
        return completed(new ConnectionSummary(
                "javaclaw-app-server", "5.0.0-SNAPSHOT", 2, Set.of("core.item-envelope"), Set.of()));
    }

    @Override
    public CompletionStage<ConnectionSummary> reconnect() {
        return connection();
    }

    @Override
    public CompletionStage<DiagnosticsSnapshot> diagnostics() {
        DiagnosticsSnapshot.BuildIdentity build = new DiagnosticsSnapshot.BuildIdentity("5.0.0-SNAPSHOT", 2, 1);
        DiagnosticsSnapshot.RuntimeHealth health =
                new DiagnosticsSnapshot.RuntimeHealth(true, 1, 9, 1, 0, "test", "25");
        DiagnosticsSnapshot.SubsystemHealth subsystems = new DiagnosticsSnapshot.SubsystemHealth(
                new DiagnosticsSnapshot.ProviderVaultHealth(1, 1, com.javaclaw.api.VaultState.READY, 0),
                new DiagnosticsSnapshot.ExtensionHealth(9, 9, 0, 0, 0),
                new DiagnosticsSnapshot.IntegrationHealth(0, 0, 0, false, false, false),
                new DiagnosticsSnapshot.JobHealth(0, 0, 0, 0),
                new DiagnosticsSnapshot.ScheduleHealth(false, false, 1, true, false, Optional.empty()),
                new DiagnosticsSnapshot.LauncherHealth(
                        false, false, false, Optional.of("IDEA 调试未配置 launcher supervisor")));
        return completed(new DiagnosticsSnapshot(build, health, subsystems, NOW, NOW));
    }

    @Override
    public CompletionStage<DiagnosticsRpcContracts.LauncherStatus> launcherStatus() {
        return completed(new DiagnosticsRpcContracts.LauncherStatus(
                false, false, false, Optional.of("IDEA 调试未配置 launcher supervisor")));
    }

    @Override
    public CompletionStage<DiagnosticsRpcContracts.ServerStopResult> stopServer(CommandOptions options) {
        return completed(new DiagnosticsRpcContracts.ServerStopResult(
                false, 1, 0, Optional.of("IDEA 调试未配置 launcher supervisor")));
    }

    @Override
    public CompletionStage<DiagnosticsSnapshot> repairLoginStartup(CommandOptions options) {
        return diagnostics();
    }

    private static ProviderEndpoint provider(long revision, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        return new ProviderEndpoint("provider-main", revision, lifecycle, spec, NOW, NOW);
    }

    private static ProviderEndpointSpec providerSpec(Optional<CredentialRef> credential) {
        return new ProviderEndpointSpec(
                "Local fake",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://models.example.test/v1")),
                Set.of(ProviderRole.CHAT),
                List.of("fake-model"),
                credential,
                Duration.ofSeconds(30),
                0,
                Map.of());
    }

    static AgentProfileSpec profileSpec() {
        ProviderEndpoint provider = provider(1, providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE);
        return new AgentProfileSpec(
                "Workspace Profile",
                "使用 Workspace 默认配置。",
                new ProviderRef(
                        provider.id(),
                        provider.revision(),
                        provider.spec().models().getFirst()),
                new PermissionProfileRef("standard", 1),
                Set.of("core/tool/search"),
                new com.javaclaw.api.TurnBudget(8_000, 2_000, 6, 1, Duration.ofSeconds(120)));
    }

    private RuntimeException takeFailure() {
        RuntimeException failure = nextFailure;
        nextFailure = null;
        return failure;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }
}
