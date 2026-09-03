package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionLayerKind;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PermissionSection;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreSettingsPresentersTest {
    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");

    @Test
    void provider新建草稿由Presenter生成稳定技术标识() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway, () -> "provider-generated-id");
        presenter.createDraft();

        assertEquals("provider-generated-id", presenter.state().draft().id());
        assertTrue(presenter.state().selected().isEmpty());
        presenter.updateDraft(copyWithDisplayName(presenter.state().draft(), "本地模型"));
        assertEquals("provider-generated-id", presenter.state().draft().id());
    }

    @Test
    void provider支持新建探测Secret绑定轮换清除和冲突保留() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        ProviderSettingsPresenter presenter = new ProviderSettingsPresenter(gateway);
        AtomicReference<ProviderSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        assertEquals(SettingsLoadState.READY, latest.get().phase());
        assertEquals("provider-main", latest.get().selected().orElseThrow().id());
        presenter.probe();
        assertEquals(
                ProviderReadiness.CREDENTIAL_REQUIRED,
                latest.get().providerStatus().orElseThrow().readiness());

        presenter.replaceSecret("temporary-secret".toCharArray());
        assertEquals(1, gateway.providerCredentialSetCalls);
        assertTrue(allZero(gateway.lastProviderSecret));
        assertTrue(latest.get().selected().orElseThrow().spec().credential().isPresent());
        assertTrue(latest.get().credential().isPresent());
        long firstSecretRevision = latest.get().credential().orElseThrow().revision();
        presenter.replaceSecret("rotated-secret".toCharArray());
        assertEquals(2, gateway.providerCredentialSetCalls);
        assertEquals(
                firstSecretRevision + 1, latest.get().credential().orElseThrow().revision());
        presenter.clearSecret();
        assertEquals(1, gateway.providerCredentialClearCalls);
        assertTrue(latest.get().selected().orElseThrow().spec().credential().isEmpty());

        ProviderDraft changed = ProviderDraft.from(latest.get().selected().orElseThrow());
        changed = new ProviderDraft(
                changed.id(),
                "新名称",
                changed.adapter(),
                changed.baseUri(),
                changed.authentication(),
                changed.models(),
                changed.credential(),
                changed.timeoutSeconds(),
                changed.maximumRetries(),
                changed.organization(),
                changed.project(),
                changed.apiVersion(),
                changed.reasoningSummary(),
                changed.lifecycle());
        presenter.updateDraft(changed);
        gateway.nextFailure = revisionConflict();
        presenter.save();
        assertTrue(latest.get().revisionConflict());
        assertTrue(latest.get().dirty());
    }

    private static ProviderDraft copyWithDisplayName(ProviderDraft draft, String displayName) {
        return new ProviderDraft(
                draft.id(),
                displayName,
                draft.adapter(),
                draft.baseUri(),
                draft.authentication(),
                draft.models(),
                draft.credential(),
                draft.timeoutSeconds(),
                draft.maximumRetries(),
                draft.organization(),
                draft.project(),
                draft.apiVersion(),
                draft.reasoningSummary(),
                draft.lifecycle());
    }

    @Test
    void profile创建时冻结精确Provider权限与预算引用() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        AgentProfileSettingsPresenter presenter = new AgentProfileSettingsPresenter(gateway);
        AtomicReference<AgentProfileSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();

        presenter.createDraft();
        ProviderEndpoint provider = gateway.providers.getFirst();
        PermissionProfile permission = gateway.permissions.getFirst();
        presenter.updateDraft(new AgentProfileDraft(
                "reviewer",
                "Reviewer",
                "检查边界。",
                Optional.of(new ProviderRef(
                        provider.id(),
                        provider.revision(),
                        provider.spec().models().getFirst().modelId())),
                Optional.of(new PermissionProfileRef(permission.id(), permission.version())),
                "core/tool/search",
                8_000,
                2_000,
                6,
                1,
                120,
                ProfileLifecycle.ACTIVE));
        presenter.save();

        AgentProfile saved = latest.get().selected().orElseThrow();
        assertEquals("reviewer", saved.id());
        assertEquals(provider.revision(), saved.spec().provider().endpointRevision());
        assertEquals(permission.version(), saved.spec().permissionProfile().version());
        assertFalse(latest.get().dirty());
    }

    @Test
    void provider新版本只在用户显式操作后更新旧智能体引用() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        AgentProfile oldProfile = gateway.createProfile(
                        "old-provider-profile",
                        TestCoreSettingsGateway.profileSpec(),
                        com.javaclaw.client.CommandOptions.create(0))
                .toCompletableFuture()
                .join();
        ProviderEndpoint oldProvider = gateway.providers.getFirst();
        ProviderEndpoint newProvider = gateway.updateProvider(
                        oldProvider.id(),
                        oldProvider.spec(),
                        ProviderLifecycle.ACTIVE,
                        com.javaclaw.client.CommandOptions.create(oldProvider.revision()))
                .toCompletableFuture()
                .join();
        ProviderProfileReferencePresenter presenter = new ProviderProfileReferencePresenter(gateway);

        presenter.bind(Optional.of(newProvider));

        assertEquals(oldProfile.id(), presenter.state().selected().orElseThrow().id());
        assertEquals(
                oldProvider.revision(),
                gateway.profiles.getFirst().spec().provider().endpointRevision());
        presenter.updateSelected();
        assertEquals(
                newProvider.revision(),
                gateway.profiles.getFirst().spec().provider().endpointRevision());
        assertTrue(presenter.state().staleProfiles().isEmpty());
    }

    @Test
    void permission的standard只读且clone生成独立版本并显示本地差异() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        PermissionProfileSettingsPresenter presenter = new PermissionProfileSettingsPresenter(gateway);
        AtomicReference<PermissionProfileSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);
        presenter.reload();
        assertTrue(latest.get().standardReadOnly());

        presenter.cloneSelected();
        PermissionProfileDraft template = latest.get().draft();
        PermissionProfileDraft custom = new PermissionProfileDraft(
                "workspace-safe",
                template.readRoots(),
                template.writeRoots(),
                template.allowDelete(),
                template.followSymbolicLinks(),
                template.networkHosts(),
                template.networkPorts(),
                template.tlsOnly(),
                template.executables(),
                template.allowPty(),
                template.processSeconds(),
                template.allowedTools(),
                template.maximumRisk(),
                template.approvalRequirement(),
                template.memoryMiB(),
                template.outputMiB(),
                template.childProcesses(),
                template.openFiles());
        presenter.updateDraft(custom);
        assertEquals(List.of("身份"), custom.changedSections(template));
        presenter.save();
        assertEquals("workspace-safe", latest.get().selected().orElseThrow().id());
        assertEquals(1, latest.get().selected().orElseThrow().version());

        PermissionProfileDraft firstVersion = latest.get().draft();
        presenter.updateDraft(withToolRisk(firstVersion, ToolRisk.NETWORK));
        presenter.save();
        PermissionHistoryPresenter history = new PermissionHistoryPresenter(gateway);
        AtomicReference<PermissionHistoryState> historyState = new AtomicReference<>();
        history.subscribe(historyState::set);
        history.load(latest.get().selected().orElseThrow());
        assertEquals(
                Set.of(PermissionSection.TOOL),
                historyState.get().diff().orElseThrow().changedSections());

        PermissionPreviewPresenter preview = new PermissionPreviewPresenter(gateway);
        AtomicReference<PermissionPreviewState> previewState = new AtomicReference<>();
        preview.subscribe(previewState::set);
        preview.reloadWorkspaces();
        preview.selectTurnGrant(gateway.permissions.getFirst());
        preview.selectToolDeclaration(gateway.permissions.getFirst());
        preview.preview(latest.get().selected().orElseThrow());
        assertEquals(5, previewState.get().preview().orElseThrow().layers().size());
        assertTrue(previewState.get().preview().orElseThrow().layers().stream()
                .filter(layer -> layer.layer() == PermissionLayerKind.TURN_GRANT
                        || layer.layer() == PermissionLayerKind.TOOL_DECLARATION)
                .allMatch(layer -> layer.applied() && layer.source().isPresent()));
    }

    @Test
    void vault和连接页只发布脱敏权威快照() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        VaultSettingsPresenter vault = new VaultSettingsPresenter(gateway);
        AtomicReference<VaultSettingsState> vaultState = new AtomicReference<>();
        vault.subscribe(vaultState::set);
        vault.reload();
        assertEquals(VaultState.READY, vaultState.get().status().orElseThrow().state());
        assertEquals(0, vaultState.get().status().orElseThrow().credentialCount());
        vault.rotateMasterKey();
        assertEquals(
                VaultManagementAction.MASTER_KEY_ROTATED,
                vaultState.get().receipt().orElseThrow().action());
        vault.reset("RESET VAULT");
        assertEquals(
                VaultManagementAction.VAULT_RESET,
                vaultState.get().receipt().orElseThrow().action());

        ConnectionSettingsPresenter connection = new ConnectionSettingsPresenter(gateway);
        AtomicReference<ConnectionSettingsState> connectionState = new AtomicReference<>();
        connection.subscribe(connectionState::set);
        connection.reload();
        assertEquals(
                "javaclaw-app-server",
                connectionState.get().summary().orElseThrow().serverName());
        assertEquals(2, connectionState.get().summary().orElseThrow().protocolVersion());
    }

    @Test
    void workspace重命名归档和默认Profile绑定都使用权威对象与精确版本() {
        TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
        AgentProfile profile = gateway.createProfile(
                        "workspace-profile",
                        TestCoreSettingsGateway.profileSpec(),
                        com.javaclaw.client.CommandOptions.create(0))
                .toCompletableFuture()
                .join();
        WorkspaceSettingsPresenter presenter = new WorkspaceSettingsPresenter(gateway);
        AtomicReference<WorkspaceSettingsState> latest = new AtomicReference<>();
        presenter.subscribe(latest::set);

        presenter.reload();
        Workspace workspace = latest.get().selected().orElseThrow();
        assertEquals(SettingsLoadState.READY, latest.get().phase());
        presenter.editName("  新工作区  ");
        presenter.saveName();
        assertEquals("新工作区", gateway.workspaceSettings.lastName);
        presenter.chooseProfile(profile);
        presenter.saveProfile();
        assertEquals(profile.id(), gateway.workspaceSettings.lastProfile.id());
        assertEquals(profile.revision(), gateway.workspaceSettings.lastProfile.revision());
        presenter.editName("临时草稿");
        presenter.discardDraft();
        assertEquals(workspace.name(), latest.get().draftName());
        presenter.archive();
        assertTrue(gateway.workspaceSettings.archived);

        presenter.editName("   ");
        presenter.saveName();
        assertEquals(SettingsLoadState.ERROR, latest.get().phase());
        assertEquals("工作区名称不能为空", latest.get().message());
    }

    private static RemoteRpcException revisionConflict() {
        return new RemoteRpcException(new JsonRpcError(
                ProtocolErrorCode.REVISION_CONFLICT, "revision 已改变", Optional.of(new CanonicalPayload("{}"))));
    }

    private static PermissionProfileDraft withToolRisk(PermissionProfileDraft source, ToolRisk risk) {
        return new PermissionProfileDraft(
                source.id(),
                source.readRoots(),
                source.writeRoots(),
                source.allowDelete(),
                source.followSymbolicLinks(),
                source.networkHosts(),
                source.networkPorts(),
                source.tlsOnly(),
                source.executables(),
                source.allowPty(),
                source.processSeconds(),
                source.allowedTools(),
                risk,
                source.approvalRequirement(),
                source.memoryMiB(),
                source.outputMiB(),
                source.childProcesses(),
                source.openFiles());
    }

    private static boolean allZero(char[] value) {
        for (char character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }
}
