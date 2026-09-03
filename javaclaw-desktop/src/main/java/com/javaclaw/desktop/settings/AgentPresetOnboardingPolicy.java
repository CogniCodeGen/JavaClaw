package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfilePreset;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionPresetDescriptor;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.Workspace;

/** 校验向导目录、确定性资源及用户精确选择的纯领域策略。 */
final class AgentPresetOnboardingPolicy {
    static final String DEFAULT = "default";
    static final String WORKER = "worker";
    static final String EXPLORER = "explorer";
    static final String REVIEW = "workspace-review";
    static final String DEVELOPER = "workspace-developer";
    static final List<String> PROFILE_PRESETS = List.of(DEFAULT, WORKER, EXPLORER);

    private final Workspace workspace;
    private final AgentPresetOnboardingIds ids;

    AgentPresetOnboardingPolicy(Workspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        ids = AgentPresetOnboardingIds.forWorkspace(workspace.id());
    }

    AgentPresetOnboardingIds ids() {
        return ids;
    }

    void requireCatalog(AgentPresetOnboardingCatalog catalog) {
        for (String presetId : PROFILE_PRESETS) {
            profilePreset(catalog, presetId);
        }
        permissionPreset(catalog, REVIEW);
        permissionPreset(catalog, DEVELOPER);
    }

    AgentPresetOnboardingSelection recoverSelection(
            AgentPresetOnboardingSelection previous, AgentPresetOnboardingCatalog catalog) {
        AgentPresetOnboardingSelection recovered = keepAvailableProviders(previous, catalog);
        Optional<PermissionProfile> review = findPermission(catalog, REVIEW);
        Optional<PermissionProfile> developer = findPermission(catalog, DEVELOPER);
        if (review.isPresent()) {
            recovered = recovered.withTools(REVIEW, review.orElseThrow().tools().allowedTools());
        }
        if (developer.isPresent()) {
            recovered = recovered.withTools(
                    DEVELOPER, developer.orElseThrow().tools().allowedTools());
        }
        for (String presetId : PROFILE_PRESETS) {
            Optional<AgentProfile> profile = findProfile(catalog, presetId);
            if (profile.isPresent()) {
                recovered = recovered.withProvider(
                        presetId, profile.orElseThrow().spec().provider());
            }
        }
        return recovered;
    }

    PermissionPresetInstantiationRequest permissionRequest(AgentPresetOnboardingCatalog catalog, String presetId) {
        PermissionPresetDescriptor preset = permissionPreset(catalog, presetId);
        Optional<PermissionProfile> existing = findPermission(catalog, presetId);
        Set<String> tools =
                existing.map(profile -> profile.tools().allowedTools()).orElse(Set.of());
        Set<String> executables =
                existing.map(profile -> profile.processes().executables()).orElse(Set.of());
        return new PermissionPresetInstantiationRequest(
                preset.id(), preset.revision(), workspace.id(), ids.permissionId(presetId), tools, executables);
    }

    PermissionPresetInstantiationRequest requestFrom(PermissionPresetPreview preview) {
        PermissionProfile proposed = Objects.requireNonNull(preview, "preview").proposedProfile();
        return new PermissionPresetInstantiationRequest(
                preview.preset().id(),
                preview.preset().revision(),
                workspace.id(),
                proposed.id(),
                proposed.tools().allowedTools(),
                proposed.processes().executables());
    }

    void validateExistingResources(
            AgentPresetOnboardingCatalog catalog,
            AgentPresetOnboardingSelection selection,
            PermissionPresetPreview review,
            PermissionPresetPreview developer) {
        validatePermission(catalog, REVIEW, review);
        validatePermission(catalog, DEVELOPER, developer);
        for (String presetId : PROFILE_PRESETS) {
            Optional<AgentProfile> existing = findProfile(catalog, presetId);
            if (existing.isPresent()) {
                AgentProfileSpec expected = profileSpec(catalog, selection, presetId);
                requireProfileMatches(catalog, existing.orElseThrow(), expected);
            }
        }
    }

    void requireReadyToApply(AgentPresetOnboardingState state) {
        if (state.phase() != AgentPresetOnboardingPhase.SELECT_CONFIGURATION
                || !state.permissions().confirmed()
                || !state.permissions().toolsLoaded()) {
            throw new IllegalStateException("请先确认权限并读取工具目录");
        }
        if (!state.selection().modelsComplete()) {
            throw new IllegalStateException("请为 default、worker 和 explorer 分别选择模型");
        }
        Set<ProviderRef> current = Set.copyOf(chatProviders(state.catalog()));
        for (String presetId : PROFILE_PRESETS) {
            ProviderRef selected = state.selection().provider(presetId).orElseThrow();
            if (findProfile(state.catalog(), presetId).isEmpty() && !current.contains(selected)) {
                throw new IllegalStateException("所选模型版本已经不可用，请重新选择");
            }
        }
    }

