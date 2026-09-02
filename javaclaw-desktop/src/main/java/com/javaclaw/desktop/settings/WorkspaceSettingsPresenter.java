package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;

/** 协调 Workspace 目录、名称草稿和默认 Agent Profile 绑定。 */
public final class WorkspaceSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<WorkspaceSettingsState> listener = ignored -> {};
    private WorkspaceSettingsState state = WorkspaceSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway 强类型 SDK 设置边界
     */
    public WorkspaceSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅不可变状态并立即接收当前值。
     *
     * @param value 页面监听器
     */
    public void subscribe(Consumer<WorkspaceSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取 Workspace 与活动 Profile 目录。 */
    public void reload() {
        long epoch = state.epoch() + 1;
        publish(copy(SettingsLoadState.LOADING, "正在读取 Workspace…", epoch));
        gateway.workspaces()
                .thenCombine(gateway.profiles(), Catalog::new)
                .whenComplete((catalog, failure) -> applyCatalog(epoch, catalog, failure));
    }

    /**
     * 切换 Workspace 并读取其直接 Profile 绑定。
     *
     * @param workspace 目录中的 Workspace
     */
    public void select(Workspace workspace) {
        Workspace checked = Objects.requireNonNull(workspace, "workspace");
        long epoch = state.epoch() + 1;
        publish(new WorkspaceSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                state.profiles(),
                Optional.of(checked),
                Optional.empty(),
                checked.name(),
                Optional.empty(),
                "正在读取默认 Profile…",
                epoch));
        gateway.workspaceProfileBinding(checked.id())
                .whenComplete((binding, failure) -> applyBinding(epoch, checked, binding, failure));
    }

    /** @param name Workspace 名称草稿 */
    public void editName(String name) {
        publish(new WorkspaceSettingsState(
                state.phase(),
                state.workspaces(),
                state.profiles(),
                state.selected(),
                state.binding(),
                Objects.requireNonNullElse(name, ""),
                state.draftProfile(),
                state.message(),
                state.epoch()));
    }

    /** @param profile 默认 Profile 草稿 */
    public void chooseProfile(AgentProfile profile) {
        publish(new WorkspaceSettingsState(
                state.phase(),
                state.workspaces(),
                state.profiles(),
                state.selected(),
                state.binding(),
                state.draftName(),
                Optional.ofNullable(profile),
                state.message(),
                state.epoch()));
    }

    /** 保存 Workspace 名称；根目录不会进入写入 payload。 */
    public void saveName() {
        Workspace current = state.selected().orElseThrow();
        String name = state.draftName().strip();
        if (name.isEmpty()) {
            publish(copy(SettingsLoadState.ERROR, "Workspace 名称不能为空", state.epoch()));
            return;
        }
        execute(gateway.renameWorkspace(current, name, CommandOptions.create(current.revision())), "Workspace 已重命名");
    }

    /** 保存 Workspace 默认 Agent Profile 的精确版本。 */
    public void saveProfile() {
        Workspace workspace = state.selected().orElseThrow();
        AgentProfile profile = state.draftProfile().orElseThrow();
        long expectedRevision = state.binding().map(ProfileBinding::revision).orElse(0L);
        AgentProfileRef reference = new AgentProfileRef(profile.id(), profile.revision());
        execute(
                gateway.bindWorkspaceProfile(workspace.id(), reference, CommandOptions.create(expectedRevision)),
                "默认 Agent Profile 已保存");
    }

    /** 归档 Workspace 登记；不会删除根目录。 */
    public void archive() {
        Workspace current = state.selected().orElseThrow();
        execute(gateway.archiveWorkspace(current, CommandOptions.create(current.revision())), "Workspace 已归档");
    }

    /** 丢弃草稿并恢复最近一次权威值。 */
    public void discardDraft() {
        Optional<AgentProfile> bound = profileFor(state.binding(), state.profiles());
        publish(new WorkspaceSettingsState(
                state.phase(),
                state.workspaces(),
                state.profiles(),
                state.selected(),
                state.binding(),
                state.selected().map(Workspace::name).orElse(""),
                bound,
                "",
                state.epoch()));
    }

    private void applyCatalog(long epoch, Catalog catalog, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        List<Workspace> workspaces = catalog.workspaces().stream()
                .sorted(Comparator.comparing(Workspace::name))
                .toList();
        List<AgentProfile> profiles = catalog.profiles().stream()
                .filter(profile -> profile.lifecycle() == ProfileLifecycle.ACTIVE)
                .sorted(Comparator.comparing(profile -> profile.spec().displayName()))
                .toList();
        Optional<Workspace> selected = selectAfterReload(workspaces);
        publish(new WorkspaceSettingsState(
                SettingsLoadState.READY,
                workspaces,
                profiles,
                selected,
                Optional.empty(),
                selected.map(Workspace::name).orElse(""),
                Optional.empty(),
                "",
                epoch));
        selected.ifPresent(this::select);
    }

    private void applyBinding(long epoch, Workspace workspace, Optional<ProfileBinding> binding, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        Optional<ProfileBinding> checked = Objects.requireNonNull(binding, "binding");
        publish(new WorkspaceSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                state.profiles(),
                Optional.of(workspace),
                checked,
                workspace.name(),
                profileFor(checked, state.profiles()),
                "",
                epoch));
    }

    private void execute(CompletionStage<?> operation, String success) {
        long epoch = state.epoch() + 1;
        publish(copy(SettingsLoadState.LOADING, "正在保存…", epoch));
        operation.whenComplete((ignored, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            } else {
                publish(copy(SettingsLoadState.READY, success, epoch));
                reload();
            }
        });
    }

    private Optional<Workspace> selectAfterReload(List<Workspace> workspaces) {
        Optional<com.javaclaw.api.WorkspaceId> current = state.selected().map(Workspace::id);
        return current.flatMap(id -> workspaces.stream()
                        .filter(workspace -> workspace.id().equals(id))
                        .findFirst())
                .or(() -> workspaces.stream().findFirst());
    }

    private WorkspaceSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return new WorkspaceSettingsState(
                phase,
                state.workspaces(),
                state.profiles(),
                state.selected(),
                state.binding(),
                state.draftName(),
                state.draftProfile(),
                message,
                epoch);
    }

    private void publish(WorkspaceSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }

    private static Optional<AgentProfile> profileFor(Optional<ProfileBinding> binding, List<AgentProfile> profiles) {
        return binding.flatMap(value -> profiles.stream()
                .filter(profile -> profile.id().equals(value.profile().id())
                        && profile.revision() == value.profile().revision())
                .findFirst());
    }

    private record Catalog(List<Workspace> workspaces, List<AgentProfile> profiles) {
        private Catalog {
            workspaces = List.copyOf(workspaces);
            profiles = List.copyOf(profiles);
        }
    }
}
