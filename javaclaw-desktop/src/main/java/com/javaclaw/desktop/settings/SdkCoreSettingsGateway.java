package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.InitializeResult;

/** 通过当前 DesktopPresenter 会话执行 SDK 请求的管理中心网关。 */
public final class SdkCoreSettingsGateway extends SdkRoleExecutionSettingsGateway
        implements CoreSettingsGateway, McpSettingsGateway, InstructionSettingsGateway, BundleSettingsGateway {
    private final DesktopPresenter desktop;

    /**
     * 创建网关。
     *
     * @param desktop 拥有 SDK 会话和后台执行器的 Presenter
     */
    public SdkCoreSettingsGateway(DesktopPresenter desktop) {
        super(desktop);
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<com.javaclaw.api.ModelContextLimits> modelContextLimits(ProviderRef provider) {
        return desktop.submitSettingsRequest(client -> client.providers().contextLimits(provider));
    }

    @Override
    public CompletionStage<com.javaclaw.api.ModelContextLimits> updateModelContextLimits(
            com.javaclaw.api.ModelContextLimits limits, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.providers().updateContextLimits(limits, options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<List<ProviderEndpoint>> providers() {
        return desktop.submitSettingsRequest(client -> client.providers().list());
    }

    @Override
    public CompletionStage<ProviderEndpoint> createProvider(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.providers().create(id, spec, lifecycle, options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<ProviderEndpoint> updateProvider(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.providers().update(id, spec, lifecycle, options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<ProviderEndpoint> archiveProvider(String id, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.providers().archive(id, options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<ProviderModelDiscoveryResult> discoverProviderModels(
            String id, long revision, com.javaclaw.api.CancellationToken cancellation) {
        return desktop.submitSettingsRequest(client -> client.providers().discoverModels(id, revision, cancellation));
    }

    @Override
    public CompletionStage<Optional<EmbeddingBinding>> embeddingBinding() {
        return desktop.submitSettingsRequest(client -> client.providers().embeddingBinding());
    }

    @Override
    public CompletionStage<EmbeddingBinding> bindEmbedding(ProviderRef provider, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.providers().bindEmbedding(provider, options));
    }

    @Override
    public CompletionStage<ProviderStatus> probeProvider(ProviderRef provider) {
        return desktop.submitSettingsRequest(client -> client.providers().probe(provider));
    }

    @Override
    public CompletionStage<ProviderVerificationResult> verifyProviderRoundTrip(
            ProviderRef provider,
            ProviderModelPurpose purpose,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options) {
        return desktop.submitSettingsRequest(client ->
                client.providers().verifyRoundTrip(provider, purpose, billingConfirmed, confirmation, options));
    }

    @Override
    public CompletionStage<ProviderCredentialBinding> setProviderCredential(
            ProviderEndpoint provider, long credentialExpectedRevision, char[] secret, CommandOptions options) {
        char[] owned = Arrays.copyOf(Objects.requireNonNull(secret, "secret"), secret.length);
        return changed(desktop.submitSettingsRequest(client -> {
            try {
                return client.providers()
                        .setCredential(provider.id(), provider.revision(), credentialExpectedRevision, owned, options);
            } finally {
                Arrays.fill(owned, '\0');
            }
        }), DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<ProviderCredentialClearResult> clearProviderCredential(
            ProviderEndpoint provider, CredentialMetadata credential, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.providers()
                .clearCredential(
                        provider.id(), provider.revision(), credential.reference(), credential.revision(), options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<List<PermissionProfile>> permissionProfiles() {
        return desktop.submitSettingsRequest(
                client -> client.permissionProfiles().list());
    }

    @Override
    public CompletionStage<PermissionProfile> permissionProfile(PermissionProfileRef reference) {
        return desktop.submitSettingsRequest(
                client -> client.permissionProfiles().read(Objects.requireNonNull(reference, "reference")));
    }

    @Override
    public CompletionStage<ToolCatalogQueryResult> toolCatalog(
            WorkspaceId workspaceId,
            PermissionProfileRef permissionProfile,
            Optional<AgentRoleRef> agentRole,
            String query,
            int limit) {
        return desktop.submitSettingsRequest(
                client -> client.tools().catalog(workspaceId, permissionProfile, agentRole, query, limit));
    }

    @Override
    public CompletionStage<List<PermissionProfile>> permissionProfileHistory(String id) {
        return desktop.submitSettingsRequest(
                client -> client.permissionProfiles().history(id));
    }

    @Override
    public CompletionStage<PermissionProfileDiff> permissionProfileDiff(
            String id, long beforeVersion, long afterVersion) {
        return desktop.submitSettingsRequest(
                client -> client.permissionProfiles().diff(id, beforeVersion, afterVersion));
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return desktop.submitSettingsRequest(client -> client.workspaces().list());
    }

    @Override
    public CompletionStage<Workspace> renameWorkspace(Workspace workspace, String name, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.workspaces().rename(workspace, name, options)),
                DesktopConfigurationChange.Kind.WORKSPACES, Optional.of(workspace.id()), Optional.empty());
    }

    @Override
    public CompletionStage<Workspace> archiveWorkspace(Workspace workspace, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.workspaces().archive(workspace, options)),
                DesktopConfigurationChange.Kind.WORKSPACES, Optional.of(workspace.id()), Optional.empty());
    }

    @Override
    public CompletionStage<List<McpEndpoint>> mcpEndpoints(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(client -> client.mcp().list(workspaceId));
    }

    @Override
    public CompletionStage<McpEndpoint> mcpEndpoint(String endpointId) {
        return desktop.submitSettingsRequest(client -> client.mcp().read(endpointId));
    }

    @Override
    public CompletionStage<List<McpEndpoint>> mcpEndpointHistory(String endpointId) {
        return desktop.submitSettingsRequest(client -> client.mcp().history(endpointId));
    }

    @Override
    public CompletionStage<McpEndpoint> createMcpEndpoint(
            String endpointId, McpEndpointSpec spec, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.mcp().create(endpointId, spec, options));
    }

    @Override
    public CompletionStage<McpEndpoint> updateMcpEndpoint(
            String endpointId, McpEndpointSpec spec, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.mcp().update(endpointId, spec, options));
    }

    @Override
    public CompletionStage<McpEndpoint> setMcpEndpointEnabled(
            McpEndpoint endpoint, boolean enabled, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> enabled
                ? client.mcp().enable(endpoint.id(), options)
                : client.mcp().disable(endpoint.id(), options));
    }

    @Override
    public CompletionStage<McpHealth> probeMcpEndpoint(String endpointId) {
        return desktop.submitSettingsRequest(client -> client.mcp().probe(endpointId));
    }

    @Override
    public CompletionStage<McpHealth> mcpHealth(String endpointId) {
        return desktop.submitSettingsRequest(client -> client.mcp().health(endpointId));
    }

    @Override
    public CompletionStage<McpEndpoint> refreshMcpCatalog(McpEndpoint endpoint, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.mcp().refreshCatalog(endpoint.id(), options));
    }

    @Override
    public CompletionStage<McpCatalogPage> mcpCatalog(
            String endpointId, Optional<McpCatalogKind> kind, Optional<String> cursor, int limit) {
        return desktop.submitSettingsRequest(client -> client.mcp().catalog(endpointId, kind, cursor, limit));
    }

    @Override
    public CompletionStage<McpResourcePage> mcpResources(String endpointId, Optional<String> cursor) {
        return desktop.submitSettingsRequest(client -> client.mcp().resources(endpointId, cursor));
    }

    @Override
    public CompletionStage<McpResourceReadResult> readMcpResource(String endpointId, String uri) {
        return desktop.submitSettingsRequest(client -> client.mcp().readResource(endpointId, uri));
    }

    @Override
    public CompletionStage<McpPromptPage> mcpPrompts(String endpointId, Optional<String> cursor) {
        return desktop.submitSettingsRequest(client -> client.mcp().prompts(endpointId, cursor));
    }

    @Override
    public CompletionStage<McpPromptResult> getMcpPrompt(
            String endpointId, String name, Map<String, String> arguments) {
        return desktop.submitSettingsRequest(client -> client.mcp().getPrompt(endpointId, name, arguments));
    }

    @Override
    public CompletionStage<McpOAuthAuthorization> startMcpOAuth(McpEndpoint endpoint, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.mcp().startOAuth(endpoint.id(), options));
    }

    @Override
    public CompletionStage<Optional<McpOAuthAuthorization>> latestMcpOAuth(String endpointId) {
        return desktop.submitSettingsRequest(client -> client.mcp().latestOAuth(endpointId));
    }

    @Override
    public CompletionStage<McpOAuthAuthorization> cancelMcpOAuth(
            McpOAuthAuthorization authorization, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.mcp().cancelOAuth(authorization.id(), options));
    }

    @Override
    public CompletionStage<List<ManagedWorktree>> managedWorktrees(WorkspaceId workspaceId, boolean includeCleaned) {
        return desktop.submitSettingsRequest(client -> client.worktrees().list(workspaceId, includeCleaned));
    }

    @Override
    public CompletionStage<com.javaclaw.api.InstructionResolution> instructionResolution(
            WorkspaceId workspaceId, Optional<com.javaclaw.api.WorktreeId> worktreeId) {
        return desktop.submitSettingsRequest(client -> client.instructions().read(workspaceId, worktreeId));
    }

    @Override
    public CompletionStage<com.javaclaw.api.WorkspaceInstructionSettings> instructionSettings(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(client -> client.instructions().readSettings(workspaceId));
    }

    @Override
    public CompletionStage<com.javaclaw.api.WorkspaceInstructionSettings> updateInstructionSettings(
            WorkspaceId workspaceId, Optional<String> fallbackBasename, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.instructions().updateSettings(workspaceId, fallbackBasename, options));
    }

    @Override
    public CompletionStage<ManagedWorktree> interruptManagedWorktree(
            ManagedWorktree worktree, String reason, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.worktrees().interrupt(worktree.id(), reason, options));
    }

    @Override
    public CompletionStage<ManagedWorktreeArtifact> exportManagedWorktreePatch(
            ManagedWorktree worktree, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.worktrees().exportPatch(worktree.id(), options));
    }

    @Override
    public CompletionStage<ManagedWorktreeArtifact> backupManagedWorktree(
            ManagedWorktree worktree, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.worktrees().backup(worktree.id(), options));
    }

    @Override
    public CompletionStage<ManagedWorktree> cleanupManagedWorktree(
            ManagedWorktree worktree, String confirmation, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.worktrees().cleanup(worktree.id(), confirmation, options));
    }

    @Override
    public CompletionStage<ConversationThread> navigateToThread(ThreadId threadId) {
        return desktop.navigateToThread(threadId);
    }

    @Override
    public CompletionStage<PermissionProfile> clonePermissionProfile(
            PermissionProfileRef source, String newId, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(
                client -> client.permissionProfiles().cloneProfile(source, newId, options)),
                DesktopConfigurationChange.Kind.PERMISSIONS);
    }

    @Override
    public CompletionStage<PermissionProfile> updatePermissionProfile(
            PermissionProfile profile, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(
                client -> client.permissionProfiles().update(profile, options)),
                DesktopConfigurationChange.Kind.PERMISSIONS);
    }

    @Override
    public CompletionStage<EffectivePermissionPreview> effectivePermissionPreview(
            WorkspaceId workspaceId,
            PermissionProfileRef profile,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        return desktop.submitSettingsRequest(client ->
                client.permissionProfiles().effectivePreview(workspaceId, profile, turnGrant, toolDeclaration));
    }

    @Override
    public CompletionStage<PrivateNetworkGrantPreview> previewPrivateNetworkGrant(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Optional<Duration> validity) {
        return desktop.submitSettingsRequest(client ->
                client.securityGrants().previewPrivateNetwork(workspaceId, purpose, origin, dnsAddresses, validity));
    }

    @Override
    public CompletionStage<List<PrivateNetworkGrant>> privateNetworkGrants(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(client -> client.securityGrants().listPrivateNetwork(workspaceId));
    }

    @Override
    public CompletionStage<PrivateNetworkGrant> createPrivateNetworkGrant(
            PrivateNetworkGrantPreview preview, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.securityGrants().createPrivateNetwork(preview, options));
    }

    @Override
    public CompletionStage<PrivateNetworkGrant> revokePrivateNetworkGrant(
            PrivateNetworkGrant grant, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.securityGrants().revokePrivateNetwork(grant.id(), options));
    }

    @Override
    public CompletionStage<List<UnattendedToolGrantStatus>> unattendedToolGrants(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(client -> client.securityGrants().listUnattended(workspaceId));
    }

    @Override
    public CompletionStage<UnattendedToolGrant> createUnattendedToolGrant(
            UnattendedToolGrantDraft draft, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.securityGrants().createUnattended(draft, options));
    }

    @Override
    public CompletionStage<UnattendedToolGrant> revokeUnattendedToolGrant(
            UnattendedToolGrant grant, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.securityGrants().revokeUnattended(grant.id(), options));
    }

    @Override
    public CompletionStage<List<PermissionDecisionTrace>> permissionDecisions(
            WorkspaceId workspaceId, SecurityGrantKind kind, Optional<String> grantId, int limit) {
        return desktop.submitSettingsRequest(
                client -> client.securityGrants().decisions(workspaceId, Optional.of(kind), grantId, limit));
    }

    @Override
    public CompletionStage<VaultStatus> vaultStatus() {
        return desktop.submitSettingsRequest(client -> client.credentials().status());
    }

    @Override
    public CompletionStage<VaultStatus> refreshVault() {
        return changed(desktop.submitSettingsRequest(client -> client.credentials().refresh()),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<VaultManagementReceipt> rotateVaultMasterKey(CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.credentials().rotateMasterKey(options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<VaultManagementReceipt> resetVault(String confirmation, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.credentials().reset(confirmation, options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<Optional<CredentialMetadata>> credential(CredentialRef reference) {
        return desktop.submitSettingsRequest(client -> client.credentials().read(reference));
    }

    @Override
    public CompletionStage<List<CredentialMetadata>> credentials(String namespace) {
        return desktop.submitSettingsRequest(client -> client.credentials().list(namespace));
    }

    @Override
    public CompletionStage<CredentialMetadata> createCredential(
            String namespace, char[] secret, CommandOptions options) {
        char[] owned = Arrays.copyOf(Objects.requireNonNull(secret, "secret"), secret.length);
        return changed(desktop.submitSettingsRequest(client -> {
            try {
                return client.credentials().create(namespace, owned, options);
            } finally {
                Arrays.fill(owned, '\0');
            }
        }), DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<CredentialMetadata> rotateCredential(
            CredentialRef reference, char[] secret, CommandOptions options) {
        char[] owned = Arrays.copyOf(Objects.requireNonNull(secret, "secret"), secret.length);
        return changed(desktop.submitSettingsRequest(client -> {
            try {
                return client.credentials().rotate(reference, owned, options);
            } finally {
                Arrays.fill(owned, '\0');
            }
        }), DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<CredentialClearReceipt> clearCredential(CredentialRef reference, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.credentials().clear(reference, options)),
                DesktopConfigurationChange.Kind.PROVIDERS);
    }

    @Override
    public CompletionStage<ConnectionSummary> connection() {
        return desktop.submitSettingsRequest(client -> summary(client.server()));
    }

    @Override
    public CompletionStage<ConnectionSummary> reconnect() {
        return desktop.reconnect().thenApply(SdkCoreSettingsGateway::summary);
    }

    @Override
    public CompletionStage<DiagnosticsSnapshot> diagnostics() {
        return desktop.submitSettingsRequest(client -> client.diagnostics().read());
    }

    @Override
    public CompletionStage<DiagnosticsRpcContracts.LauncherStatus> launcherStatus() {
        return desktop.submitSettingsRequest(client -> client.diagnostics().launcherStatus());
    }

    @Override
    public CompletionStage<DiagnosticsRpcContracts.ServerStopResult> stopServer(CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.diagnostics().stopServer(options));
    }

    @Override
    public CompletionStage<DiagnosticsSnapshot> repairLoginStartup(CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.diagnostics().repairLoginStartup(options));
    }

    private static ConnectionSummary summary(InitializeResult result) {
        return new ConnectionSummary(
                result.serverName(),
                result.serverVersion(),
                result.appProtocolVersion(),
                result.capabilities().stableCapabilities(),
                result.capabilities().experimentalCapabilities());
    }
}
