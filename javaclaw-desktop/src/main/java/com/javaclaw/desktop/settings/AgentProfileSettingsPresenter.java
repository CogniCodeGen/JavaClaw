package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.client.CommandOptions;

/** Agent Profile 设置页的异步状态机；不持有 JavaFX 控件。 */
public final class AgentProfileSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<AgentProfileSettingsState> listener = ignored -> {};
    private AgentProfileSettingsState state = AgentProfileSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public AgentProfileSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅完整状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<AgentProfileSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 并行读取 Profile、Provider 和 PermissionProfile 目录。 */
    public void reload() {
        long epoch = state.epoch() + 1;
        publish(new AgentProfileSettingsState(
                SettingsLoadState.LOADING,
                state.profiles(),
                state.providers(),
                state.permissions(),
                state.selected(),
                state.creating(),
                state.baseline(),
                state.draft(),
                "正在读取智能体方案…",
                false,
                epoch));
        gateway.profiles()
                .thenCombine(gateway.providers(), ProfileCatalog::new)
                .thenCombine(gateway.permissionProfiles(), ProfileCatalog::withPermissions)
                .whenComplete((catalog, failure) -> completeReload(epoch, catalog, failure));
    }

    /**
     * 选择权威 Profile；脏草稿存在时拒绝切换。
     *
     * @param profile 目录项
     */
    public void select(AgentProfile profile) {
        AgentProfile checked = Objects.requireNonNull(profile, "profile");
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        AgentProfileDraft draft = AgentProfileDraft.from(checked);
        publish(new AgentProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                state.providers(),
                state.permissions(),
                Optional.of(checked),
                false,
                draft,
                draft,
                "",
                false,
                state.epoch() + 1));
    }

    /** 开始填写一个新 Profile。 */
    public void createDraft() {
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        AgentProfileDraft empty = AgentProfileDraft.empty();
        publish(new AgentProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                state.providers(),
                state.permissions(),
                Optional.empty(),
                true,
                empty,
                empty,
                "填写新智能体方案",
                false,
                state.epoch() + 1));
    }

    /**
     * 替换当前草稿。
     *
     * @param draft 页面控件投影
     */
    public void updateDraft(AgentProfileDraft draft) {
        publish(new AgentProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                state.providers(),
                state.permissions(),
                state.selected(),
                state.creating(),
                state.baseline(),
                draft,
                state.message(),
                false,
                state.epoch()));
    }

    /** 保存新建或更新 Profile。 */
    public void save() {
        AgentProfileSpec spec;
        try {
            requireId(state.draft().id());
            requireActiveReferences(state.draft());
            spec = state.draft().toSpec();
        } catch (RuntimeException invalid) {
            publishFailure(invalid);
            return;
        }
        publishSaving("正在保存智能体方案…");
        if (state.selected().isEmpty()) {
            gateway.createProfile(state.draft().id(), spec, CommandOptions.create(0))
                    .whenComplete(this::completeWrite);
            return;
        }
        AgentProfile selected = state.selected().orElseThrow();
        gateway.updateProfile(
                        selected.id(), spec, state.draft().lifecycle(), CommandOptions.create(selected.revision()))
                .whenComplete(this::completeWrite);
    }

    /** 归档当前 Profile；活动 Turn 继续使用已经冻结的快照。 */
    public void archive() {
        AgentProfile selected = requireSelected();
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        publishSaving("正在归档智能体方案…");
        gateway.archiveProfile(selected.id(), CommandOptions.create(selected.revision()))
                .whenComplete(this::completeWrite);
    }

    /** 丢弃草稿。 */
    public void discardDraft() {
        publish(new AgentProfileSettingsState(
                SettingsLoadState.READY,
                state.profiles(),
                state.providers(),
                state.permissions(),
                state.selected(),
                false,
                state.baseline(),
                state.baseline(),
                "本地草稿已丢弃",
                false,
                state.epoch()));
    }

    /** 显示统一离页保护说明。 */
    public void warnUnsavedChanges() {
        publish(new AgentProfileSettingsState(
                state.phase(),
                state.profiles(),
                state.providers(),
                state.permissions(),
                state.selected(),
                state.creating(),
                state.baseline(),
                state.draft(),
                "请先保存或放弃智能体方案草稿",
                false,
                state.epoch()));
    }

    /** @return 当前不可变状态 */
    public AgentProfileSettingsState state() {
        return state;
    }

    private void completeReload(long epoch, ProfileCatalog catalog, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new AgentProfileSettingsState(
                    SettingsLoadState.ERROR,
                    state.profiles(),
                    state.providers(),
                    state.permissions(),
                    state.selected(),
                    state.creating(),
                    state.baseline(),
                    state.draft(),
                    SettingsFailures.message(failure),
                    false,
                    epoch));
            return;
        }
        List<AgentProfile> profiles = catalog.profiles();
        Optional<AgentProfile> selected = profiles.stream().findFirst();
        AgentProfileDraft draft = selected.map(AgentProfileDraft::from).orElseGet(AgentProfileDraft::empty);
        String message = profiles.isEmpty() ? "尚未创建智能体方案" : missingCatalogMessage(catalog);
        publish(new AgentProfileSettingsState(
                SettingsLoadState.READY,
                profiles,
                catalog.providers(),
                catalog.permissions(),
                selected,
                false,
                draft,
                draft,
                message,
                false,
                epoch));
    }

    private void completeWrite(AgentProfile profile, Throwable failure) {
        if (failure != null) {
            publishFailure(failure);
            return;
        }
        List<AgentProfile> profiles = replace(state.profiles(), profile);
        AgentProfileDraft draft = AgentProfileDraft.from(profile);
        publish(new AgentProfileSettingsState(
                SettingsLoadState.READY,
                profiles,
                state.providers(),
                state.permissions(),
                Optional.of(profile),
                false,
                draft,
                draft,
                "智能体方案已保存为版本 " + profile.revision(),
                false,
                state.epoch()));
    }

    private void requireActiveReferences(AgentProfileDraft draft) {
        var provider = draft.provider().orElseThrow(() -> new IllegalArgumentException("请选择模型服务和模型"));
        boolean providerAvailable = state.providers().stream()
                .anyMatch(endpoint -> endpoint.id().equals(provider.endpointId())
                        && endpoint.revision() == provider.endpointRevision()
                        && endpoint.lifecycle() == com.javaclaw.api.ProviderLifecycle.ACTIVE
                        && endpoint.spec().models().stream()
                                .anyMatch(model -> model.modelId().equals(provider.model())
                                        && model.supports(ProviderModelPurpose.CHAT)));
        if (!providerAvailable) {
            throw new IllegalArgumentException("所选模型服务和模型 已停用、归档或版本过期");
        }
        var permission = draft.permissionProfile().orElseThrow(() -> new IllegalArgumentException("请选择权限方案"));
        boolean permissionAvailable = state.permissions().stream()
                .anyMatch(profile -> profile.id().equals(permission.id()) && profile.version() == permission.version());
        if (!permissionAvailable) {
            throw new IllegalArgumentException("所选权限方案版本已过期");
        }
    }

    private AgentProfile requireSelected() {
        return state.selected().orElseThrow(() -> new IllegalStateException("请先选择智能体方案"));
    }

    private void publishSaving(String message) {
        publish(new AgentProfileSettingsState(
                SettingsLoadState.SAVING,
                state.profiles(),
                state.providers(),
                state.permissions(),
                state.selected(),
                state.creating(),
                state.baseline(),
                state.draft(),
                message,
                false,
                state.epoch()));
    }

    private void publishFailure(Throwable failure) {
        publish(new AgentProfileSettingsState(
                SettingsLoadState.ERROR,
                state.profiles(),
                state.providers(),
                state.permissions(),
                state.selected(),
                state.creating(),
                state.baseline(),
                state.draft(),
                SettingsFailures.message(failure),
                SettingsFailures.revisionConflict(failure),
                state.epoch()));
    }

    private static String missingCatalogMessage(ProfileCatalog catalog) {
        if (catalog.providers().stream()
                .noneMatch(endpoint -> endpoint.lifecycle() == com.javaclaw.api.ProviderLifecycle.ACTIVE)) {
            return "没有可用于新智能体方案的活动模型服务";
        }
        if (catalog.permissions().isEmpty()) {
            return "没有可用权限方案";
        }
        return "";
    }

    private static String requireId(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("智能体方案标识只能包含字母、数字、点、下划线和连字符");
        }
        return normalized;
    }

    private static List<AgentProfile> replace(List<AgentProfile> current, AgentProfile updated) {
        ArrayList<AgentProfile> result = new ArrayList<>(current);
        result.removeIf(profile -> profile.id().equals(updated.id()));
        result.add(updated);
        result.sort(Comparator.comparing(AgentProfile::id));
        return List.copyOf(result);
    }

    private void publish(AgentProfileSettingsState next) {
        state = next;
        listener.accept(next);
    }

    private record ProfileCatalog(
            List<AgentProfile> profiles, List<ProviderEndpoint> providers, List<PermissionProfile> permissions) {
        private ProfileCatalog(List<AgentProfile> profiles, List<ProviderEndpoint> providers) {
            this(profiles, providers, List.of());
        }

        private ProfileCatalog {
            profiles = List.copyOf(profiles);
            providers = List.copyOf(providers);
            permissions = List.copyOf(permissions);
        }

        private ProfileCatalog withPermissions(List<PermissionProfile> value) {
            return new ProfileCatalog(profiles, providers, value);
        }
    }
}