    AgentProfileSpec profileSpec(
            AgentPresetOnboardingCatalog catalog, AgentPresetOnboardingSelection selection, String presetId) {
        AgentProfilePreset preset = profilePreset(catalog, presetId);
        String permissionPreset = EXPLORER.equals(presetId) ? REVIEW : DEVELOPER;
        PermissionProfile permission = findPermission(catalog, permissionPreset).orElseThrow();
        return new AgentProfileSpec(
                preset.displayName(),
                preset.systemInstruction(),
                selection.provider(presetId).orElseThrow(),
                reference(permission),
                selection.tools(permissionPreset),
                preset.defaultBudget());
    }

    void requireProfileMatches(AgentPresetOnboardingCatalog catalog, AgentProfile profile, AgentProfileSpec expected) {
        AgentProfileSpec actual = profile.spec();
        boolean contentMatches = profile.lifecycle() == ProfileLifecycle.ACTIVE
                && actual.displayName().equals(expected.displayName())
                && actual.systemInstruction().equals(expected.systemInstruction())
                && actual.permissionProfile().equals(expected.permissionProfile())
                && actual.visibleTools().equals(expected.visibleTools())
                && actual.budget().equals(expected.budget());
        if (!contentMatches || !providerCurrentlyEnabled(catalog, actual.provider())) {
            throw conflict("智能体标识 " + profile.id() + " 已被不同内容占用或引用已失效");
        }
    }

    boolean initializationComplete(AgentPresetOnboardingCatalog catalog) {
        Optional<AgentProfile> defaultProfile = findProfile(catalog, DEFAULT);
        boolean allProfiles =
                PROFILE_PRESETS.stream().allMatch(id -> findProfile(catalog, id).isPresent());
        if (!allProfiles || defaultProfile.isEmpty()) {
            return false;
        }
        AgentProfile profile = defaultProfile.orElseThrow();
        AgentProfileRef expected = new AgentProfileRef(profile.id(), profile.revision());
        return catalog.workspaceBinding()
                .map(ProfileBinding::profile)
                .filter(expected::equals)
                .isPresent();
    }

    List<ProviderRef> chatProviders(AgentPresetOnboardingCatalog catalog) {
        return chatProviders(catalog.providers()).stream()
                .filter(reference -> providerCurrentlyEnabled(catalog, reference))
                .toList();
    }

    private static List<ProviderRef> chatProviders(List<ProviderEndpoint> providers) {
        return providers.stream()
                .filter(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ACTIVE)
                .flatMap(endpoint -> endpoint.spec().models().stream()
                        .filter(model -> model.supports(ProviderModelPurpose.CHAT))
                        .map(model -> new ProviderRef(endpoint.id(), endpoint.revision(), model.modelId())))
                .toList();
    }

    boolean toolsFrozenByExistingProfile(AgentPresetOnboardingCatalog catalog, String presetId) {
        if (REVIEW.equals(presetId)) {
            return findProfile(catalog, EXPLORER).isPresent();
        }
        return findProfile(catalog, DEFAULT).isPresent()
                || findProfile(catalog, WORKER).isPresent();
    }

    void requireExistingToolsAvailable(
            AgentPresetOnboardingCatalog catalog,
            AgentPresetOnboardingSelection selection,
            String presetId,
            List<ToolDescriptor> candidates) {
        if (!toolsFrozenByExistingProfile(catalog, presetId)) {
            return;
        }
        if (!toolNames(candidates).containsAll(selection.tools(presetId))) {
            throw conflict("已创建智能体引用的工具已不在当前目录，请在智能体方案中处理");
        }
    }

    Optional<AgentProfile> findProfile(AgentPresetOnboardingCatalog catalog, String presetId) {
        String id = ids.profileId(presetId);
        return catalog.profiles().stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst();
    }

    Optional<AgentProfile> findProfile(List<AgentProfile> profiles, String presetId) {
        String id = ids.profileId(presetId);
        return profiles.stream().filter(profile -> profile.id().equals(id)).findFirst();
    }

    Optional<PermissionProfile> findPermission(AgentPresetOnboardingCatalog catalog, String presetId) {
        String id = ids.permissionId(presetId);
        return catalog.permissions().stream()
                .filter(profile -> profile.id().equals(id))
                .findFirst();
    }

