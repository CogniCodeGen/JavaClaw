package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.Workspace;

/** 首次智能体初始化的异步状态机；不持有 JavaFX 控件。 */
public final class AgentPresetOnboardingPresenter {
    static final String DEFAULT = AgentPresetOnboardingPolicy.DEFAULT;
    static final String WORKER = AgentPresetOnboardingPolicy.WORKER;
    static final String EXPLORER = AgentPresetOnboardingPolicy.EXPLORER;
    static final String REVIEW = AgentPresetOnboardingPolicy.REVIEW;
    static final String DEVELOPER = AgentPresetOnboardingPolicy.DEVELOPER;

    private final AgentPresetOnboardingGateway gateway;
    private final AgentPresetOnboardingPolicy policy;
    private Consumer<AgentPresetOnboardingState> listener = ignored -> {};
    private AgentPresetOnboardingState state;

    /**
     * 创建固定 Workspace 的初始化状态机。
     *
     * @param workspace 打开向导时冻结的 Workspace 快照
     * @param gateway Java SDK 异步边界
     */
    public AgentPresetOnboardingPresenter(Workspace workspace, AgentPresetOnboardingGateway gateway) {
        state = AgentPresetOnboardingState.initial(Objects.requireNonNull(workspace, "workspace"));
        policy = new AgentPresetOnboardingPolicy(workspace);
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅完整状态并立即收到当前快照。
     *
     * @param value Dialog 渲染回调
     */
    public void subscribe(Consumer<AgentPresetOnboardingState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取预设、目录、确定性资源和 Workspace 默认绑定，并恢复未完成步骤。 */
    public void reload() {
        long epoch = state.epoch() + 1;
        publish(withPhase(AgentPresetOnboardingPhase.LOADING, "正在检查初始化状态…", epoch));
        gateway.load(state.workspace().id())
                .whenComplete((catalog, failure) -> completeCatalog(epoch, catalog, failure));
    }

    /**
     * 为一个尚未创建的内置 Profile 选择精确 Chat ProviderRef。
     *
     * @param presetId default、worker 或 explorer
     * @param provider 当前目录中的精确 Chat ProviderRef
     */
    public void selectProvider(String presetId, ProviderRef provider) {
        if (policy.findProfile(state.catalog(), presetId).isPresent()) {
            publishMessage("该智能体已经创建，模型引用不能由初始化向导覆盖");
            return;
        }
        if (!chatProviders().contains(Objects.requireNonNull(provider, "provider"))) {
            publishMessage("所选模型不是当前可用的精确 Chat 模型");
            return;
        }
        publish(withSelection(state.selection().withProvider(presetId, provider), ""));
    }

    /**
     * 显式确认两份权限预览并创建尚不存在的普通 PermissionProfile。
     *
     * @param confirmed 只有用户勾选确认后才允许为 true
     */
    public void confirmPermissions(boolean confirmed) {
        if (!confirmed) {
            publishMessage("请先核对两份权限边界并勾选确认");
            return;
        }
        if (!state.permissions().previewsReady()) {
            publishMessage("权限预览尚未就绪，请先重试读取");
            return;
        }
        long epoch = state.epoch() + 1;
        publish(withPhase(AgentPresetOnboardingPhase.APPLYING, "正在创建权限方案…", epoch));
        instantiatePermissions(epoch);
    }

    /**
     * 选择一个权限预设实际允许且可见的精确工具名。
     *
     * @param presetId workspace-review 或 workspace-developer
     * @param tools 用户选择的精确名称
     */
    public void selectTools(String presetId, Set<String> tools) {
        Set<String> checked = Set.copyOf(Objects.requireNonNull(tools, "tools"));
        if (!state.permissions().toolsLoaded()) {
            publishMessage("工具目录尚未读取完成");
            return;
        }
        if (policy.toolsFrozenByExistingProfile(state.catalog(), presetId)
                && !state.selection().tools(presetId).equals(checked)) {
            publishMessage("相关智能体已经创建，不能修改其初始化工具集合");
            return;
        }
        Set<String> candidates = policy.candidateNames(state.permissions(), presetId);
        if (checked.stream().anyMatch(tool -> tool.indexOf('*') >= 0 || !candidates.contains(tool))) {
            publishMessage("只能选择当前目录中的精确工具名，不能使用通配符");
            return;
        }
        publish(withSelection(state.selection().withTools(presetId, checked), ""));
    }

    /** 更新权限工具版本、创建三个普通 Profile，并只绑定 default 为 Workspace 默认。 */
    public void apply() {
        try {
            policy.requireReadyToApply(state);
        } catch (RuntimeException invalid) {
            publishMessage(SettingsFailures.message(invalid));
            return;
        }
        long epoch = state.epoch() + 1;
        publish(withPhase(AgentPresetOnboardingPhase.APPLYING, "正在完成智能体初始化…", epoch));
        updatePermissionTools()
                .thenCompose(this::createMissingProfiles)
                .thenCompose(this::bindDefaultProfile)
                .whenComplete((ignored, failure) -> completeApply(epoch, failure));
    }

    /** @return 当前不可变状态 */
    public AgentPresetOnboardingState state() {
        return state;
    }

    /** @return 当前 ACTIVE Provider 中逐模型展开的精确 Chat 引用 */
    public List<ProviderRef> chatProviders() {
        return policy.chatProviders(state.catalog());
    }

    private void completeCatalog(long epoch, AgentPresetOnboardingCatalog catalog, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        try {
            policy.requireCatalog(catalog);
            AgentPresetOnboardingSelection selection = policy.recoverSelection(state.selection(), catalog);
            publish(new AgentPresetOnboardingState(
                    AgentPresetOnboardingPhase.LOADING,
                    state.workspace(),
                    catalog,
                    selection,
                    AgentPresetPermissionSetup.empty(),
                    "正在校验权限预设…",
                    epoch));
            previewPermissions(epoch, catalog);
        } catch (RuntimeException invalid) {
            fail(epoch, invalid);
        }
    }

    private void previewPermissions(long epoch, AgentPresetOnboardingCatalog catalog) {
        PermissionPresetInstantiationRequest review = policy.permissionRequest(catalog, REVIEW);
        PermissionPresetInstantiationRequest developer = policy.permissionRequest(catalog, DEVELOPER);
        gateway.previewPermission(review)
                .thenCombine(gateway.previewPermission(developer), PreviewPair::new)
                .whenComplete((previews, failure) -> completePreviews(epoch, previews, failure));
    }

    private void completePreviews(long epoch, PreviewPair previews, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        try {
            policy.validateExistingResources(
                    state.catalog(), state.selection(), previews.review(), previews.developer());
            if (policy.initializationComplete(state.catalog())) {
                publish(completedState(epoch));
                return;
            }
            boolean persisted =
                    permission(REVIEW).isPresent() && permission(DEVELOPER).isPresent();
            AgentPresetPermissionSetup setup = new AgentPresetPermissionSetup(
                    Optional.of(previews.review()),
                    Optional.of(previews.developer()),
                    List.of(),
                    List.of(),
                    persisted,
                    false);
            AgentPresetOnboardingPhase phase =
                    persisted ? AgentPresetOnboardingPhase.LOADING : AgentPresetOnboardingPhase.REVIEW_PERMISSIONS;
            publish(withPermissions(setup, phase, persisted ? "正在读取可选工具…" : "请核对并确认权限边界"));
            if (persisted) {
                loadTools(epoch);
            }
        } catch (RuntimeException invalid) {
            fail(epoch, invalid);
        }
    }

    private void instantiatePermissions(long epoch) {
        CompletionStage<PermissionProfile> review = instantiatePermission(REVIEW);
        CompletionStage<PermissionProfile> developer = instantiatePermission(DEVELOPER);
        review.thenCombine(developer, PermissionPair::new)
                .whenComplete((permissions, failure) -> completeInstantiation(epoch, permissions, failure));
    }

    private CompletionStage<PermissionProfile> instantiatePermission(String presetId) {
        Optional<PermissionProfile> existing = permission(presetId);
        if (existing.isPresent()) {
            return CompletableFuture.completedFuture(existing.orElseThrow());
        }
        PermissionPresetPreview preview = policy.preview(state.permissions(), presetId);
        PermissionPresetInstantiationRequest request = policy.requestFrom(preview);
        return gateway.instantiatePermission(
                        request,
                        AgentPresetOnboardingCommands.instantiatePermission(
                                state.workspace().id(), presetId))
                .thenApply(PermissionPresetInstantiationResult::profile);
    }

    private void completeInstantiation(long epoch, PermissionPair pair, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        AgentPresetOnboardingCatalog catalog = policy.withPermissions(state.catalog(), pair.review(), pair.developer());
        AgentPresetPermissionSetup setup = new AgentPresetPermissionSetup(
                state.permissions().reviewPreview(),
                state.permissions().developerPreview(),
                List.of(),
                List.of(),
                true,
                false);
        publish(new AgentPresetOnboardingState(
                AgentPresetOnboardingPhase.LOADING,
                state.workspace(),
                catalog,
                state.selection(),
                setup,
                "权限方案已创建，正在读取可选工具…",
                epoch));
        loadTools(epoch);
    }

    private void loadTools(long epoch) {
        PermissionProfile review = permission(REVIEW).orElseThrow();
        PermissionProfile developer = permission(DEVELOPER).orElseThrow();
        gateway.searchTools(state.workspace().id(), reference(review))
                .thenCombine(gateway.searchTools(state.workspace().id(), reference(developer)), ToolCandidatePair::new)
                .whenComplete((candidates, failure) -> completeTools(epoch, candidates, failure));
    }

    private void completeTools(long epoch, ToolCandidatePair candidates, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        try {
            policy.requireExistingToolsAvailable(state.catalog(), state.selection(), REVIEW, candidates.review());
            policy.requireExistingToolsAvailable(state.catalog(), state.selection(), DEVELOPER, candidates.developer());
            AgentPresetPermissionSetup setup = new AgentPresetPermissionSetup(
                    state.permissions().reviewPreview(),
                    state.permissions().developerPreview(),
                    candidates.review(),
                    candidates.developer(),
                    true,
                    true);
            publish(withPermissions(setup, AgentPresetOnboardingPhase.SELECT_CONFIGURATION, "请选择三个模型与需要公开的精确工具"));
        } catch (RuntimeException invalid) {
            fail(epoch, invalid);
        }
    }

    private CompletionStage<PermissionPair> updatePermissionTools() {
        CompletionStage<PermissionProfile> review = updatePermissionTools(REVIEW);
        CompletionStage<PermissionProfile> developer = updatePermissionTools(DEVELOPER);
        return review.thenCombine(developer, PermissionPair::new).thenApply(pair -> {
            AgentPresetOnboardingCatalog catalog =
                    policy.withPermissions(state.catalog(), pair.review(), pair.developer());
            publish(new AgentPresetOnboardingState(
                    state.phase(),
                    state.workspace(),
                    catalog,
                    state.selection(),
                    state.permissions(),
                    state.message(),
                    state.epoch()));
            return pair;
        });
    }

    private CompletionStage<PermissionProfile> updatePermissionTools(String presetId) {
        PermissionProfile current = permission(presetId).orElseThrow();
        Set<String> selected = state.selection().tools(presetId);
        if (current.tools().allowedTools().equals(selected)) {
            return CompletableFuture.completedFuture(current);
        }
        ToolPermission tools = new ToolPermission(
                selected, current.tools().maximumRisk(), current.tools().approvalRequirement());
        PermissionProfile updated = new PermissionProfile(
                current.id(),
                current.version() + 1,
                current.files(),
                current.network(),
                current.processes(),
                tools,
                current.resources());
        return gateway.updatePermission(
                updated,
                AgentPresetOnboardingCommands.updatePermission(state.workspace().id(), updated));
    }

    private CompletionStage<List<AgentProfile>> createMissingProfiles(PermissionPair ignored) {
        CompletionStage<List<AgentProfile>> result =
                CompletableFuture.completedFuture(state.catalog().profiles());
        for (String presetId : AgentPresetOnboardingPolicy.PROFILE_PRESETS) {
            result = result.thenCompose(profiles -> createMissingProfile(presetId, profiles));
        }
        return result;
    }

    private CompletionStage<List<AgentProfile>> createMissingProfile(
            String presetId, List<AgentProfile> currentProfiles) {
        AgentProfileSpec expected = policy.profileSpec(state.catalog(), state.selection(), presetId);
        Optional<AgentProfile> existing = policy.findProfile(currentProfiles, presetId);
        if (existing.isPresent()) {
            policy.requireProfileMatches(state.catalog(), existing.orElseThrow(), expected);
            return CompletableFuture.completedFuture(currentProfiles);
        }
        String id = policy.ids().profileId(presetId);
        return gateway.createProfile(
                        id,
                        expected,
                        AgentPresetOnboardingCommands.createProfile(
                                state.workspace().id(), presetId, expected))
                .thenApply(created -> policy.replaceProfile(currentProfiles, created));
    }

    private CompletionStage<ProfileBinding> bindDefaultProfile(List<AgentProfile> profiles) {
        AgentProfile profile = policy.findProfile(profiles, DEFAULT).orElseThrow();
        AgentProfileRef reference = new AgentProfileRef(profile.id(), profile.revision());
        Optional<ProfileBinding> existing = state.catalog().workspaceBinding();
        if (existing.map(ProfileBinding::profile).filter(reference::equals).isPresent()) {
            return CompletableFuture.completedFuture(existing.orElseThrow());
        }
        long expectedRevision = existing.map(ProfileBinding::revision).orElse(0L);
        return gateway.bindDefault(
                state.workspace().id(),
                reference,
                AgentPresetOnboardingCommands.bindDefault(
                        state.workspace().id(), profile.id(), profile.revision(), expectedRevision));
    }

    private void completeApply(long epoch, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        reload();
    }

    private Optional<PermissionProfile> permission(String presetId) {
        return policy.findPermission(state.catalog(), presetId);
    }

    private AgentPresetOnboardingState completedState(long epoch) {
        return new AgentPresetOnboardingState(
                AgentPresetOnboardingPhase.COMPLETED,
                state.workspace(),
                state.catalog(),
                state.selection(),
                state.permissions(),
                "内置智能体已完成初始化",
                epoch);
    }

    private AgentPresetOnboardingState withPhase(AgentPresetOnboardingPhase phase, String message, long epoch) {
        return new AgentPresetOnboardingState(
                phase, state.workspace(), state.catalog(), state.selection(), state.permissions(), message, epoch);
    }

    private AgentPresetOnboardingState withSelection(AgentPresetOnboardingSelection selection, String message) {
        return new AgentPresetOnboardingState(
                state.phase(),
                state.workspace(),
                state.catalog(),
                selection,
                state.permissions(),
                message,
                state.epoch());
    }

    private AgentPresetOnboardingState withPermissions(
            AgentPresetPermissionSetup permissions, AgentPresetOnboardingPhase phase, String message) {
        return new AgentPresetOnboardingState(
                phase, state.workspace(), state.catalog(), state.selection(), permissions, message, state.epoch());
    }

    private void publishMessage(String message) {
        publish(new AgentPresetOnboardingState(
                state.phase(),
                state.workspace(),
                state.catalog(),
                state.selection(),
                state.permissions(),
                message,
                state.epoch()));
    }

    private void fail(long epoch, Throwable failure) {
        AgentPresetOnboardingPhase phase =
                isConflict(failure) ? AgentPresetOnboardingPhase.CONFLICT : AgentPresetOnboardingPhase.ERROR;
        publish(new AgentPresetOnboardingState(
                phase,
                state.workspace(),
                state.catalog(),
                state.selection(),
                state.permissions(),
                SettingsFailures.message(failure),
                epoch));
    }

    private void publish(AgentPresetOnboardingState value) {
        state = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }

    private static PermissionProfileRef reference(PermissionProfile profile) {
        return new PermissionProfileRef(profile.id(), profile.version());
    }

    private static boolean isConflict(Throwable failure) {
        if (SettingsFailures.revisionConflict(failure)) {
            return true;
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AgentPresetOnboardingConflictException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record PreviewPair(PermissionPresetPreview review, PermissionPresetPreview developer) {}

    private record PermissionPair(PermissionProfile review, PermissionProfile developer) {}

    private record ToolCandidatePair(List<ToolDescriptor> review, List<ToolDescriptor> developer) {
        private ToolCandidatePair {
            review = List.copyOf(review);
            developer = List.copyOf(developer);
        }
    }
}
