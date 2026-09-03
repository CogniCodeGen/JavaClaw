package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfilePreset;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionPresetDescriptor;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** 首次智能体 Presenter 测试使用的可恢复内存边界。 */
final class TestAgentPresetOnboardingGateway implements AgentPresetOnboardingGateway {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final String DIGEST = "0".repeat(64);

    final Workspace workspace;
    final ProviderEndpoint provider = provider();
    final List<ProviderEndpoint> providerVersions = new ArrayList<>(List.of(provider));
    final List<PermissionProfile> permissions = new ArrayList<>();
    final List<AgentProfile> profiles = new ArrayList<>();
    final List<CommandOptions> writes = new ArrayList<>();
    final Map<String, List<CommandOptions>> profileWrites = new HashMap<>();
    Optional<ProfileBinding> binding = Optional.empty();
    ProviderEndpoint latestProvider = provider;
    String failProfileOnce = "";
    int permissionInstantiations;
    int profileCreations;

    TestAgentPresetOnboardingGateway(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public CompletionStage<AgentPresetOnboardingCatalog> load(WorkspaceId workspaceId) {
        requireWorkspace(workspaceId);
        return completed(new AgentPresetOnboardingCatalog(
                profilePresets(),
                permissionPresets(),
                List.of(latestProvider),
                List.copyOf(providerVersions),
                List.copyOf(profiles),
                List.copyOf(permissions),
                binding));
    }

    @Override
    public CompletionStage<PermissionPresetPreview> previewPermission(PermissionPresetInstantiationRequest request) {
        requireWorkspace(request.workspaceId());
        PermissionPresetDescriptor preset = permissionPresets().stream()
                .filter(candidate -> candidate.id().equals(request.presetId()))
                .findFirst()
                .orElseThrow();
        PermissionProfile profile = permission(request);
        return completed(new PermissionPresetPreview(preset, workspace.id(), profile, List.of()));
    }

    @Override
    public CompletionStage<PermissionPresetInstantiationResult> instantiatePermission(
            PermissionPresetInstantiationRequest request, CommandOptions options) {
        writes.add(options);
        permissionInstantiations++;
        if (permissions.stream().anyMatch(profile -> profile.id().equals(request.profileId()))) {
            return CompletableFuture.failedFuture(new IllegalStateException("revision conflict"));
        }
        PermissionProfile profile = permission(request);
        permissions.add(profile);
        PermissionPresetDescriptor preset = permissionPresets().stream()
                .filter(candidate -> candidate.id().equals(request.presetId()))
                .findFirst()
                .orElseThrow();
        return completed(new PermissionPresetInstantiationResult(preset, workspace.id(), profile));
    }

    @Override
    public CompletionStage<List<ToolDescriptor>> searchTools(
            WorkspaceId workspaceId, PermissionProfileRef permissionProfile) {
        requireWorkspace(workspaceId);
        PermissionProfile profile = permissions.stream()
                .filter(candidate -> candidate.id().equals(permissionProfile.id())
                        && candidate.version() == permissionProfile.version())
                .findFirst()
                .orElseThrow();
        List<ToolDescriptor> tools = new ArrayList<>();
        tools.add(tool("read_file", ToolRisk.READ_ONLY));
        if (profile.tools().maximumRisk().ordinal() >= ToolRisk.WORKSPACE_WRITE.ordinal()) {
            tools.add(tool("write_file", ToolRisk.WORKSPACE_WRITE));
        }
        return completed(List.copyOf(tools));
    }

    @Override
    public CompletionStage<PermissionProfile> updatePermission(PermissionProfile profile, CommandOptions options) {
        writes.add(options);
        PermissionProfile current = permissions.stream()
                .filter(candidate -> candidate.id().equals(profile.id()))
                .findFirst()
                .orElseThrow();
        if (current.version() != options.expectedRevision()) {
            return CompletableFuture.failedFuture(new IllegalStateException("revision conflict"));
        }
        permissions.remove(current);
        permissions.add(profile);
        return completed(profile);
    }

    @Override
    public CompletionStage<AgentProfile> createProfile(String id, AgentProfileSpec spec, CommandOptions options) {
        writes.add(options);
        profileWrites.computeIfAbsent(id, ignored -> new ArrayList<>()).add(options);
        if (id.equals(failProfileOnce)) {
            failProfileOnce = "";
            return CompletableFuture.failedFuture(new IllegalStateException("连接在提交前中断"));
        }
        if (profiles.stream().anyMatch(profile -> profile.id().equals(id))) {
            return CompletableFuture.failedFuture(new IllegalStateException("revision conflict"));
        }
        profileCreations++;
        AgentProfile profile = new AgentProfile(id, 1, ProfileLifecycle.ACTIVE, spec, NOW, NOW);
        profiles.add(profile);
        return completed(profile);
    }

    @Override
    public CompletionStage<ProfileBinding> bindDefault(
            WorkspaceId workspaceId, AgentProfileRef profile, CommandOptions options) {
        requireWorkspace(workspaceId);
        writes.add(options);
        long current = binding.map(ProfileBinding::revision).orElse(0L);
        if (current != options.expectedRevision()) {
            return CompletableFuture.failedFuture(new IllegalStateException("revision conflict"));
        }
        ProfileBinding updated =
                new ProfileBinding(workspace.id(), Optional.<ThreadId>empty(), profile, current + 1, NOW);
        binding = Optional.of(updated);
        return completed(updated);
    }

    void addPermissionProfiles() {
        AgentPresetOnboardingIds ids = AgentPresetOnboardingIds.forWorkspace(workspace.id());
        permissions.add(permission(new PermissionPresetInstantiationRequest(
                AgentPresetOnboardingPolicy.REVIEW,
                1,
                workspace.id(),
                ids.reviewPermission(),
                Set.of("read_file"),
                Set.of())));
        permissions.add(permission(new PermissionPresetInstantiationRequest(
                AgentPresetOnboardingPolicy.DEVELOPER,
                1,
                workspace.id(),
                ids.developerPermission(),
                Set.of("read_file", "write_file"),
                Set.of())));
    }

    void advanceProvider(ProviderLifecycle lifecycle) {
        latestProvider = new ProviderEndpoint(
                provider.id(),
                provider.revision() + 1,
                lifecycle,
                provider.spec(),
                provider.createdAt(),
                NOW.plusSeconds(1));
        providerVersions.add(latestProvider);
    }

    private PermissionProfile permission(PermissionPresetInstantiationRequest request) {
        boolean review = request.presetId().equals(AgentPresetOnboardingPolicy.REVIEW);
        return new PermissionProfile(
                request.profileId(),
                1,
                new FilePermission(
                        List.of(workspace.root()), review ? List.of() : List.of(workspace.root()), !review, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(request.executables(), false, Duration.ofSeconds(review ? 30 : 300)),
                new ToolPermission(
                        request.allowedTools(),
                        review ? ToolRisk.READ_ONLY : ToolRisk.PROCESS,
                        ApprovalRequirement.RISKY),
                new ResourceLimits(
                        review ? 256L * 1024 * 1024 : 1024L * 1024 * 1024,
                        review ? 16L * 1024 * 1024 : 64L * 1024 * 1024,
                        review ? 1 : 8,
                        review ? 32 : 128));
    }

    private void requireWorkspace(WorkspaceId workspaceId) {
        if (!workspace.id().equals(workspaceId)) {
            throw new IllegalArgumentException("Workspace 漂移");
        }
    }

    private static List<AgentProfilePreset> profilePresets() {
        return List.of(
                preset("default", "默认智能体", "default prompt", 32, 4, Duration.ofMinutes(15)),
                preset("worker", "Worker", "worker prompt", 24, 0, Duration.ofMinutes(10)),
                preset("explorer", "Explorer", "explorer prompt", 24, 0, Duration.ofMinutes(5)));
    }

    private static AgentProfilePreset preset(
            String id, String name, String prompt, int tools, int children, Duration duration) {
        return new AgentProfilePreset(
                id, 1, name, "test " + id, prompt, DIGEST, new TurnBudget(32_000, 4_000, tools, children, duration));
    }

    private static List<PermissionPresetDescriptor> permissionPresets() {
        return List.of(
                new PermissionPresetDescriptor(AgentPresetOnboardingPolicy.REVIEW, 1, "Workspace 只读审阅", "只读", false),
                new PermissionPresetDescriptor(
                        AgentPresetOnboardingPolicy.DEVELOPER, 1, "Workspace 开发", "读写并审批", true));
    }

    private static ProviderEndpoint provider() {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "本地测试模型",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://models.example.test/v1")),
                ProviderAuthentication.NONE,
                List.of(
                        new ProviderModelSpec(
                                "chat-model", "Chat Model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                        new ProviderModelSpec(
                                "embedding-model",
                                "Embedding Model",
                                Set.of(ProviderModelPurpose.EMBEDDING),
                                OptionalInt.of(768))),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        return new ProviderEndpoint("provider-main", 1, ProviderLifecycle.ACTIVE, spec, NOW, NOW);
    }

    private static ToolDescriptor tool(String name, ToolRisk risk) {
        return new ToolDescriptor(
                new ToolIdentity("core", name, 1),
                "测试工具 " + name,
                new CanonicalPayload("{}"),
                new CanonicalPayload("{}"),
                risk,
                Set.of("test"));
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