    AgentProfilePreset profilePreset(AgentPresetOnboardingCatalog catalog, String presetId) {
        return catalog.profilePresets().stream()
                .filter(preset -> preset.id().equals(presetId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少内置智能体预设: " + presetId));
    }

    PermissionPresetPreview preview(AgentPresetPermissionSetup setup, String presetId) {
        Optional<PermissionPresetPreview> selected =
                REVIEW.equals(presetId) ? setup.reviewPreview() : setup.developerPreview();
        return selected.orElseThrow(() -> new IllegalStateException("权限预览尚未就绪"));
    }

    Set<String> candidateNames(AgentPresetPermissionSetup setup, String presetId) {
        List<ToolDescriptor> candidates =
                REVIEW.equals(presetId) ? setup.reviewCandidates() : setup.developerCandidates();
        return toolNames(candidates);
    }

    AgentPresetOnboardingCatalog withPermissions(
            AgentPresetOnboardingCatalog catalog, PermissionProfile review, PermissionProfile developer) {
        ArrayList<PermissionProfile> permissions = new ArrayList<>(catalog.permissions());
        permissions.removeIf(
                profile -> profile.id().equals(review.id()) || profile.id().equals(developer.id()));
        permissions.add(review);
        permissions.add(developer);
        return new AgentPresetOnboardingCatalog(
                catalog.profilePresets(),
                catalog.permissionPresets(),
                catalog.providers(),
                catalog.availableProviderVersions(),
                catalog.profiles(),
                permissions,
                catalog.workspaceBinding());
    }

    List<AgentProfile> replaceProfile(List<AgentProfile> profiles, AgentProfile profile) {
        ArrayList<AgentProfile> updated = new ArrayList<>(profiles);
        updated.removeIf(existing -> existing.id().equals(profile.id()));
        updated.add(profile);
        return List.copyOf(updated);
    }

    private AgentPresetOnboardingSelection keepAvailableProviders(
            AgentPresetOnboardingSelection selection, AgentPresetOnboardingCatalog catalog) {
        Set<ProviderRef> available = Set.copyOf(chatProviders(catalog));
        AgentPresetOnboardingSelection result = AgentPresetOnboardingSelection.empty()
                .withTools(REVIEW, selection.reviewTools())
                .withTools(DEVELOPER, selection.developerTools());
        for (String presetId : PROFILE_PRESETS) {
            Optional<ProviderRef> selected = selection.provider(presetId).filter(available::contains);
            if (selected.isPresent()) {
                result = result.withProvider(presetId, selected.orElseThrow());
            }
        }
        return result;
    }

    private void validatePermission(
            AgentPresetOnboardingCatalog catalog, String presetId, PermissionPresetPreview preview) {
        Optional<PermissionProfile> existing = findPermission(catalog, presetId);
        if (existing.isPresent() && !samePermissionPolicy(existing.orElseThrow(), preview.proposedProfile())) {
            throw conflict("权限标识 " + existing.orElseThrow().id() + " 已被不同内容占用");
        }
    }

    private boolean providerCurrentlyEnabled(AgentPresetOnboardingCatalog catalog, ProviderRef reference) {
        boolean latestEnabled = catalog.providers().stream()
                .anyMatch(provider -> provider.id().equals(reference.endpointId())
                        && provider.lifecycle() == ProviderLifecycle.ACTIVE);
        if (!latestEnabled) {
            return false;
        }
        return catalog.availableProviderVersions().stream()
                .anyMatch(provider -> isExactChatProvider(provider, reference));
    }

    private static boolean isExactChatProvider(ProviderEndpoint provider, ProviderRef reference) {
        return provider.id().equals(reference.endpointId())
                && provider.revision() == reference.endpointRevision()
                && provider.lifecycle() == ProviderLifecycle.ACTIVE
                && provider.spec().models().stream()
                        .anyMatch(model ->
                                model.modelId().equals(reference.model()) && model.supports(ProviderModelPurpose.CHAT));
    }

    private static PermissionPresetDescriptor permissionPreset(AgentPresetOnboardingCatalog catalog, String presetId) {
        return catalog.permissionPresets().stream()
                .filter(preset -> preset.id().equals(presetId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少内置权限预设: " + presetId));
    }

    private static PermissionProfileRef reference(PermissionProfile profile) {
        return new PermissionProfileRef(profile.id(), profile.version());
    }

    private static boolean samePermissionPolicy(PermissionProfile left, PermissionProfile right) {
        return left.id().equals(right.id())
                && left.files().equals(right.files())
                && left.network().equals(right.network())
                && left.processes().equals(right.processes())
                && left.tools().equals(right.tools())
                && left.resources().equals(right.resources());
    }

    private static Set<String> toolNames(List<ToolDescriptor> candidates) {
        Set<String> result = new HashSet<>();
        candidates.stream().map(tool -> tool.identity().name()).forEach(result::add);
        return Set.copyOf(result);
    }

    private static AgentPresetOnboardingConflictException conflict(String message) {
        return new AgentPresetOnboardingConflictException(message);
    }
}
