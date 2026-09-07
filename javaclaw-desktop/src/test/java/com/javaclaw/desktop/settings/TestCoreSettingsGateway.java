package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolDescriptor;
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
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.DiagnosticsRpcContracts;

/** 管理中心 Presenter 测试共享的纯内存 SDK 边界。 */
class TestCoreSettingsGateway extends TestRoleExecutionSettingsGateway {
    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");

    final List<ProviderEndpoint> providers = new ArrayList<>();
    final java.util.ArrayDeque<CompletableFuture<ProviderModelDiscoveryResult>> discoveryResponses = new ArrayDeque<>();
    final List<CancellationToken> discoveryCancellations = new ArrayList<>();
    private final TestPermissionSettings permissionSettings = new TestPermissionSettings();
    final List<PermissionProfile> permissions = permissionSettings.profiles;
    final List<PrivateNetworkGrant> privateNetworkGrants = permissionSettings.privateNetworkGrants;
    final List<UnattendedToolGrantStatus> unattendedToolGrants = permissionSettings.unattendedToolGrants;
    private Optional<CredentialMetadata> credential = Optional.empty();
    private Optional<EmbeddingBinding> embeddingBinding = Optional.empty();
    private final TestManagedWorktreeSettings worktreeSettings = new TestManagedWorktreeSettings();
    int providerCredentialSetCalls;
    int providerCredentialClearCalls;
    int providerVerificationCalls;
    CommandOptions lastProviderCreateOptions;
    CommandOptions lastProviderUpdateOptions;
    CommandOptions lastProviderCredentialOptions;
    ProviderLifecycle lastProviderCreateLifecycle;
    RuntimeException nextProviderCreateResponseFailure;
    char[] lastProviderSecret;
    RuntimeException nextFailure;

    TestCoreSettingsGateway() {
        providers.add(TestCoreSettingsFixtures.provider(
                1, TestCoreSettingsFixtures.providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE));
    }

    @Override
    public CompletionStage<List<ProviderEndpoint>> providers() {
        return completed(List.copyOf(providers));
    }

    @Override
    public CompletionStage<ProviderEndpoint> createProvider(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
        lastProviderCreateOptions = options;
        lastProviderCreateLifecycle = lifecycle;
        ProviderEndpoint created = new ProviderEndpoint(id, 1, lifecycle, spec, NOW, NOW);
        providers.add(created);
        if (nextProviderCreateResponseFailure != null) {
            RuntimeException failure = nextProviderCreateResponseFailure;
            nextProviderCreateResponseFailure = null;
            return failed(failure);
        }
        return completed(created);
    }

    @Override
    public CompletionStage<ProviderModelDiscoveryResult> discoverProviderModels(
            String id, long revision, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        discoveryCancellations.add(cancellation);
        if (!discoveryResponses.isEmpty()) {
            return discoveryResponses.removeFirst();
        }
        ProviderEndpoint endpoint = providers.stream()
                .filter(candidate -> candidate.id().equals(id) && candidate.revision() == revision)
                .findFirst()
                .orElseThrow();
        return completed(new ProviderModelDiscoveryResult(id, revision, List.of(), false, endpoint.updatedAt()));
    }

    @Override
    public CompletionStage<Optional<EmbeddingBinding>> embeddingBinding() {
        return completed(embeddingBinding);
    }

    @Override
    public CompletionStage<EmbeddingBinding> bindEmbedding(ProviderRef provider, CommandOptions options) {
        EmbeddingBinding updated = new EmbeddingBinding(provider, options.expectedRevision() + 1, NOW);
        embeddingBinding = Optional.of(updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<ProviderEndpoint> updateProvider(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
        lastProviderUpdateOptions = options;
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
                new ProviderCapabilities(
                        Set.of(ProviderModelPurpose.CHAT), true, true, true, false, false, false, false),
                Optional.empty(),
                NOW));
    }

    @Override
    public CompletionStage<ProviderVerificationResult> verifyProviderRoundTrip(
            ProviderRef provider,
            ProviderModelPurpose purpose,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options) {
        providerVerificationCalls++;
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        return completed(TestCoreSettingsFixtures.verification(provider, purpose, NOW));
    }

    @Override
    public CompletionStage<ProviderCredentialBinding> setProviderCredential(
            ProviderEndpoint provider, long credentialExpectedRevision, char[] secret, CommandOptions options) {
        providerCredentialSetCalls++;
        lastProviderCredentialOptions = options;
        lastProviderSecret = secret;
        RuntimeException failure = takeFailure();
        if (failure != null) {
            return failed(failure);
        }
        CredentialRef reference =
                provider.spec().credential().orElseGet(() -> new CredentialRef("provider", "credential-1"));
        CredentialMetadata metadata = new CredentialMetadata(reference, credentialExpectedRevision + 1, NOW);
        ProviderEndpointSpec current = provider.spec();
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                current.displayName(),
                current.adapter(),
                current.baseUri(),
                current.authentication(),
                current.models(),
                Optional.of(reference),
                current.timeout(),
                current.maximumRetries(),
                current.options());
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
                provider.id(),
                provider.revision() + 1,
                provider.lifecycle(),
                TestCoreSettingsFixtures.providerSpec(Optional.empty()),
                NOW,
                NOW);
        providers.remove(provider);
        providers.add(updated);
        credential = Optional.empty();
        CredentialClearReceipt receipt = new CredentialClearReceipt(metadata.reference(), metadata.revision(), NOW);
        return completed(new ProviderCredentialClearResult(updated, receipt));
    }

    @Override
    public CompletionStage<List<PermissionProfile>> permissionProfiles() {
        return completed(List.copyOf(permissions));
    }

    @Override
    public CompletionStage<PermissionProfile> permissionProfile(PermissionProfileRef reference) {
        return completed(permissionSettings.history(reference.id()).stream()
                .filter(candidate -> candidate.version() == reference.version())
                .findFirst()
                .orElseThrow());
    }

    @Override
    public CompletionStage<ToolCatalogQueryResult> toolCatalog(
            WorkspaceId workspaceId,
            PermissionProfileRef permissionProfile,
            Optional<AgentRoleRef> agentRole,
            String query,
            int limit) {
        ToolDescriptor descriptor = CoreTools.search();
        List<ToolDescriptor> matches = descriptor.identity().name().contains(query)
                        || descriptor.description().contains(query)
                ? List.of(descriptor)
                : List.of();
        return completed(
                new ToolCatalogQueryResult(11, matches.stream().limit(limit).toList()));
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
        return workspaceSettings.list();
    }

    @Override
    public CompletionStage<Workspace> renameWorkspace(Workspace workspace, String name, CommandOptions options) {
        return completed(workspaceSettings.rename(workspace, name));
    }

    @Override
    public CompletionStage<Workspace> archiveWorkspace(Workspace workspace, CommandOptions options) {
        return completed(workspaceSettings.archive(workspace));
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
        return completed(TestCoreSettingsFixtures.connection());
    }

    @Override
    public CompletionStage<ConnectionSummary> reconnect() {
        return connection();
    }

    @Override
    public CompletionStage<DiagnosticsSnapshot> diagnostics() {
        return completed(TestCoreSettingsFixtures.diagnostics(NOW));
    }

    @Override
    public CompletionStage<DiagnosticsRpcContracts.LauncherStatus> launcherStatus() {
        return completed(TestCoreSettingsFixtures.launcherStatus());
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

    static AgentRoleSpec profileSpec() {
        return TestCoreSettingsFixtures.profileSpec();
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
