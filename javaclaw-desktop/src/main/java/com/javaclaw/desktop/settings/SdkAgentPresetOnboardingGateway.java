package com.javaclaw.desktop.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.facade.ProviderClient;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.DesktopPresenter;

/** 通过当前 Desktop SDK 会话执行首次智能体初始化请求。 */
public final class SdkAgentPresetOnboardingGateway implements AgentPresetOnboardingGateway {
    private static final int TOOL_CANDIDATE_LIMIT = 100;

    private final DesktopPresenter desktop;

    /**
     * 创建 SDK 网关。
     *
     * @param desktop 拥有已初始化 JavaClawClient 和后台执行器的 Presenter
     */
    public SdkAgentPresetOnboardingGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<AgentPresetOnboardingCatalog> load(WorkspaceId workspaceId) {
        WorkspaceId frozen = Objects.requireNonNull(workspaceId, "workspaceId");
        return desktop.submitSettingsRequest(client -> load(client, frozen));
    }

    @Override
    public CompletionStage<PermissionPresetPreview> previewPermission(PermissionPresetInstantiationRequest request) {
        PermissionPresetInstantiationRequest frozen = Objects.requireNonNull(request, "request");
        return desktop.submitSettingsRequest(
                client -> client.permissionProfiles().previewPreset(frozen));
    }

    @Override
    public CompletionStage<PermissionPresetInstantiationResult> instantiatePermission(
            PermissionPresetInstantiationRequest request, CommandOptions options) {
        PermissionPresetInstantiationRequest frozen = Objects.requireNonNull(request, "request");
        CommandOptions frozenOptions = Objects.requireNonNull(options, "options");
        return desktop.submitSettingsRequest(
                client -> client.permissionProfiles().instantiatePreset(frozen, frozenOptions));
    }

    @Override
    public CompletionStage<List<ToolDescriptor>> searchTools(
            WorkspaceId workspaceId, PermissionProfileRef permissionProfile) {
        WorkspaceId frozenWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        PermissionProfileRef frozenPermission = Objects.requireNonNull(permissionProfile, "permissionProfile");
        return desktop.submitSettingsRequest(
                client -> client.tools().search(frozenWorkspace, frozenPermission, "", TOOL_CANDIDATE_LIMIT));
    }

    @Override
    public CompletionStage<PermissionProfile> updatePermission(PermissionProfile profile, CommandOptions options) {
        PermissionProfile frozen = Objects.requireNonNull(profile, "profile");
        CommandOptions frozenOptions = Objects.requireNonNull(options, "options");
        return desktop.submitSettingsRequest(
                client -> client.permissionProfiles().update(frozen, frozenOptions));
    }

    @Override
    public CompletionStage<AgentProfile> createProfile(String id, AgentProfileSpec spec, CommandOptions options) {
        String frozenId = requireText(id, "id");
        AgentProfileSpec frozenSpec = Objects.requireNonNull(spec, "spec");
        CommandOptions frozenOptions = Objects.requireNonNull(options, "options");
        return desktop.submitSettingsRequest(client -> client.profiles().create(frozenId, frozenSpec, frozenOptions));
    }

    @Override
    public CompletionStage<ProfileBinding> bindDefault(
            WorkspaceId workspaceId, AgentProfileRef profile, CommandOptions options) {
        WorkspaceId frozenWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        AgentProfileRef frozenProfile = Objects.requireNonNull(profile, "profile");
        CommandOptions frozenOptions = Objects.requireNonNull(options, "options");
        return desktop.submitSettingsRequest(
                client -> client.profiles().bind(frozenWorkspace, Optional.empty(), frozenProfile, frozenOptions));
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }

    private static AgentPresetOnboardingCatalog load(JavaClawClient client, WorkspaceId workspaceId) {
        List<ProviderEndpoint> providers = client.providers().list();
        List<AgentProfile> profiles = client.profiles().list();
        return new AgentPresetOnboardingCatalog(
                client.profiles().presets(),
                client.permissionProfiles().presets(),
                providers,
                availableProviderVersions(client.providers(), providers, profiles),
                profiles,
                client.permissionProfiles().list(),
                client.profiles().binding(workspaceId, Optional.empty()));
    }

    private static List<ProviderEndpoint> availableProviderVersions(
            ProviderClient client, List<ProviderEndpoint> latest, List<AgentProfile> profiles) {
        LinkedHashMap<ProviderVersion, ProviderCandidate> candidates = new LinkedHashMap<>();
        latest.stream()
                .filter(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ACTIVE)
                .forEach(endpoint -> firstChatReference(endpoint)
                        .ifPresent(reference -> candidates.put(
                                ProviderVersion.from(reference), new ProviderCandidate(endpoint, reference))));
        profiles.stream()
                .map(profile -> profile.spec().provider())
                .forEach(reference -> candidates.computeIfAbsent(
                        ProviderVersion.from(reference),
                        ignored -> new ProviderCandidate(
                                client.read(reference.endpointId(), reference.endpointRevision()), reference)));
        return candidates.values().stream()
                .filter(candidate -> client.status(candidate.reference()).readiness() == ProviderReadiness.READY)
                .map(ProviderCandidate::endpoint)
                .toList();
    }

    private static Optional<ProviderRef> firstChatReference(ProviderEndpoint endpoint) {
        return endpoint.spec().models().stream()
                .filter(model -> model.supports(ProviderModelPurpose.CHAT))
                .findFirst()
                .map(model -> new ProviderRef(endpoint.id(), endpoint.revision(), model.modelId()));
    }

    private record ProviderVersion(String id, long revision) {
        private static ProviderVersion from(ProviderRef reference) {
            return new ProviderVersion(reference.endpointId(), reference.endpointRevision());
        }
    }

    private record ProviderCandidate(ProviderEndpoint endpoint, ProviderRef reference) {}
}
